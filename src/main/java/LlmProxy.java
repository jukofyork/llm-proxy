import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

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
}