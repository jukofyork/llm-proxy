import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages model discovery, routing, and API responses with background refresh caching.
 * Handles fetching models from configured servers and provides model-to-server mapping.
 */
public class ModelsManager {

    public record ModelConfig(String endpoint, String displayName, String apiKey) {}

    private static final Map<String, ModelConfig> modelBackends = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    
    // Caching infrastructure
    private static final Map<String, ServerCache> serverCaches = new ConcurrentHashMap<>();
    private static final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "models-refresh");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "models-scheduler");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean isInitialized = new AtomicBoolean(false);
    
    /**
     * Cache entry for a server's models with timestamp.
     */
    private static class ServerCache {
        private final String serverName;
        private final ConfigurationManager.ServerConfig config;
        private volatile List<String> models;
        private volatile Instant lastRefresh;
        private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
        
        ServerCache(String serverName, ConfigurationManager.ServerConfig config) {
            this.serverName = serverName;
            this.config = config;
            this.models = Collections.emptyList();
            this.lastRefresh = Instant.EPOCH;
        }
        
        void updateModels(List<String> newModels) {
            this.models = Collections.unmodifiableList(new ArrayList<>(newModels));
            this.lastRefresh = Instant.now();
        }
        
        List<String> getModels() {
            return models;
        }
    }

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private ModelsManager() {
    }

    /**
     * Initializes model discovery by performing initial fetch from all configured servers
     * and starting the periodic background refresh.
     * 
     * @param serverConfigs Map of server configurations from ConfigurationManager
     * @return true if initialization was successful, false otherwise
     */
    public static boolean initialize(Map<String, ConfigurationManager.ServerConfig> serverConfigs) {
        // Initialize server caches
        for (Map.Entry<String, ConfigurationManager.ServerConfig> entry : serverConfigs.entrySet()) {
            serverCaches.put(entry.getKey(), new ServerCache(entry.getKey(), entry.getValue()));
        }
        
        // Perform initial synchronous fetch
        fetchAllAvailableModels();
        isInitialized.set(true);
        
        // Start periodic background refresh
        startPeriodicRefresh();
        
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
     * Generates models response for /models endpoint.
     * Always returns immediately from cache.
     * 
     * @return JSON string containing all available models
     */
    public static String generateModelsResponse() {
        // If not initialized yet, do a quick synchronous fetch
        if (!isInitialized.get()) {
            fetchAllAvailableModels();
        }
        
        // Always return current cached data immediately
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
            Logger.warning("Model '" + modelName + "' not found");
            return null;
        }

        if (!isValidEndpointForModel(path, config.endpoint())) {
            Logger.warning("Model '" + modelName + "' endpoint mismatch");
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
     * Starts the periodic background refresh of all server caches.
     */
    private static void startPeriodicRefresh() {
        long intervalSeconds = Constants.MODEL_REFRESH_INTERVAL.toSeconds();
        
        scheduledExecutor.scheduleAtFixedRate(() -> {
            Logger.info("Starting periodic model refresh");
            refreshAllServerCaches();
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        
        Logger.info("Periodic model refresh scheduled every " + Constants.MODEL_REFRESH_INTERVAL.toMinutes() + " minutes");
    }

    /**
     * Refreshes all server caches in parallel.
     */
    private static void refreshAllServerCaches() {
        List<CompletableFuture<Void>> refreshTasks = new ArrayList<>();
        
        for (ServerCache cache : serverCaches.values()) {
            if (cache.refreshInProgress.compareAndSet(false, true)) {
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> refreshServerCache(cache), backgroundExecutor);
                refreshTasks.add(task);
            }
        }
        
        // Wait for all refreshes to complete (with timeout)
        try {
            CompletableFuture.allOf(refreshTasks.toArray(new CompletableFuture[0]))
                .get(Constants.MODEL_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Logger.info("Periodic model refresh completed");
        } catch (Exception e) {
            Logger.warning("Some model refreshes did not complete in time", e);
        }
    }

    /**
     * Refreshes a single server's cache in the background.
     */
    private static void refreshServerCache(ServerCache cache) {
        try {
            Logger.info("Background refresh starting for server: " + cache.serverName);
            
			HttpClientWrapper httpClient = new HttpClientWrapper(Constants.MODEL_CONNECTION_TIMEOUT,
					Constants.MODEL_REQUEST_TIMEOUT);
            List<String> models = fetchModelsFromServer(httpClient, cache.config.endpoint(), cache.config.apiKey());
            models = filterModelsByAllowedList(models, cache.config.allowedModels());
            
            // Update cache
            cache.updateModels(models);
            
            // Update global model backends map
            updateModelBackendsForServer(cache.serverName, models, cache.config);
            
            Logger.info("Background refresh completed for server: " + cache.serverName + " (" + models.size() + " models)");
            
        } catch (Exception e) {
            Logger.warning("Background refresh failed for server: " + cache.serverName, e);
        } finally {
            cache.refreshInProgress.set(false);
        }
    }

    /**
     * Fetches available models from all configured servers (synchronous).
     */
    private static void fetchAllAvailableModels() {
        Logger.info("Fetching models from configured servers...");
        
        HttpClientWrapper httpClient = new HttpClientWrapper(Constants.MODEL_CONNECTION_TIMEOUT, Constants.MODEL_REQUEST_TIMEOUT);

        for (ServerCache cache : serverCaches.values()) {
            try {
                List<String> models = fetchModelsFromServer(httpClient, cache.config.endpoint(), cache.config.apiKey());
                models = filterModelsByAllowedList(models, cache.config.allowedModels());
                
                cache.updateModels(models);
                updateModelBackendsForServer(cache.serverName, models, cache.config);
                
                Logger.info("Server " + cache.serverName + " provides " + models.size() + " models");
                
            } catch (Exception e) {
                Logger.warning("Failed to fetch models from " + cache.serverName, e);
            }
        }

        Logger.info("Total models available: " + modelBackends.size());
    }

    /**
     * Updates the global model backends map for a specific server.
     */
    private static void updateModelBackendsForServer(String serverName, List<String> models, ConfigurationManager.ServerConfig config) {
        // Remove old models from this server
        modelBackends.entrySet().removeIf(entry -> 
            entry.getValue().endpoint().equals(config.endpoint()));
        
        // Add new models from this server
        for (String modelName : models) {
            modelBackends.put(modelName, new ModelConfig(config.endpoint(), modelName, config.apiKey()));
        }
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