# Weather-Aggregation-Server
A Java implementation of a client/server system that aggregates and distributes JSON format weather data using a RESTful API.

### Project purpose:
The purpose of this project is to implement an aggregation server that stores weather data in JSON format, handling PUT
requests that update the current weather data, handling GET requests of the data, and removing stale weather data.
The system supports multiple servers, multiple clients, and uses Lamport clocks for consistency. 

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

## Instructions
### Setup steps:
1. Open a terminal in the root project directory 
2. Run `make` to compile the code.

### To manually run the code:
1. Open three terminals and run `cd src` in each to change to src directory.
2. In the first terminal run `java AggregationServer` to start the Aggregation Server on port 4567. Optionally the port 
can be specified in an argument, i.e., `java AggregationServer <port_number>`.
3. In the second terminal run `java ContentServer localhost:4567 weather_data.txt` to run the Content Server, which 
sends a PUT request to the Aggregation Server containing the weather data in weather_data.txt (weather_data.txt is the 
example from the assignment description). 
For other domain, port number, or file path: `java ContentServer <server_name>:<port_number> <file_path>`.
4. In the third terminal run `java GETClient localhost:4567` to run the GET Client, which sends a GET request to the 
Aggregation Server and prints the response. Optionally a second argument can be passed to specify the station to send the 
GET request for, e.g., for station IDS60901: `java GETClient localhost:4567 IDS60901`. For other domain, port number, or 
station: `java GETClient <domain_name>:<port_number> <station>`.

### To run the automated tests:
1. Ensure that Aggregation Server is not running and port 4567 is free.
2. Open a terminal in the src directory.
3. Run `java AutomatedTests`.
