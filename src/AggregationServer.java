import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.*;

/**
 * Aggregation server that handles HTTP PUT and GET requests from the content server and client.
 * The server receives and sends weather data, and stores it in a persistent state in a file.
 * Maintains Lamport clocks for synchronization and removes old data.
 * Client connections are handled concurrently using a thread pool.
 */
public class AggregationServer {
    // Store JSON data in a map
    private static ConcurrentHashMap<String, WeatherDataEntry> weatherDataMap = new ConcurrentHashMap<>();

    private static final String DATA_STORE_FILE = "weather_data_store.txt";
    private static volatile long lamportClock = 0;
    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    /**
     * Starts the aggregation server.
     * Loads any existing weather data, starts clean up thread, and listens for client connections.
     *
     * @param args command line arguments:
     *             args[0] - port number (default 4567)
     */
    public static void main(String[] args) {
        int portNumber = 4567; // Default port
        if (args.length > 0) {
            portNumber = Integer.parseInt(args[0]);
        }

        // Load existing weather data
        loadDataFile();

        // Start background cleanup thread
        startCleanupThread();

        try (ServerSocket serverSocket = new ServerSocket(portNumber)) {
            System.out.println("Aggregation Server listening on port " + portNumber);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Could not listen on port " + portNumber);
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }

    /***
     * Handles a single client connection in a separate thread.
     * The client socket is automatically closed when the request has been processed.
     */
    private static class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                // Read and parse the HTTP Request
                String requestLine = in.readLine();
                if (requestLine == null) return;
                String[] requestParts = requestLine.split(" ");
                String method = requestParts[0];
                String path = requestParts[1];

                // Read headers to find Lamport clock and content length
                long receivedLamportTime = 0;
                int contentLength = 0;
                String headerLine;
                while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                    if (headerLine.startsWith("Lamport-Clock:")) {
                        receivedLamportTime = Long.parseLong(headerLine.split(":")[1].trim());
                    }
                    if (headerLine.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    }
                }

                // Update Lamport Clock
                synchronized (AggregationServer.class) {
                    lamportClock = Math.max(lamportClock, receivedLamportTime) + 1;
                }

                // Handle the request
                if ("PUT".equalsIgnoreCase(method)) {
                    handlePutRequest(in, out, contentLength);
                } else if ("GET".equalsIgnoreCase(method)) {
                    handleGetRequest(out, requestLine);
                } else {
                    sendResponse(out, 400, "Bad Request", "Only PUT and GET methods are supported.");
                }

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Saves the current weather data in weatherDataMap to a persistent file.
     * Saves in format: stationId|timestamp|jsonData.
     */
    private static void saveDataToFile() {
        File tempFile = new File(DATA_STORE_FILE + ".tmp");

        try (PrintWriter writer = new PrintWriter(new FileWriter(tempFile))) {
            synchronized (weatherDataMap) {
                for (Map.Entry<String, WeatherDataEntry> entry : weatherDataMap.entrySet()) {
                    String stationId = entry.getKey();
                    WeatherDataEntry dataEntry = entry.getValue();

                    String compactJson = dataEntry.jsonData.replaceAll("\\s+", "").replaceAll("\\n", "");

                    writer.println(stationId + "|" + dataEntry.lastUpdatedTimestamp + "|" + compactJson);
                }
            }
        } catch (IOException e) {
            System.err.println("Error saving data to disk: " + e.getMessage());
            e.printStackTrace();
        }

        File actualFile = new File(DATA_STORE_FILE);
        if (actualFile.exists()) {
            actualFile.delete();
        }
        tempFile.renameTo(actualFile);
    }

    /**
     * Loads the weather data from file into the weatherDataMap.
     */
    private static void loadDataFile() {
        File file = new File(DATA_STORE_FILE);
        if (!file.exists()) {
            System.out.println("No existing data file found. Starting with empty database.");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int loadedCount = 0;
            while ((line = reader.readLine()) != null) {
                try {
                    // Split the line into: stationId|timestamp|jsonData
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) {
                        String stationId = parts[0];
                        long timestamp = Long.parseLong(parts[1]);
                        String jsonData = parts[2];

                        WeatherDataEntry entry = new WeatherDataEntry(jsonData);
                        entry.lastUpdatedTimestamp = timestamp;

                        weatherDataMap.put(stationId, entry);
                        loadedCount++;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Error parsing timestamp in data file: " + line);
                } catch (Exception e) {
                    System.err.println("Error parsing line in data file: " + line);
                }
            }

            System.out.println("Successfully loaded " + loadedCount + " weather stations from disk.");
        } catch (IOException e) {
            System.err.println("Error reading data file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles HTTP PUT request from the content server.
     * Reads the JSON body of the request, extracts the station ID,
     * and updates weatherDataMap.
     * After storing the data, the Lamport clock is incremented to maintain
     * logical time consistency.
     *
     * @param in the BufferedReader used to read the incoming request body
     * @param out out the PrintWriter used to send the response to the client
     * @param contentLength the character length to read from the request body
     * @throws IOException if reading from input stream fails
     */
    private static void handlePutRequest(BufferedReader in, PrintWriter out, int contentLength) throws IOException {
        // Read the JSON body
        char[] bodyChars = new char[contentLength];
        in.read(bodyChars, 0, contentLength);
        String jsonBody = new String(bodyChars);

        // Extract station ID
        String stationId;
        try {
            if (!jsonBody.contains("\"id\"")) {
                sendResponse(out, 500, "Invalid JSON", "JSON must contain an 'id' field.");
                return;
            }

            // Find index after "id"
            int idLabelIndex = jsonBody.indexOf("\"id\"");
            if (idLabelIndex == -1) {
                throw new Exception("ID field not found");
            }

            // Find the colon after "id"
            int colonIndex = jsonBody.indexOf(":", idLabelIndex);
            if (colonIndex == -1) {
                throw new Exception("Colon not found after ID");
            }

            // Find the first quote after the colon
            int firstQuoteIndex = jsonBody.indexOf("\"", colonIndex);
            if (firstQuoteIndex == -1) {
                throw new Exception("Opening quote not found for ID value");
            }

            // Find the closing quote after the first quote
            int secondQuoteIndex = jsonBody.indexOf("\"", firstQuoteIndex + 1);
            if (secondQuoteIndex == -1) {
                throw new Exception("Closing quote not found for ID value");
            }

            stationId = jsonBody.substring(firstQuoteIndex + 1, secondQuoteIndex);

        } catch (Exception e) {
            sendResponse(out, 500, "Invalid JSON", "Could not parse JSON body.");
            return;
        }

        // Check if this is new data or an update
        boolean isNewData = !weatherDataMap.containsKey(stationId);

        // Store weather data
        synchronized (weatherDataMap) {
            WeatherDataEntry newEntry = new WeatherDataEntry(jsonBody);
            weatherDataMap.put(stationId, newEntry);
            saveDataToFile();
        }

        if (isNewData) {
            sendResponse(out, 201, "Created", "Weather data successfully stored.");
        } else {
            sendResponse(out, 200, "OK", "Weather data successfully updated.");
        }

        // Update Lamport Clock
        synchronized (AggregationServer.class) {
            lamportClock++;
            System.out.println("LAMPORT CLOCK after PUT: " + lamportClock);
        }
    }

    /**
     * Handles HTTP GET request from client
     *
     * @param out the PrintWriter used to send the response to the client
     * @param requestLine requestLine the first line of the HTTP request containing the method, path, and HTTP version
     */
    private static void handleGetRequest(PrintWriter out, String requestLine) {
        // Parse query parameters from the request line
        String stationId = null;
        if (requestLine.contains("?id=")) {
            String[] parts = requestLine.split("\\?id=");
            if (parts.length > 1) {
                stationId = parts[1].split("\\s")[0]; // Get station ID, stop at whitespace
            }
        }

        StringBuilder responseBuilder = new StringBuilder();

        synchronized (weatherDataMap) {
            if (weatherDataMap.isEmpty()) {
                sendResponse(out, 204, "No Content", "{}");
                return;
            }

            // If specific station ID requested, return only that station
            if (stationId != null) {
                WeatherDataEntry entry = weatherDataMap.get(stationId);
                if (entry != null) {
                    sendResponse(out, 200, "OK", entry.jsonData);
                } else {
                    sendResponse(out, 404, "Not Found", "Station ID '" + stationId + "' not found");
                }
            } else {
                // Return all stations
                responseBuilder.append("[");
                Iterator<WeatherDataEntry> values = weatherDataMap.values().iterator();
                while (values.hasNext()) {
                    WeatherDataEntry entry = values.next();
                    responseBuilder.append(entry.jsonData);
                    if (values.hasNext()) {
                        responseBuilder.append(",");
                    }
                }
                responseBuilder.append("]");

                String responseData = responseBuilder.toString();
                sendResponse(out, 200, "OK", responseData);
            }
        }

        // Update Lamport Clock
        synchronized (AggregationServer.class) {
            lamportClock++;
            System.out.println("LAMPORT CLOCK after GET: " + lamportClock);
        }
    }

    /**
     * Sends HTTP response to client.
     *
     * @param out the PrintWriter used to send the response to the client
     * @param statusCode the HTTP status code
     * @param statusMessage the HTTP status message
     * @param body JSON body to send
     */
    private static void sendResponse(PrintWriter out, int statusCode, String statusMessage, String body) {
        out.printf("HTTP/1.1 %d %s\r\n", statusCode, statusMessage);
        out.printf("Lamport-Clock: %d\r\n", lamportClock);
        out.println("Content-Type: application/json");
        out.println("Connection: close");
        out.printf("Content-Length: %d\r\n", body.length());
        out.println();
        out.println(body);
        out.flush();
    }

    /**
     * Starts a background thread that periodically removes weather data older than 30 seconds.
     * Checks if there is any stale weather data every 5 seconds.
     */
    private static void startCleanupThread() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            long thirtySecondsAgo = currentTime - (30 * 1000);

            synchronized (weatherDataMap) {
                Iterator<Map.Entry<String, WeatherDataEntry>> iterator = weatherDataMap.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, WeatherDataEntry> entry = iterator.next();
                    if (entry.getValue().lastUpdatedTimestamp < thirtySecondsAgo) {
                        System.out.println("Removing stale data for station: " + entry.getKey());
                        iterator.remove();
                    }
                }
                saveDataToFile();
            }
        }, 0, 5, TimeUnit.SECONDS); // Run every 5 seconds to check for stale data
    }

    /**
     * Stores an instance of weather data in JSON, as well as its timestamp.
     */
    private static class WeatherDataEntry {
        String jsonData;
        long lastUpdatedTimestamp;

        WeatherDataEntry(String jsonData) {
            this.jsonData = jsonData;
            this.lastUpdatedTimestamp = System.currentTimeMillis();
        }
    }
}