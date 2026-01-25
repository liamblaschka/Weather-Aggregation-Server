import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tests the AggregationServer, ContentServer, and GETClient.
 * Tests include:
 * - Server startup and availability
 * - ContentServer data submission
 * - GETClient data retrieval
 * - Multiple weather stations
 * - Persistence of weather data
 * - Removal of old weather data
 */
public class AutomatedTests {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 4567;
    private static final String TEST_DATA_FILE = "test_data.txt";

    private static Process serverProcess = null;

    private static int testCount = 0;
    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) {
        System.out.println("Server: " + SERVER_HOST + ":" + SERVER_PORT + "\n");

        try {
            createTestDataFile();

            // Run all tests
            testBasicFunctionality();
            testFailureModes();
            testConcurrency();
            testEdgeCases();
            testPersistence();
            testContentServerRetry();
            testGETClientRetry();

            printTestSummary();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stopServer();
        }
    }

    private static void createTestDataFile() throws IOException {
        System.out.println("Creating test data file: " + TEST_DATA_FILE);
        String testData = "id:TEST001\nname:Test Station\nstate:TS\nair_temp:25.5\nrel_hum:45";
        try (PrintWriter writer = new PrintWriter(new FileWriter(TEST_DATA_FILE))) {
            writer.println(testData);
        }
        System.out.println("Test data file created successfully\n");
    }

    private static void runTest(String testName, Runnable test) {
        testCount++;
        System.out.println("TEST " + testCount + ": " + testName);

        long startTime = System.currentTimeMillis();

        try {
            test.run();
            long duration = System.currentTimeMillis() - startTime;
            passCount++;
            System.out.println("PASS: " + testName + " (" + duration + "ms)");
            System.out.println();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            failCount++;
            System.out.println("FAIL: " + testName + " (" + duration + "ms)\n\n");
            System.out.println("   Error: " + e.getMessage());
            System.out.println();
        }
    }

    /**
     * Tests the basic functionality
     */
    private static void testBasicFunctionality() {
        runTest("Server Startup and Availability", () -> {
            System.out.println("    - Starting AggregationServer...");
            startServer();
            try {
                Thread.sleep(1000); // Wait for server to start
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            System.out.println("    - Verifying server is running...");
            assertTrue(isServerRunning(), "Server should be running and accepting connections");
            System.out.println("Server is running on port " + SERVER_PORT);
        });

        runTest("Content Server Data Submission", () -> {
            System.out.println("    - Running ContentServer with test data...");
            int result = runContentServer().exitCode;

            System.out.println("    - Verifying ContentServer exit code...");
            assertTrue(result == 0, "ContentServer should exit successfully (code 0), got: " + result);
            System.out.println("ContentServer completed successfully");
        });

        runTest("GET Client Data Retrieval", () -> {
            System.out.println("    - Running GETClient to retrieve data...");
            String output = runGetClient();

            System.out.println("    - Verifying retrieved data contains expected content...");
            assertTrue(output.contains("TEST001"), "Should retrieve test station data");
            assertTrue(output.contains("25.5"), "Should contain temperature data");
            assertTrue(output.contains("Test Station"), "Should contain station name");
            System.out.println("GETClient successfully retrieved and displayed data");
        });

        runTest("Multiple Weather Stations Support", () -> {
            System.out.println("    - Creating second test station data...");
            String data2 = "id:TEST002\nname:Station 2\nair_temp:30.0\nrel_hum:50";
            try {
                createTempFile("test2.txt", data2);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            System.out.println("    - Submitting second station data...");
            int result = runContentServer("test2.txt").exitCode;
            assertTrue(result == 0, "Second station should be added successfully");

            System.out.println("    - Verifying both stations are available...");
            String output = runGetClient();
            assertTrue(output.contains("TEST001") && output.contains("TEST002"),
                    "Should contain data from both stations");
            System.out.println("System supports multiple weather stations");
        });
    }

    /**
     * Tests that errors are handled gracefully
     */
    private static void testFailureModes() {
        runTest("Server Downtime Handling", () -> {
            System.out.println("    - Stopping server...");
            stopServer();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            System.out.println("    - Testing client behavior when server is down...");
            String output = runGetClient();

            assertTrue(output.contains("Unknown host") || output.contains("Could not connect"),
                    "Client should handle server downtime gracefully");
            System.out.println("Clients handle server unavailability properly");

            System.out.println("    - Restarting server for subsequent tests...");
            startServer();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        runTest("Nonexistent Data File Handling", () -> {
            System.out.println("    - Testing ContentServer with missing file...");
            int result = runContentServer("nonexistent_file.txt").exitCode;
            assertTrue(result != 0, "ContentServer should fail gracefully with missing file");
            System.out.println("ContentServer handles missing files appropriately");
        });
    }

    /**
     * Tests concurrent PUT and GET requests using the ContentServer and GETClient
     */
    private static void testConcurrency() throws Exception {
        runTest("Concurrent Data Submissions", () -> {
            System.out.println("    - Preparing concurrent test data...");
            ExecutorService executor = Executors.newFixedThreadPool(3);
            List<Future<Integer>> results = new ArrayList<>();

            for (int i = 0; i < 3; i++) {
                final int stationNum = i + 10;
                results.add(executor.submit(() -> {
                    String data = "id:CONC" + stationNum + "\nname:Concurrent Station " + stationNum + "\nair_temp:" + (20 + stationNum);
                    createTempFile("concurrent_" + stationNum + ".txt", data);
                    return runContentServer("concurrent_" + stationNum + ".txt").exitCode;
                }));
            }

            System.out.println("    - Executing 3 concurrent PUT requests...");
            for (int i = 0; i < results.size(); i++) {
                Future<Integer> result = results.get(i);
                try {
                    assertTrue(result.get() == 0, "Concurrent PUT " + (i+1) + " should succeed");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            System.out.println("    - Verifying all concurrent data was stored...");
            String output = runGetClient();
            for (int i = 10; i < 13; i++) {
                assertTrue(output.contains("CONC" + i), "Data from concurrent PUT " + i + " should be present");
            }
            System.out.println("System handles concurrent PUT requests correctly");
        });

        runTest("Concurrent Data Retrievals", () -> {
            System.out.println("    - Executing multiple concurrent GET requests...");
            ExecutorService executor = Executors.newFixedThreadPool(5);
            List<Future<String>> results = new ArrayList<>();

            for (int i = 0; i < 5; i++) {
                results.add(executor.submit(() -> runGetClient()));
            }

            System.out.println("    - Verifying all GET requests complete successfully...");
            for (int i = 0; i < results.size(); i++) {
                String output = null;
                try {
                    output = results.get(i).get();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
                assertTrue(output.contains("TEST001"), "Concurrent GET " + (i+1) + " should return data");
            }
            System.out.println("System handles concurrent GET requests correctly");
        });
    }

    /**
     * Tests edge cases
     */
    private static void testEdgeCases() {
        runTest("Empty Data File Handling", () -> {
            System.out.println("    - Testing with empty data file...");
            try {
                createTempFile("empty.txt", "");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            int result = runContentServer("empty.txt").exitCode;
            assertTrue(result != 0, "Should reject empty data file");
            System.out.println("System rejects empty data files appropriately");
        });

        runTest("Special Character Handling", () -> {
            System.out.println("    - Testing data with special characters...");
            String specialData = "id:SPECIAL\nname:Station with \\\"quotes\\\" & % symbols\nvalue:100°C";
            try {
                createTempFile("special.txt", specialData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            int result = runContentServer("special.txt").exitCode;
            assertTrue(result == 0, "Should handle special characters in data");
            System.out.println("System handles special characters correctly");
        });
    }

    /**
     * Tests the persistence of the weather data between server restarts
     */
    private static void testPersistence() {
        runTest("Data Persistence Across Server Restarts", () -> {
            System.out.println("    - Submitting test data...");
            int putResult = runContentServer().exitCode;
            assertTrue(putResult == 0, "Initial data submission should succeed");

            System.out.println("    - Restarting server...");
            stopServer();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            startServer();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            System.out.println("    - Verifying data persists after restart...");
            String output = runGetClient();
            assertTrue(output.contains("TEST001"), "Data should persist after server restart");
            System.out.println("Data persistence across server restarts works correctly");
        });

        runTest("Crash Recovery", () -> {
            System.out.println("    - Testing server recovery from potential crashes...");
            // Test crash recovery by restarting server multiple times
            for (int i = 0; i < 3; i++) {
                stopServer();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                startServer();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                assertTrue(isServerRunning(), "Server should recover after restart " + (i+1));
            }
            System.out.println("Server demonstrates robust crash recovery capabilities");
        });
    }

    /**
     * Test the retry functionality of the ContentServer as it attempts to send a PUT request while the
     * aggregation server is down
     */
    private static void testContentServerRetry() {
        runTest("ContentServer Retry Behavior", () -> {
            System.out.println("    - Testing ContentServer retrying when server is down...");

            // Stop aggregation server
            stopServer();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            String testData = "id:RETRY_TEST\nname:Retry Test Station\nair_temp:25.0";
            try {
                createTempFile("retry_test.txt", testData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            long startTime = System.currentTimeMillis();

            // Run ContentServer, it should try to connect and fail with retries
            String output = runContentServer("retry_test.txt").output;
            long duration = System.currentTimeMillis() - startTime;

            System.out.println("Duration: " + duration + "ms");

            boolean showedRetryBehavior = output.contains("Retrying") && duration > 2000;

            // ContentServer should attempt to retry connection
            assertTrue(showedRetryBehavior, "ContentServer should retry connection to server");

            System.out.println("ContentServer demonstrates retry behavior when server is unavailable");

            startServer();
        });
    }

    /**
     * Test the retry functionality of the GETClient as it attempts to send a GET request while the
     * aggregation server is down
     */
    private static void testGETClientRetry() {
        runTest("GETClient Retry Behavior", () -> {
            System.out.println("    - Testing GETClient retrying when server is down...");

            // Stop aggregation server
            stopServer();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            long startTime = System.currentTimeMillis();

            // Run GETClient, it should try to connect and fail with retries
            String output = runGetClient();
            long duration = System.currentTimeMillis() - startTime;

            System.out.println("Duration: " + duration + "ms");

            // GETClient should attempt to retry connection
            boolean showedRetryBehavior = output.contains("Retrying") && duration > 2000;
            assertTrue(showedRetryBehavior, "GETClient should retry connection to server.");

            System.out.println("GETClient demonstrates retry behavior when server is unavailable");

            startServer();
        });
    }

    private static void startServer() {
        try {
            // Start server in a separate process
            serverProcess = Runtime.getRuntime().exec("java AggregationServer " + SERVER_PORT);
            Thread.sleep(2000);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start server: " + e.getMessage());
        }
    }

    private static void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroy();
            try {
                serverProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (serverProcess.isAlive()) {
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                serverProcess.destroyForcibly();
            }
            serverProcess = null;
        }
    }

    private static boolean isServerRunning() {
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static class ProcessResult {
        int exitCode;
        String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static ProcessResult runContentServer() {
        return runContentServer(TEST_DATA_FILE);
    }

    private static ProcessResult runContentServer(String dataFile) {
        try {
            Process process = Runtime.getRuntime().exec(
                    "java ContentServer " + SERVER_HOST + ":" + SERVER_PORT + " " + dataFile);

            // Read stdout and stderr
            BufferedReader stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            BufferedReader stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));

            StringBuilder output = new StringBuilder();
            String line;

            // Read stdout
            while ((line = stdoutReader.readLine()) != null) {
                output.append("[STDOUT] ").append(line).append("\n");
            }

            // Read stderr
            while ((line = stderrReader.readLine()) != null) {
                output.append("[STDERR] ").append(line).append("\n");
            }

            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, output.toString());
        } catch (Exception e) {
            return new ProcessResult(-1, "Error running ContentServer: " + e.getMessage());
        }
    }

    private static String runGetClient() {
        try {
            Process process = Runtime.getRuntime().exec(
                    "java GETClient " + SERVER_HOST + ":" + SERVER_PORT);

            // Read stdout and stderr
            BufferedReader stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            BufferedReader stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));

            StringBuilder output = new StringBuilder();
            String line;

            // Read stdout
            while ((line = stdoutReader.readLine()) != null) {
                output.append("[STDOUT] ").append(line).append("\n");
            }

            // Read stderr
            while ((line = stderrReader.readLine()) != null) {
                output.append("[STDERR] ").append(line).append("\n");
            }

            process.waitFor();
            return output.toString();

        } catch (Exception e) {
            return "Error running GETClient: " + e.getMessage();
        }
    }

    private static void createTempFile(String filename, String content) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println(content);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void printTestSummary() {
        System.out.println("\nTest Summary:");
        System.out.println("Total Tests Run: " + testCount);
        System.out.println("Tests Passed: " + passCount);
        System.out.println("Tests Failed: " + failCount);

        if (failCount == 0) {
            System.out.println("\nPASSED all tests.");
        } else {
            System.out.println("\nFAILED some tests. Check test outputs.");
        }
    }
}