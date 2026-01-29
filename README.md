# Weather-Aggregation-Server
A Java implementation of a client/server system that aggregates and distributes JSON format weather data using a RESTful API.

## System Elements
**Aggregation server:**
- Handles HTTP PUT and GET requests from the content server and client.
- The server receives and sends weather data, and stores it in a persistent state in a file.
- Maintains Lamport clocks for synchronization and removes old data.
- Client connections are handled concurrently using a thread pool.

**Content server:**
  - Reads local weather data from a file, converts it to JSON format, and sends it to the aggregation server using an HTTP PUT request.
  - Maintains a Lamport clock for synchronization with the server.

**GET client:**
  - Sends HTTP GET requests to the aggregation server to retrieve stored weather data.
  - Connects to the server, requests data, and prints the formatted response.
  - Handles Lamport clock synchronization from server responses.
