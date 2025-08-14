import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages model discovery and routing by fetching fresh model data on each request.
 */
public class ModelsManager {

    /**
     * Holds essential model configuration details.
     */
    public record ModelConfig(String endpoint, String displayName, String apiKey) {
    }

    private static final Map<String, ModelConfig> registeredModels = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static Map<String, ConfigurationManager.ServerConfig> configuredServers = new ConcurrentHashMap<>();

    // Tracks the last time models were refreshed
    private static Instant lastRefreshTime = Instant.EPOCH;

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private ModelsManager() {
    }

    /**
     * Initializes the ModelsManager with the given server configurations.
     *
     * @param configs Map of server configurations from ConfigurationManager
     * @return true if initialization was successful, false otherwise
     */
    public static boolean initialize(Map<String, ConfigurationManager.ServerConfig> configs) {
        configuredServers = new ConcurrentHashMap<>(configs);
        return true;
    }

    /**
     * Generates models response for /models endpoint by fetching fresh data from
     * all servers, then returns a JSON string listing all available models.
     *
     * @return JSON string containing all available models
     */
    public static String generateModelsResponse() {
        refreshRegisteredModels();

        try {
            List<Map<String, String>> models = registeredModels.keySet().stream()
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
     * Gets the model configuration for a specific model name.
     *
     * @param modelName the model name
     * @return a ModelConfig for the model, or null if not found
     */
    public static ModelConfig getModelConfig(String modelName) {
        return registeredModels.get(modelName);
    }

    /**
     * Gets the server configuration for a specific model.
     *
     * @param modelName the model name
     * @return the ServerConfig for this model, or null if not found
     */
    public static ConfigurationManager.ServerConfig getServerConfigForModel(String modelName) {
        // Try virtual model lookup first
        ConfigurationManager.ServerConfig virtualConfig = getVirtualServerConfigForModel(modelName);
        if (virtualConfig != null) return virtualConfig;

        // Fall back to direct lookup for base models
        return getDirectServerConfigForModel(modelName);
    }

    /**
     * Gets the effective parameters for a model, merging base and virtual parameters.
     *
     * @param modelName the model name
     * @return merged parameters, or null if no config found
     */
    public static JsonNode getEffectiveParamsForModel(String modelName) {
        ConfigurationManager.ServerConfig serverConfig = getServerConfigForModel(modelName);
        if (serverConfig == null) return null;

        // If not a virtual endpoint, just return its params
        if (serverConfig.virtualEndpointFor() == null) {
            return serverConfig.params();
        }

        // Get base config params for virtual endpoints
        ConfigurationManager.ServerConfig baseConfig = getDirectServerConfigForModel(modelName);
        JsonNode baseParams = baseConfig != null ? baseConfig.params() : null;
        JsonNode virtualParams = serverConfig.params();

        // Merge base params with virtual params (virtual overrides base)
        if (baseParams == null) return virtualParams;
        if (virtualParams == null) return baseParams;

        try {
            com.fasterxml.jackson.databind.node.ObjectNode merged = baseParams.deepCopy();
            return JSON_MAPPER.readerForUpdating(merged).readValue(virtualParams);
        } catch (Exception e) {
            Logger.warning("Failed to merge base and virtual params: " + e.getMessage());
            return virtualParams; // Fallback to just virtual params
        }
    }

    /**
     * Gets the server configuration for a model by direct lookup.
     *
     * @param modelName the model name
     * @return the ServerConfig for this model, or null if not found
     */
    private static ConfigurationManager.ServerConfig getDirectServerConfigForModel(String modelName) {
        ModelConfig modelConfig = registeredModels.get(modelName);
        if (modelConfig == null) {
            return null;
        }

        for (ConfigurationManager.ServerConfig serverConfig : configuredServers.values()) {
            if (serverConfig.endpoint() != null && serverConfig.endpoint().equals(modelConfig.endpoint())) {
                return serverConfig;
            }
        }
        return null;
    }

    /**
     * Gets the server configuration for a virtual model.
     *
     * @param modelName the model name
     * @return the virtual ServerConfig for this model, or null if not found
     */
    private static ConfigurationManager.ServerConfig getVirtualServerConfigForModel(String modelName) {
        for (Map.Entry<String, ConfigurationManager.ServerConfig> entry : configuredServers.entrySet()) {
            String configName = entry.getKey();
            ConfigurationManager.ServerConfig config = entry.getValue();

            if (config.virtualEndpointFor() == null) continue;

            String baseServerName = config.virtualEndpointFor();
            String virtualSuffix = configName.substring(baseServerName.length() + 1);

            if (modelName.endsWith("-" + virtualSuffix)) {
                return config;
            }
        }
        return null;
    }

    /**
     * Gets the configuration name for a specific model.
     *
     * @param modelName the model name
     * @return the configuration name for this model, or null if not found
     */
    public static String getConfigNameForModel(String modelName) {
        for (Map.Entry<String, ConfigurationManager.ServerConfig> entry : configuredServers.entrySet()) {
            String configName = entry.getKey();
            ConfigurationManager.ServerConfig config = entry.getValue();
            
            if (config.virtualEndpointFor() != null) {
                String virtualSuffix = configName.substring(config.virtualEndpointFor().length() + 1);
                if (modelName.endsWith("-" + virtualSuffix)) {
                    return configName;
                }
            }
        }
        return null;
    }

    /**
     * Validates if the request path is compatible with the model's endpoint.
     *
     * @param requestPath   the incoming request path
     * @param modelEndpoint the model's configured endpoint
     * @return true if valid, false otherwise
     */
    public static boolean validateModelEndpoint(String requestPath, String modelEndpoint) {
        if (!requestPath.startsWith(Constants.V1_PREFIX) && modelEndpoint.endsWith(Constants.V1_PREFIX)) {
            return false;
        }
        return true;
    }

    /**
     * Builds the final request path for the backend server.
     *
     * @param requestPath the requested path
     * @param endpoint    the model's endpoint URL
     * @return the modified path to be appended to the endpoint
     */
    public static String buildFinalRequestPath(String requestPath, String endpoint) {
        if (endpoint.endsWith(Constants.V1_PREFIX) && requestPath.startsWith(Constants.V1_PREFIX)) {
            return requestPath.substring(Constants.V1_PREFIX.length());
        }
        return requestPath;
    }

    /**
     * Refreshes the internally registered models by fetching updated models
     * information from all configured servers in parallel, respecting a refresh TTL.
     */
    private static void refreshRegisteredModels() {
        // Check if the last refresh was too recent
        if (Instant.now().isBefore(lastRefreshTime.plus(Constants.MODEL_REFRESH_TTL))) {
            Logger.info("Skipping model refresh due to TTL...");
            return;
        }

        Logger.info("Fetching models from configured servers...");

        // Build models in a temporary map to avoid partial updates during concurrent access
        Map<String, ModelConfig> tempModels = new ConcurrentHashMap<>();
        HttpClientWrapper httpClient = new HttpClientWrapper(
                Constants.MODEL_CONNECTION_TIMEOUT,
                Constants.MODEL_REQUEST_TIMEOUT
        );

        List<CompletableFuture<Void>> fetchTasks = new ArrayList<>();

        for (Map.Entry<String, ConfigurationManager.ServerConfig> entry : configuredServers.entrySet()) {
            String serverName = entry.getKey();
            ConfigurationManager.ServerConfig config = entry.getValue();

            // Skip virtual endpoints - they don't have their own models
            if (config.endpoint() == null) continue;

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    List<String> models = retrieveServerModels(httpClient, config.endpoint(), config.apiKey());
                    models = filterAllowedModels(models, config.allowedModels());
                    // Build server-specific map
                    Map<String, ModelConfig> serverModels = buildServerModels(serverName, models, config);
                    // Merge into temp map
                    tempModels.putAll(serverModels);

                    Logger.info("Server " + serverName + " provides " + models.size() + " models");
                } catch (Exception e) {
                    Logger.warning("Failed to fetch models from " + serverName, e);
                }
            });

            fetchTasks.add(task);
        }

        // Wait for all fetches to complete
        try {
            CompletableFuture
                    .allOf(fetchTasks.toArray(new CompletableFuture[0]))
                    .get(Constants.MODEL_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            Logger.warning("Some model fetches did not complete in time", e);
        }

        // After fetching base models, register virtual models
        registerVirtualModels(tempModels);

        // Atomically replace the registered models with the newly constructed snapshot
        registeredModels.clear();
        registeredModels.putAll(tempModels);
        lastRefreshTime = Instant.now();

        Logger.info("Proxy provides " + registeredModels.size() + " models: " + registeredModels.keySet());
    }

    /**
     * Registers virtual models based on virtual endpoint configurations.
     *
     * @param tempModels the temporary models map to add virtual models to
     */
    private static void registerVirtualModels(Map<String, ModelConfig> tempModels) {
        // First, identify all base models (before any virtual models are added)
        Set<String> baseModels = new HashSet<>(tempModels.keySet());
        
        for (Map.Entry<String, ConfigurationManager.ServerConfig> entry : configuredServers.entrySet()) {
            String configName = entry.getKey();
            ConfigurationManager.ServerConfig config = entry.getValue();

            // Skip non-virtual endpoints
            if (config.virtualEndpointFor() == null) continue;

            String baseServerName = config.virtualEndpointFor();
            String virtualSuffix = configName.substring(baseServerName.length() + 1);

            // Find base server's models and create virtual variants
            ConfigurationManager.ServerConfig baseConfig = configuredServers.get(baseServerName);
            if (baseConfig == null || baseConfig.endpoint() == null) continue;

            // Get ONLY base models (not previously created virtual models)
            List<String> serverBaseModels = baseModels.stream()
                .filter(modelName -> {
                    ModelConfig modelConfig = tempModels.get(modelName);
                    return modelConfig.endpoint().equals(baseConfig.endpoint());
                })
                .toList();

            // Register virtual models
            for (String baseModel : serverBaseModels) {
                String virtualModel = baseModel + "-" + virtualSuffix;
                ModelConfig baseModelConfig = tempModels.get(baseModel);
                tempModels.put(virtualModel, baseModelConfig);
            }

            Logger.info("Registered " + serverBaseModels.size() + " virtual models for " + configName);
        }
    }

    /**
     * Constructs a map of model names to ModelConfig objects for a given server.
     *
     * @param serverName the name of the server
     * @param models     the list of model IDs
     * @param config     the server configuration containing endpoint and apiKey
     * @return a map of modelName -> ModelConfig
     */
    private static Map<String, ModelConfig> buildServerModels(String serverName, List<String> models,
            ConfigurationManager.ServerConfig config) {
        Map<String, ModelConfig> serverModels = new HashMap<>();
        for (String modelName : models) {
            serverModels.put(modelName, new ModelConfig(config.endpoint(), modelName, config.apiKey()));
        }
        return serverModels;
    }

    /**
     * Retrieves the list of models from a specific server by issuing a request to its
     * /models endpoint and parsing the JSON response.
     *
     * @param httpClient the HTTP client wrapper
     * @param baseUrl    the server's base URL
     * @param apiKey     the API key, if needed, for this server
     * @return a List of model names provided by the server
     * @throws Exception if the request fails or the server returns an unexpected status
     */
    private static List<String> retrieveServerModels(HttpClientWrapper httpClient, String baseUrl, String apiKey)
            throws Exception {
        String modelsUrl = baseUrl + Constants.MODELS_ENDPOINT;
        URI modelsUri = URI.create(modelsUrl);

        HttpResponse<InputStream> response = httpClient.sendRequest(modelsUri, apiKey, null, false);
        int statusCode = response.statusCode();
        byte[] rawBody = response.body().readAllBytes();

        if (statusCode != 200) {
            throw new Exception("HTTP " + statusCode + ": " + new String(rawBody, StandardCharsets.UTF_8));
        }

        String responseBody = new String(rawBody, StandardCharsets.UTF_8);
        return extractModelNames(responseBody);
    }

    /**
     * Extracts model names from the supplied JSON response body.
     *
     * @param responseBody the JSON response body from the backend server
     * @return a list of extracted model identifiers
     * @throws Exception if JSON parsing fails
     */
    private static List<String> extractModelNames(String responseBody) throws Exception {
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
     * Filters the full list of models to include only those present in the allowed
     * models list, if provided.
     *
     * @param models        the raw list of models
     * @param allowedModels an optional list of models to allow
     * @return a filtered list of models
     */
    private static List<String> filterAllowedModels(List<String> models, List<String> allowedModels) {
        if (allowedModels == null) {
            return models;
        }
        return models.stream().filter(allowedModels::contains).toList();
    }
}