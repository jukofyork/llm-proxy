import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

/**
 * A clean proxy server for OpenAI-compatible APIs.
 * Routes requests to multiple backend servers based on model selection.
 * Supports both streaming and non-streaming responses with TOML configuration.
 */
public class LlmProxy extends HttpProxy {

    private final ProxySettings settings;

    public LlmProxy(RuntimeConfig runtime, ProxySettings settings) {
        super(
            settings.port,
            settings.connectionTimeout,
            settings.requestTimeout,
            new ModelRequestRouter(runtime, settings)
        );
        this.settings = settings;
    }

    public static void main(String[] args) throws Exception {
        // Parse command-line arguments
        ProxySettings settings;
        try {
            settings = ProxySettings.parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use -h or --help for usage information");
            System.exit(1);
            return;
        }

        // Initialize logger with settings
        Logger.initialize(settings);

        // Load configuration
        RuntimeConfig runtime = ConfigLoader.load(settings.configFile);
        if (runtime == null) {
            Logger.error("Failed to initialize configuration");
            System.exit(1);
        }

        // Initialize models manager with settings
        if (!ModelsManager.initialize(runtime, settings)) {
            Logger.error("Failed to initialize models");
            System.exit(1);
        }

        LlmProxy proxy = new LlmProxy(runtime, settings);
        proxy.start();

        Logger.info("Proxy server running on port " + settings.port);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();

        if (requestPath.endsWith("/models")) {
            String modelsPayload = ModelsManager.generateModelsResponse();
            HttpServerWrapper.sendResponse(exchange, 200, "application/json", modelsPayload);
            return;
        }

        super.handle(exchange);
    }
}