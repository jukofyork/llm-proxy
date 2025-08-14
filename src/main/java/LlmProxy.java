import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A clean proxy server for OpenAI-compatible APIs.
 * Routes requests to multiple backend servers based on model selection.
 * Supports both streaming and non-streaming responses with TOML configuration.
 */
public class LlmProxy extends HttpProxy {

    /**
     * Constructs a new LlmProxy configured with predefined constants and router.
     */
    public LlmProxy() {
        super(
            Constants.PROXY_PORT,
            Constants.CONNECTION_TIMEOUT,
            Constants.REQUEST_TIMEOUT,
            new ModelRequestRouter()
        );
    }

    /**
     * Main entry point for starting the proxy server.
     *
     * @param args command-line arguments
     * @throws Exception if proxy initialization fails
     */
    public static void main(String[] args) throws Exception {
        // Initialize configuration and models
        if (!ConfigurationManager.initialize()) {
            Logger.error("Failed to initialize configuration");
            System.exit(1);
        }
        
        if (!ModelsManager.initialize(ConfigurationManager.getServerConfigs())) {
            Logger.error("Failed to initialize models");
            System.exit(1);
        }
        
        // Create and start the proxy
        LlmProxy proxy = new LlmProxy();
        proxy.start();
        
        Logger.info("Proxy server running on port " + Constants.PROXY_PORT);
    }

    /**
     * Handles incoming HTTP requests. If the path matches the models endpoint,
     * generates a models response; otherwise delegates the work to parent for
     * normal proxying.
     *
     * @param exchange the HttpExchange representing the current request/response
     * @throws IOException if an I/O error occurs during handling
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        
        // Handle models endpoint directly
        if (requestPath.endsWith(Constants.MODELS_ENDPOINT)) {
            String modelsPayload = ModelsManager.generateModelsResponse();
            HttpServerWrapper.sendResponse(exchange, 200, "application/json", modelsPayload);
            return;
        }
        
        // Delegate to parent for normal proxying
        super.handle(exchange);
    }

    /**
     * Router implementation that determines which backend server to use based on
     * model information contained in the request.
     */
    private static class ModelRequestRouter implements RequestRouter {

        /**
         * Routes an incoming request to the appropriate backend based on
         * extracted model configuration.
         *
         * @param method  the HTTP method
         * @param requestPath the request path
         * @param requestBody the request body
         * @param headers a map of request headers
         * @return a ProxyTarget containing routing details, or null if invalid
         */
        @Override
        public ProxyTarget route(String method, String requestPath, String requestBody, Map<String, String> headers) {
            // Find model configuration
            ModelsManager.ModelConfig targetModelConfig =
                ModelsManager.findModelConfigForRequest(method, requestPath, requestBody, headers);
            if (targetModelConfig == null) {
                return null; // Will result in 400 error
            }
            
            Logger.info("Routing to " + targetModelConfig.displayName() + " (" + targetModelConfig.endpoint() + ")");
            
            // Log request if debug enabled
            logRequestPayloadIfDebug(requestBody);
            
            // Build target URI
            String resolvedPath = ModelsManager.buildFinalRequestPath(requestPath, targetModelConfig.endpoint());
            String resolvedUrl = targetModelConfig.endpoint() + resolvedPath;
            URI targetUri = URI.create(resolvedUrl);
            
            // Determine if streaming
            boolean isStreaming = ModelsManager.determineIfStreamingRequest(requestBody);
            
            return new ProxyTarget(targetUri, targetModelConfig.apiKey(), isStreaming);
        }

        /**
         * Logs the request body as pretty-printed JSON if debug mode is enabled.
         *
         * @param requestBody the raw JSON request body
         */
        private void logRequestPayloadIfDebug(String requestBody) {
            if (Constants.DEBUG_REQUEST && !requestBody.isEmpty()) {
                try {
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode = objectMapper.readTree(requestBody);
                    String prettyJson = objectMapper.writerWithDefaultPrettyPrinter()
                                                    .writeValueAsString(jsonNode);
                    Logger.info("Sending JSON:\n" + prettyJson + "\n---");
                } catch (Exception e) {
                    Logger.info("Sending JSON:\n" + requestBody + "\n---");
                }
            }
        }
    }
}