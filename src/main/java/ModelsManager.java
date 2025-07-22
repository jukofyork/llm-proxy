import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages model discovery, routing, and API responses.
 * Handles fetching models from configured servers and provides model-to-server mapping.
 */
public class ModelsManager {

    public record ModelConfig(String endpoint, String displayName, String apiKey) {}

    private static final Map<String, ModelConfig> modelBackends = new HashMap<>();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private ModelsManager() {
    }

    /**
     * Initializes model discovery by fetching models from all configured servers.
     * 
     * @param serverConfigs Map of server configurations from ConfigurationManager
     * @return true if initialization was successful, false otherwise
     */
    public static boolean initialize(Map<String, ConfigurationManager.ServerConfig> serverConfigs) {
        fetchAllAvailableModels(serverConfigs);
        return true;
    }

    /**
     * Returns the model backends mapping.
     * 
     * @return Map of model names to their configurations
     */
    public static Map<String, ModelConfig> getModelBackends() {
        return Collections.unmodifiableMap(modelBackends);
    }

    /**
     * Generates models response for /models endpoint on-demand.
     * 
     * @return JSON string containing all available models
     */
    public static String generateModelsResponse() {
        try {
            List<Map<String, String>> models = modelBackends.keySet().stream()
                .map(modelId -> Map.of("id", modelId, "object", "model"))
                .toList();
            
            Map<String, Object> response = Map.of("object", "list", "data", models);
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(response);
        } catch (Exception e) {
            Logger.error("Failed to generate models response", e);
            return "{\"object\":\"list\",\"data\":[]}";
        }
    }

    /**
     * Finds appropriate model configuration for the request.
     */
    public static ModelConfig findModelConfigForRequest(String method, String path, String body, Map<String, String> headers) {
        String modelName = extractModelNameFromRequest(path, body, headers);
        if (modelName == null) {
            return null;
        }

        ModelConfig config = modelBackends.get(modelName);
        if (config == null) {
            Logger.error("Model '" + modelName + "' not found");
            return null;
        }

        if (!isValidEndpointForModel(path, config.endpoint())) {
            Logger.error("Model '" + modelName + "' endpoint mismatch");
            return null;
        }

        return config;
    }

    /**
     * Extracts model name from request based on endpoint type.
     */
    public static String extractModelNameFromRequest(String path, String body, Map<String, String> headers) {
        if (path.startsWith(Constants.V1_PREFIX)) {
            return extractModelNameFromJson(body);
        } else {
            String authHeader = headers.get("authorization");
            return authHeader != null ? authHeader.replace("Bearer ", "") : null;
        }
    }

    /**
     * Determines if the request expects a streaming response.
     */
    public static boolean determineIfStreamingRequest(String body) {
        return !body.matches(".*\"stream\"\\s*:\\s*false.*");
    }

    /**
     * Builds the final request path for the backend server.
     */
    public static String buildFinalRequestPath(String requestPath, String endpoint) {
        if (endpoint.endsWith(Constants.V1_PREFIX) && requestPath.startsWith(Constants.V1_PREFIX)) {
            return requestPath.substring(Constants.V1_PREFIX.length());
        }
        return requestPath;
    }

    /**
     * Fetches available models from all configured servers.
     */
    private static void fetchAllAvailableModels(Map<String, ConfigurationManager.ServerConfig> serverConfigs) {
        Logger.info("Fetching models from configured servers...");
        
        HttpClientWrapper httpClient = new HttpClientWrapper(Constants.CONNECTION_TIMEOUT, Constants.REQUEST_TIMEOUT);

        for (Map.Entry<String, ConfigurationManager.ServerConfig> entry : serverConfigs.entrySet()) {
            String serverName = entry.getKey();
            ConfigurationManager.ServerConfig config = entry.getValue();

            try {
                List<String> models = fetchModelsFromServer(httpClient, config.endpoint(), config.apiKey());
                models = filterModelsByAllowedList(models, config.allowedModels());
                
                Logger.info("Server " + serverName + " provides " + models.size() + " models");
                
                registerModelsForServer(models, config);
            } catch (Exception e) {
                Logger.error("Failed to fetch models from " + serverName, e);
            }
        }

        Logger.info("Total models available: " + modelBackends.size());
    }

    /**
     * Fetches available models from a specific server endpoint.
     */
    private static List<String> fetchModelsFromServer(HttpClientWrapper httpClient, String baseUrl, String apiKey) throws Exception {
        String modelsUrl = baseUrl + Constants.MODELS_ENDPOINT;
        URI modelsUri = URI.create(modelsUrl);
        
        HttpResponse<InputStream> response = httpClient.sendRequest(modelsUri, apiKey, null, false);
        
        if (response.statusCode() != 200) {
            throw new Exception("HTTP " + response.statusCode() + ": " + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        
        String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        
        return parseModelNamesFromResponse(responseBody);
    }

    /**
     * Parses model names from API response JSON.
     */
    private static List<String> parseModelNamesFromResponse(String responseBody) throws Exception {
        JsonNode root = JSON_MAPPER.readTree(responseBody);
        List<String> modelNames = new ArrayList<>();

        if (root.has("data") && root.get("data").isArray()) {
            for (JsonNode modelNode : root.get("data")) {
                if (modelNode.has("id")) {
                    modelNames.add(modelNode.get("id").asText());
                }
            }
        }

        Collections.sort(modelNames);
        return modelNames;
    }

    /**
     * Registers models from a server in the model backends map.
     */
    private static void registerModelsForServer(List<String> models, ConfigurationManager.ServerConfig config) {
        for (String modelName : models) {
            modelBackends.put(modelName, new ModelConfig(config.endpoint(), modelName, config.apiKey()));
        }
    }

    /**
     * Filters models based on allowed models list.
     */
    private static List<String> filterModelsByAllowedList(List<String> models, List<String> allowedModels) {
        if (allowedModels == null) {
            return models;
        }
        return models.stream().filter(allowedModels::contains).toList();
    }

    /**
     * Extracts model name from JSON request body.
     */
    private static String extractModelNameFromJson(String body) {
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            return root.has("model") ? root.get("model").asText() : null;
        } catch (Exception e) {
            Logger.error("Invalid JSON in request body");
            return null;
        }
    }

    /**
     * Validates if the request path is compatible with the model's endpoint.
     */
    private static boolean isValidEndpointForModel(String requestPath, String modelEndpoint) {
        if (!requestPath.startsWith(Constants.V1_PREFIX) && modelEndpoint.endsWith(Constants.V1_PREFIX)) {
            return false;
        }
        return true;
    }
}