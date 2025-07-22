import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * A generic HTTP proxy server that forwards requests to backend servers.
 * Uses {@link HttpServerWrapper} for incoming requests and {@link HttpClientWrapper}
 * for outbound requests. Supports both streaming and non-streaming responses.
 */
public class HttpProxy implements HttpHandler {

    /**
     * Interface for determining how to route incoming requests to backend servers.
     */
    public interface RequestRouter {
        /**
         * Determines the target URI and API key for a given request.
         * 
         * @param method HTTP method
         * @param path Request path
         * @param body Request body
         * @param headers Request headers (lowercase keys)
         * @return ProxyTarget with URI and API key, or null if request should be rejected
         */
        ProxyTarget route(String method, String path, String body, Map<String, String> headers);
    }

    /**
     * Target information for proxying a request.
     */
    public record ProxyTarget(URI uri, String apiKey, boolean isStreaming) {}

    private final HttpServerWrapper serverWrapper;
    private final HttpClientWrapper clientWrapper;
    private final RequestRouter router;

    /**
     * Creates a new HTTP proxy server.
     *
     * @param port The port to bind the server to
     * @param backlog The maximum number of incoming connections to queue
     * @param connectionTimeout Timeout for establishing backend connections
     * @param requestTimeout Timeout for complete request/response cycle
     * @param router The router to determine backend targets
     */
    public HttpProxy(int port, Duration connectionTimeout, Duration requestTimeout, RequestRouter router) {
        this.serverWrapper = new HttpServerWrapper(port);
        this.clientWrapper = new HttpClientWrapper(connectionTimeout, requestTimeout);
        this.router = router;
    }

    /**
     * Starts the proxy server.
     *
     * @throws IOException If the server fails to start
     */
    public void start() throws IOException {
        serverWrapper.start(this);
    }

    /**
     * Stops the proxy server.
     *
     * @throws IOException If the server fails to stop
     */
    public void stop() throws IOException {
        serverWrapper.stop();
    }

    /**
     * Handles incoming HTTP requests by routing them to appropriate backends.
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = HttpServerWrapper.readRequestBody(exchange);
            Map<String, String> headers = convertHeaders(exchange);

            ProxyTarget target = router.route(method, path, body, headers);
            if (target == null) {
                HttpServerWrapper.sendResponse(exchange, 400, "application/json", 
                    "{\"error\":{\"message\":\"Bad Request\",\"type\":\"invalid_request_error\"}}");
                return;
            }

            proxyRequest(exchange, target, body);

        } catch (Exception e) {
            HttpServerWrapper.sendResponse(exchange, 500, "application/json",
                "{\"error\":{\"message\":\"Internal Server Error\",\"type\":\"server_error\"}}");
        }
    }

    /**
     * Proxies the request to the target backend server.
     */
    private void proxyRequest(HttpExchange exchange, ProxyTarget target, String body) throws IOException {
        HttpResponse<InputStream> response = clientWrapper.sendRequest(target.uri(), target.apiKey(), body, target.isStreaming());
        
        if (target.isStreaming()) {
            HttpServerWrapper.sendStreamingResponse(exchange, response.body());
        } else {
            // Read the entire response body for non-streaming
            try (InputStream responseStream = response.body()) {
                String responseBody = new String(responseStream.readAllBytes());
                HttpServerWrapper.sendResponse(exchange, response.statusCode(), "application/json", responseBody);
            }
        }
    }

    /**
     * Converts HttpExchange headers to a simple map with lowercase keys.
     */
    private Map<String, String> convertHeaders(HttpExchange exchange) {
        return exchange.getRequestHeaders().entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(java.util.stream.Collectors.toMap(
                entry -> entry.getKey().toLowerCase(),
                entry -> entry.getValue().get(0)
            ));
    }
}