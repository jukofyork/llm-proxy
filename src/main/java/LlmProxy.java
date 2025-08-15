import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

/**
 * A clean proxy server for OpenAI-compatible APIs.
 * Routes requests to multiple backend servers based on model selection.
 * Supports both streaming and non-streaming responses with TOML configuration.
 */
public class LlmProxy extends HttpProxy {

    public LlmProxy(RuntimeConfig runtime) {
        super(
            Constants.PROXY_PORT,
            Constants.CONNECTION_TIMEOUT,
            Constants.REQUEST_TIMEOUT,
            new ModelRequestRouter(runtime)
        );
    }

    public static void main(String[] args) throws Exception {
        RuntimeConfig runtime = ConfigLoader.load(Constants.CONFIG_FILE);
        if (runtime == null) {
            Logger.error("Failed to initialize configuration");
            System.exit(1);
        }

        if (!ModelsManager.initialize(runtime)) {
            Logger.error("Failed to initialize models");
            System.exit(1);
        }

        LlmProxy proxy = new LlmProxy(runtime);
        proxy.start();

        Logger.info("Proxy server running on port " + Constants.PROXY_PORT);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();

        if (requestPath.endsWith(Constants.MODELS_ENDPOINT)) {
            String modelsPayload = ModelsManager.generateModelsResponse();
            HttpServerWrapper.sendResponse(exchange, 200, "application/json", modelsPayload);
            return;
        }

        super.handle(exchange);
    }
}