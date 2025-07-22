import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A wrapper around {@link HttpClient} that provides simplified HTTP communication
 * with a specific API endpoint. Handles authentication, request building, and
 * connection validation.
 * 
 * @see java.net.http.HttpClient
 */
public class HttpClientWrapper {

    private final Duration requestTimeout;
    private final HttpClient httpClient;

    /**
     * Creates a new HTTP client wrapper and validates the connection.
     *
     * @param connectionTimeout Timeout for establishing connections
     * @param requestTimeout Timeout for complete request/response cycle
     */
    public HttpClientWrapper(Duration connectionTimeout, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectionTimeout).build();
    }
    
    /**
     * Sends a synchronous HTTP request with the provided body.
     *
     * @param apiUri The API URI for the request
     * @param apiKey The API key for authentication
     * @param body The JSON request body (may be null for GET requests)
     * @param isStreaming Whether this is a streaming request
     * @return Response with input stream for reading the response body
     * @throws IOException If the request fails or returns a non-200 status code
     */
    public HttpResponse<InputStream> sendRequest(URI apiUri, String apiKey, String body, boolean isStreaming) throws IOException {
        HttpResponse<InputStream> response;
        HttpRequest request = buildRequest(apiUri, apiKey, body, isStreaming);
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IOException("Failed to send request", e);
        }
        return response;
    }

    /**
     * Sends an asynchronous HTTP request without waiting for the response.
     *
     * @param apiUri The API URI for the request
     * @param apiKey The API key for authentication
     * @param body The JSON request body
     * @throws IOException If request building fails
     */
    public void sendRequestAsync(URI apiUri, String apiKey, String body) throws IOException {
        HttpRequest request = buildRequest(apiUri, apiKey, body, false);
        try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IOException("Failed to send async request", e);
        }
    }
    
    /**
     * Builds an HTTP request with standard headers and optional body.
     * Uses HTTP/1.1 and includes Authorization, Accept, and Content-Type headers.
     *
     * @param apiUri The API URI for the request
     * @param apiKey The API key for authentication
     * @param body Request body (null or empty for GET, non-empty for POST)
     * @param isStreaming Whether this is a streaming request
     * @return Built HTTP request
     * @throws IOException If request building fails
     */
    private HttpRequest buildRequest(URI apiUri, String apiKey, String body, boolean isStreaming) throws IOException {
        HttpRequest request;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            		.uri(apiUri)
            		.timeout(requestTimeout)
                    .version(HttpClient.Version.HTTP_1_1);
            
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            
            if (isStreaming) {
                requestBuilder.header("Accept", "text/event-stream");
            } else {
                requestBuilder.header("Accept", "application/json");
            }
            
            if (body == null || body.isEmpty()) {
                requestBuilder.GET();
            } else {
                requestBuilder.header("Content-Type", "application/json")
                             .POST(HttpRequest.BodyPublishers.ofString(body));
            }
            request = requestBuilder.build();
        } catch (Exception e) {
            throw new IOException("Could not build the request", e);
        }
        return request;
    }
    
}