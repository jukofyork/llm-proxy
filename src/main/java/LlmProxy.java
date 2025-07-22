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

    public LlmProxy() {
        super(
            Constants.PROXY_PORT,
            Constants.CONNECTION_TIMEOUT,
            Constants.REQUEST_TIMEOUT,
            new LlmProxyRouter()
        );
    }

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
        Logger.info("Available models: " + ModelsManager.getModelBackends().keySet());
    }

    /**
     * Override to handle special endpoints like /models.
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        
        // Handle models endpoint directly
        if (path.endsWith(Constants.MODELS_ENDPOINT)) {
            String responseBody = ModelsManager.generateModelsResponse();
            HttpServerWrapper.sendResponse(exchange, 200, "application/json", responseBody);
            return;
        }
        
        // Delegate to parent for normal proxying
        super.handle(exchange);
    }

    /**
     * Router implementation that handles model-based request routing.
     */
    private static class LlmProxyRouter implements RequestRouter {
        
        @Override
        public ProxyTarget route(String method, String path, String body, Map<String, String> headers) {
            // Find model configuration
            ModelsManager.ModelConfig modelConfig = ModelsManager.findModelConfigForRequest(method, path, body, headers);
            if (modelConfig == null) {
                return null; // Will result in 400 error
            }
            
            Logger.info("Routing to " + modelConfig.displayName() + " (" + modelConfig.endpoint() + ")");
            
            // Log request if debug enabled (with pretty printing like the old version)
            if (Constants.DEBUG_REQUEST && !body.isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode jsonNode = mapper.readTree(body);
                    String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
                    Logger.info("Sending JSON:\n" + prettyJson + "\n---");
                } catch (Exception e) {
                    Logger.info("Sending JSON:\n" + body + "\n---");
                }
            }
            
            // Build target URI
            String finalPath = ModelsManager.buildFinalRequestPath(path, modelConfig.endpoint());
            String fullUrl = modelConfig.endpoint() + finalPath;
            URI targetUri = URI.create(fullUrl);
            
            // Determine if streaming
            boolean isStreaming = ModelsManager.determineIfStreamingRequest(body);
            
            return new ProxyTarget(targetUri, modelConfig.apiKey(), isStreaming);
        }
    }
}