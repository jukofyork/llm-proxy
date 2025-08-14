import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Router implementation that determines which backend server to use based on
 * model information contained in the request. Handles request body transformation
 * including parameter filtering and overrides.
 */
public class ModelRequestRouter implements HttpProxy.RequestRouter {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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
    public HttpProxy.ProxyTarget route(String method, String requestPath, String requestBody, Map<String, String> headers) {
        // Extract model name from request
        String modelName = extractRequestedModelName(requestPath, requestBody, headers);
        if (modelName == null) {
            return null;
        }

        // Get model configuration
        ModelsManager.ModelConfig targetModelConfig = ModelsManager.getModelConfig(modelName);
        if (targetModelConfig == null) {
            Logger.warning("Model '" + modelName + "' not found");
            return null;
        }

        // Validate endpoint compatibility
        if (!ModelsManager.validateModelEndpoint(requestPath, targetModelConfig.endpoint())) {
            Logger.warning("Model '" + modelName + "' endpoint mismatch");
            return null;
        }

        // Transform request body based on server configuration
        String transformedBody = transformRequestBody(requestBody, modelName);
        
        Logger.info("Routing to " + targetModelConfig.displayName() + " (" + targetModelConfig.endpoint() + ")");
        
        // Log transformed request if debug enabled
        logRequestPayloadIfDebug(transformedBody);
        
        // Build target URI
        String resolvedPath = ModelsManager.buildFinalRequestPath(requestPath, targetModelConfig.endpoint());
        String resolvedUrl = targetModelConfig.endpoint() + resolvedPath;
        URI targetUri = URI.create(resolvedUrl);
        
        // Determine if streaming (using transformed body)
        boolean isStreaming = determineIfStreamingRequest(transformedBody);
        
        return new HttpProxy.ProxyTarget(targetUri, targetModelConfig.apiKey(), isStreaming, transformedBody);
    }

    /**
     * Extracts the model name from the request based on endpoint type.
     *
     * @param path the request path
     * @param body the request body
     * @param headers the request headers
     * @return the extracted model name, or null if not found
     */
    private String extractRequestedModelName(String path, String body, Map<String, String> headers) {
        if (path.startsWith(Constants.V1_PREFIX)) {
            try {
                JsonNode root = JSON_MAPPER.readTree(body);
                return root.has("model") ? root.get("model").asText() : null;
            } catch (Exception e) {
                Logger.error("Invalid JSON in request body");
                return null;
            }
        }

        // To allow passing model name via llama.cpp API, fallback to extracting from key
        String authHeader = headers.get("authorization");
        return authHeader != null ? authHeader.replace("Bearer ", "") : null;
    }

    /**
     * Transforms request body based on server configuration.
     *
     * @param requestBody the original request body
     * @param modelName the target model name
     * @return the transformed request body
     */
    private String transformRequestBody(String requestBody, String modelName) {
        if (requestBody == null || requestBody.isEmpty()) {
            return requestBody;
        }
        
        ConfigurationManager.ServerConfig serverConfig = ModelsManager.getServerConfigForModel(modelName);
        if (serverConfig == null) {
            return requestBody;
        }
        
        try {
            JsonNode requestJson = JSON_MAPPER.readTree(requestBody);
            
            // Only proceed if we have an object node
            if (!requestJson.isObject()) {
                return requestBody;
            }
            
            com.fasterxml.jackson.databind.node.ObjectNode objectNode = 
                (com.fasterxml.jackson.databind.node.ObjectNode) requestJson;
            
            // Apply disallowed parameter filtering first
            if (serverConfig.disallowedParams() != null) {
                for (String param : serverConfig.disallowedParams()) {
                    removeParameterByPath(objectNode, param);
                }
            }
            
            // Apply parameter overrides safely
            if (serverConfig.params() != null && serverConfig.params().isObject()) {
                try {
                    objectNode = (com.fasterxml.jackson.databind.node.ObjectNode) 
                        JSON_MAPPER.readerForUpdating(objectNode).readValue(serverConfig.params());
                } catch (Exception e) {
                    Logger.warning("Failed to apply parameter overrides for model '" + modelName + "': " + e.getMessage());
                    // Continue with original objectNode if override fails
                }
            }
            
            return JSON_MAPPER.writeValueAsString(objectNode);
        } catch (Exception e) {
            Logger.error("Failed to transform request body", e);
            return requestBody;
        }
    }

    /**
     * Removes a parameter from a JSON object by path (supports nested paths with dot notation).
     *
     * @param node the JSON object node to modify
     * @param paramPath the parameter path to remove (supports dot notation for nested paths)
     */
    private void removeParameterByPath(com.fasterxml.jackson.databind.node.ObjectNode node, String paramPath) {
        if (paramPath == null || paramPath.isEmpty()) {
            return;
        }
        
        if (!paramPath.contains(".")) {
            // Simple top-level parameter
            node.remove(paramPath);
            return;
        }
        
        // Handle nested path
        String[] parts = paramPath.split("\\.", 2);
        String currentKey = parts[0];
        String remainingPath = parts[1];
        
        JsonNode child = node.get(currentKey);
        if (child != null && child.isObject()) {
            removeParameterByPath((com.fasterxml.jackson.databind.node.ObjectNode) child, remainingPath);
        }
    }
    
    /**
     * Determines if the request expects a streaming response.
     *
     * @param body the request body
     * @return true if streaming is expected, false otherwise
     */
    private boolean determineIfStreamingRequest(String body) {
        try {
            if (body == null || body.isEmpty()) {
                return true;  // Default if no body
            }
            JsonNode root = JSON_MAPPER.readTree(body);
            JsonNode streamNode = root.get("stream");
            // If "stream" is explicitly false, disable streaming; otherwise enable it
            if (streamNode != null && streamNode.isBoolean() && !streamNode.asBoolean()) {
                return false;
            }
            return true;
        } catch (Exception e) {
            // If JSON is invalid, default to streaming
            return true;
        }
    }

    /**
     * Logs the request body as pretty-printed JSON if debug mode is enabled.
     *
     * @param requestBody the request body to log
     */
    private void logRequestPayloadIfDebug(String requestBody) {
        if (Constants.DEBUG_REQUEST && !requestBody.isEmpty()) {
            try {
                JsonNode jsonNode = JSON_MAPPER.readTree(requestBody);
                String prettyJson = JSON_MAPPER.writerWithDefaultPrettyPrinter()
                                                .writeValueAsString(jsonNode);
                Logger.info("Sending JSON:\n" + prettyJson + "\n---");
            } catch (Exception e) {
                Logger.info("Sending JSON:\n" + requestBody + "\n---");
            }
        }
    }
}