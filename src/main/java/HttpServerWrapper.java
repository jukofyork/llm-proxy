import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * A wrapper around {@link HttpServer} that provides simplified HTTP server management
 * for handling incoming requests. Manages server lifecycle, request/response handling,
 * and connection management.
 * 
 * @see com.sun.net.httpserver.HttpServer
 */
public class HttpServerWrapper {

    private HttpServer httpServer;
    private ExecutorService executor;
    private final int port;

    /**
     * Creates a new HTTP server wrapper.
     *
     * @param port The port to bind the server to
     */
    public HttpServerWrapper(int port) {
        this.port = port;
    }

    /**
     * Starts the HTTP server and registers the provided handler for all requests.
     *
     * @param handler The handler to process all incoming requests
     * @throws IOException If the server fails to start
     */
    public void start(HttpHandler handler) throws IOException {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0); // 0 = Use system default backlog
            httpServer.createContext("/", handler);
            
            // Create a thread pool for handling concurrent requests
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("HttpServer-Worker");
                return t;
            });
            httpServer.setExecutor(executor);
            
            httpServer.start();
        } catch (Exception e) {
            throw new IOException("Failed to start server on port " + port, e);
        }
    }

    /**
     * Stops the HTTP server gracefully.
     *
     * @throws IOException If the server fails to stop
     */
    public void stop() throws IOException {
        if (httpServer != null) {
            try {
                httpServer.stop(0); // Do not wait until exchanges have finished
                httpServer = null;
            } catch (Exception e) {
                throw new IOException("Failed to stop server", e);
            }
        }
        
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /**
     * Reads the complete request body from an HTTP exchange.
     *
     * @param exchange The HTTP exchange
     * @return The request body as a string
     * @throws IOException If reading the body fails
     */
    public static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Failed to read request body", e);
        }
    }

    /**
     * Sends a response with the specified status code, content type, and body.
     *
     * @param exchange The HTTP exchange
     * @param statusCode The HTTP status code
     * @param contentType The content type header value
     * @param responseBody The response body
     * @throws IOException If sending the response fails
     */
    public static void sendResponse(HttpExchange exchange, int statusCode, String contentType, String responseBody) throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(responseBytes);
            }
        } catch (Exception e) {
            throw new IOException("Failed to send response", e);
        }
    }

    /**
     * Sends a streaming response with the specified input stream.
     *
     * @param exchange The HTTP exchange
     * @param responseStream The input stream to copy to the response
     * @throws IOException If sending the streaming response fails
     */
    public static void sendStreamingResponse(HttpExchange exchange, InputStream responseStream) throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0); // 0 means chunked encoding
            
            try (OutputStream output = exchange.getResponseBody(); InputStream input = responseStream) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    output.flush();
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to send streaming response", e);
        }
    }
}