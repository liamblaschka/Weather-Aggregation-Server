import java.net.*;
import java.io.*;
import java.util.Scanner;

/**
 * Client program that sends HTTP GET requests to the aggregation server to retrieve stored weather data.
 * Connects to the server, requests data, and prints the formatted response.
 * Handles Lamport clock synchronization from server responses.
 */
public class GETClient {
    private static long lamportClock = 0;

    /**
     * Runs the GETClient.
     * The client connects to the aggregation server, sends an HTTP GET request,
     * and prints the weather data received.
     * The Lamport clock is updated before sending the request and after receiving the response.
     *
     * @param args command line arguments:
     *             args[0] - server URL
     *             args[1] - station ID (optional)
     */
    public static void main(String[] args) {
        // Validate command line arguments
        if (args.length < 1) {
            System.err.println("Usage: java GETClient <server_url> <station_id>");
            System.exit(1);
        }

        String serverUrl = args[0];
        String stationId = (args.length > 1) ? args[1] : null;

        String host;
        int port;
        String path = "/weather.json";

        try {
            if (serverUrl.startsWith("http://")) {
                serverUrl = serverUrl.substring(7); // Remove "http://"
            }

            // Split host:port
            String[] parts = serverUrl.split(":");
            host = parts[0];
            port = (parts.length > 1) ? Integer.parseInt(parts[1]) : 4567; // Default port

        } catch (Exception e) {
            System.err.println("Invalid server URL format: " + serverUrl);
            System.err.println("Use format: hostname:port or http://hostname:port");
            return;
        }

        // If stationId specified, add it to the path
        if (stationId != null) {
            path = "/weather.json?id=" + stationId;
        }

        int maxRetries = 3;
        int attempt = 0;
        while (attempt < maxRetries + 1) {
            try (
                    Socket socket = new Socket(host, port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            ) {
                lamportClock++; // Update Lamport clock before sending

                // Build and send HTTP GET request
                String request = buildGetRequest(host, port, path);
                out.println(request);
                out.flush();

                System.out.println("Sent request to server:");
                System.out.println(request);

                // Read and parse the server response
                String response = readResponse(in);
                processResponse(response);

                return;

            } catch (UnknownHostException e) {
                System.err.println("Unknown host " + host);
                System.err.println("Check that the hostname is correct.");
            } catch (IOException e) {
                System.err.println("Could not connect to " + host + " on port " + port);
                System.err.println("Ensure the server is running.");
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
            }

            // Retry GET request
            if (attempt < maxRetries) {
                try {
                    int delaySeconds = (attempt + 1) * 2; // 2, 4, 6 seconds...
                    System.out.println("Waiting " + delaySeconds + " seconds before retry...");
                    Thread.sleep(delaySeconds * 1000);
                    System.out.println("Retrying...\n");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            attempt++;
        }
    }

    /**
     * Builds HTTP GET request for a specified host, port, and path.
     *
     * @param host the hostname of the server to receive the request
     * @param port the port number of the server
     * @param path the path of the requested resource
     * @return the formatted GET request string
     */
    private static String buildGetRequest(String host, int port, String path) {
        StringBuilder request = new StringBuilder();

        request.append("GET ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host).append(":").append(port).append("\r\n");
        request.append("User-Agent: GETClient/1.0\r\n");
        request.append("Lamport-Clock: ").append(lamportClock).append("\r\n");
        request.append("Connection: close\r\n");
        request.append("\r\n");

        return request.toString();
    }

    /**
     * Reads HTTP response from the BufferedReader.
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
     * Processes the HTTP response received from the server.
     * Splits the response into headers and body, updates the Lamport clock,
     * and prints the weather data.
     *
     * @param response the HTTP response as a string
     */
    private static void processResponse(String response) {
        System.out.println("Server response:");

        // Split response into headers and body
        String[] parts = response.split("\n\n", 2);
        String headers = parts[0];
        String body = (parts.length > 1) ? parts[1] : "";

        processHeaders(headers);

        // Process the JSON body
        if (body.trim().isEmpty() || body.equals("{}")) {
            System.out.println("No weather data available.");
            return;
        }

        try {
            String cleanBody = body.trim();
            if (cleanBody.startsWith("[") && cleanBody.endsWith("]")) {
                cleanBody = cleanBody.substring(1, cleanBody.length() - 1);
            }

            // If multiple station entries, split entries
            String[] stations = cleanBody.split("},\\s*\\{");

            for (int i = 0; i < stations.length; i++) {
                String stationJson = stations[i];
                // Add the curly braces back if entries were split
                if (!stationJson.startsWith("{")) {
                    stationJson = "{" + stationJson;
                }
                if (!stationJson.endsWith("}")) {
                    stationJson = stationJson + "}";
                }

                System.out.println("\nWeather Station Data:");
                prettyPrintJson(stationJson);
                System.out.println();
            }

        } catch (Exception e) {
            System.out.println("Error parsing response: " + e.getMessage());
            System.out.println("Raw response body: " + body);
        }
    }

    /**
     * Processes the HTTP response headers from the server.
     * The clients Lamport clock is incremented, maintaining logical time consistency
     * after comparing with the server's clock.
     *
     * @param headers the HTTP headers as a string
     */
    private static void processHeaders(String headers) {
        String[] headerLines = headers.split("\n");

        for (String line : headerLines) {
            if (line.startsWith("HTTP/")) {
                System.out.println("Status: " + line);
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
    }

    /**
     * Prints a JSON string stripped of its JSON formatting.
     *
     * @param json the JSON string to print
     */
    private static void prettyPrintJson(String json) {
        try {
            // Strip of JSON formatting
            json = json.replaceAll(",", "");
            json = json.replaceAll("\\{", "");
            json = json.replaceAll("\"", "");
            json = json.replaceAll("}", "");

            String[] lines = json.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                System.out.println(line);
            }
        } catch (Exception e) {
            System.out.println("Error while printing JSON: " + json);
        }
    }
}