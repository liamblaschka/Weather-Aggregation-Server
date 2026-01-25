import java.net.*;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Content server that reads local weather data from a file, converts it into JSON format,
 * and sends it to the aggregation server using an HTTP PUT request.
 * Maintains a Lamport clock for synchronization with the server.
 */
public class ContentServer {
    // Lamport clock for this content server
    private static long lamportClock = 0;

    /**
     * Runs the content server.
     * Reads the file (specified in argument) containing the weather data, converts it to a JSON formatted string,
     * and sends the JSON data to the aggregation server as an HTTP PUT request.
     * The Lamport clock is updated before sending the request and after receiving the response.
     *
     * @param args command line arguments:
     *             args[0] - server URL
     *             args[1] - path to the weather data file
     */
    public static void main(String[] args) {
        // Validate arguments
        if (args.length < 2) {
            System.err.println("Usage: java ContentServer <server_url> <data_file>");
            System.exit(1);
        }

        String serverUrl = args[0];
        String dataFilePath = args[1];

        // Default host and port
        String host = "localhost";
        int port = 4567;

        try {
            if (serverUrl.startsWith("http://")) {
                serverUrl = serverUrl.substring(7);
            }

            // Split host:port
            String[] parts = serverUrl.split(":");
            host = parts[0];
            if (parts.length > 1) {
                port = Integer.parseInt(parts[1]);
            }

        } catch (Exception e) {
            System.err.println("Invalid server URL format: " + serverUrl);
            System.err.println("Use format: hostname:port or http://hostname:port");
            System.exit(1);
        }

        // Check if file exists and is readable
        File dataFile = new File(dataFilePath);
        if (!dataFile.exists() || !dataFile.isFile()) {
            System.err.println("Error: Data file does not exist: " + dataFilePath);
            System.exit(1);
        }
        if (!dataFile.canRead()) {
            System.err.println("Error: Cannot read data file: " + dataFilePath);
            System.exit(1);
        }

        // Read and parse the data file
        Map<String, String> weatherData = parseDataFile(dataFilePath);
        if (weatherData == null || weatherData.isEmpty()) {
            System.err.println("No valid data found in file: " + dataFilePath);
            System.exit(1);
        }

        // Convert the data to JSON format
        String jsonData = convertToJson(weatherData);
        if (jsonData == null) {
            System.err.println("Failed to create JSON data");
            System.exit(1);
        }

        System.out.println("Generated JSON data:");
        System.out.println(jsonData);
        System.out.println();

        // Send the PUT request to the aggregation server
        boolean success = sendPutRequest(host, port, jsonData);

        if (success) {
            System.out.println("Data successfully sent to aggregation server");
        } else {
            System.out.println("Failed to send data to aggregation server");
            System.exit(1);
        }
    }

    /**
     * Parses file containing weather data into a map.
     *
     * @param filePath the path of the file
     * @return a map containing the parsed data
     */
    private static Map<String, String> parseDataFile(String filePath) {
        Map<String, String> dataMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Split key:value pairs
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    dataMap.put(key, value);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading file: " + filePath);
            System.err.println("Error: " + e.getMessage());
            return null;
        }

        return dataMap;
    }

    /**
     * Converts map of weather data into a JSON formatted string.
     *
     * @param data the map containing the weather data
     * @return a JSON string of the data
     */
    private static String convertToJson(Map<String, String> data) {
        // Validate required fields
        if (!data.containsKey("id") || data.get("id").isEmpty()) {
            System.err.println("Error: Data file must contain an 'id' field");
            return null;
        }

        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{\n");

        int count = 0;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Remove quotes if numeric value
            if (isNumeric(value)) {
                jsonBuilder.append("    \"").append(key).append("\" : ").append(value);
            } else {
                jsonBuilder.append("    \"").append(key).append("\" : \"").append(value).append("\"");
            }

            if (++count < data.size()) {
                jsonBuilder.append(",");
            }
            jsonBuilder.append("\n");
        }

        jsonBuilder.append("}");
        return jsonBuilder.toString();
    }

    /**
     * Check if a string is numeric.
     *
     * @param str string to check
     * @return boolean of whether the string is a numeric value
     */
    private static boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Sends an HTTP PUT request to the aggregation server, with JSON string of weather data.
     *
     * @param host the hostname of the aggregation server
     * @param port the port number of the server
     * @param jsonData the JSON string of weather data to send
     * @return boolean of whether PUT request was successful
     */
    private static boolean sendPutRequest(String host, int port, String jsonData) {
        int maxRetries = 3;
        int attempt = 0;
        while (attempt < maxRetries + 1) {
            try (
                    Socket socket = new Socket(host, port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            ) {
                lamportClock++; // Update Lamport clock before sending

                // Build and send HTTP PUT request
                String request = buildPutRequest(host, port, jsonData);
                out.println(request);
                out.flush();

                System.out.println("Sending PUT request to " + host + ":" + port);
                System.out.println("Request size: " + request.length() + " characters");

                // Read and process the server response
                String response = readResponse(in);
                return processResponse(response);

            } catch (UnknownHostException e) {
                System.err.println("Unknown host " + host);
                System.err.println("Check that the hostname is correct.");
            } catch (IOException e) {
                System.err.println("Could not connect to " + host + " on port " + port);
                System.err.println("Ensure the server is running.");
            } catch (Exception e) {
                System.err.println("Unexpected error: " + e.getMessage());
                e.printStackTrace();
            }

            // Retry PUT request
            if (attempt < maxRetries) {
                try {
                    int delaySeconds = (attempt + 1) * 2; // 2, 4, 6 seconds...
                    System.out.println("Waiting " + delaySeconds + " seconds before retry...");
                    Thread.sleep(delaySeconds * 1000);
                    System.out.println("Retrying...\n");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            attempt++;
        }
        return false;
    }

    /**
     * Builds the HTTP PUT request that will be sent to the aggregation server.
     *
     * @param host the hostname of the aggregation server
     * @param port the port number of the server
     * @param jsonData the JSON string of weather data to send
     * @return the HTTP PUT request as a string
     */
    private static String buildPutRequest(String host, int port, String jsonData) {
        StringBuilder request = new StringBuilder();

        request.append("PUT /weather.json HTTP/1.1\r\n");
        request.append("Host: ").append(host).append(":").append(port).append("\r\n");
        request.append("User-Agent: ContentServer/1.0\r\n");
        request.append("Content-Type: application/json\r\n");
        request.append("Lamport-Clock: ").append(lamportClock).append("\r\n");
        request.append("Content-Length: ").append(jsonData.length()).append("\r\n");
        request.append("Connection: close\r\n");
        request.append("\r\n");
        request.append(jsonData); // JSON body

        return request.toString();
    }

    /**
     * Reads the HTTP response from the aggregation server until the connection is closed.
     *
     * @param in the BufferedReader to read the response from
     * @return the response as a string
     * @throws IOException if an I/O error occurs
     */
    private static String readResponse(BufferedReader in) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;

        // Read response until connection is closed
        while ((line = in.readLine()) != null) {
            response.append(line).append("\n");
        }

        return response.toString();
    }

    /**
     * Processes the HTTP response received from the aggregation server.
     * Splits the response into headers and body, updates the Lamport clock, and prints the status line.
     *
     * @param response the HTTP response as a string
     * @return boolean of whether the response was successful
     */
    private static boolean processResponse(String response) {
        System.out.println("\nServer response:");

        // Split response into headers and body
        String[] parts = response.split("\n\n", 2);
        String headers = parts[0];
        String body = (parts.length > 1) ? parts[1] : "";

        // Process headers
        boolean success = false;
        String statusLine = "";

        String[] headerLines = headers.split("\n");
        for (String line : headerLines) {
            if (line.startsWith("HTTP/")) {
                statusLine = line;
                success = line.contains("201") || line.contains("200"); // Created or OK
            } else if (line.toLowerCase().startsWith("lamport-clock:")) {
                String[] clockParts = line.split(":", 2);
                if (clockParts.length > 1) {
                    long serverClock = Long.parseLong(clockParts[1].trim());
                    // Update client's Lamport clock
                    lamportClock = Math.max(lamportClock, serverClock) + 1;
                    System.out.println("Server Lamport Clock: " + serverClock);
                    System.out.println("Updated Client Clock: " + lamportClock);
                }
            }
        }

        System.out.println("Status: " + statusLine);
        if (!body.trim().isEmpty()) {
            System.out.println("Response body: " + body.trim());
        }

        if (success) {
            System.out.println("PUT request successful");
        } else {
            System.out.println("PUT request failed");
        }

        return success;
    }
}