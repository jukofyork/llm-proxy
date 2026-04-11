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
 * Manages model discovery and registry from configured servers.
 * - Fetches base models from each server
 * - Applies allow-list filtering
 * - Exposes a registry of models (base + virtual names) -> endpoint/apiKey
 */
public class ModelsManager {

    public record ModelConfig(String endpoint, String displayName, String apiKey) {}

    private static final Map<String, ModelConfig> registeredModels = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static RuntimeConfig runtimeConfig;

    private static ProxySettings settings;

    private static Instant lastRefreshTime = Instant.EPOCH;

    private static ScheduledExecutorService scheduledExecutor;

    private ModelsManager() {}

    /**
     * Initializes the model manager with runtime configuration and settings.
     * Performs an initial synchronous model fetch and starts periodic background refresh.
     *
     * @param runtime the runtime configuration containing server definitions
     * @param proxySettings the proxy settings for timeouts and refresh intervals
     * @return true if initialization succeeded
     */
    public static boolean initialize(RuntimeConfig runtime, ProxySettings proxySettings) {
        runtimeConfig = runtime;
        settings = proxySettings;

        // Perform initial synchronous fetch before accepting requests
        Logger.info("Initializing model registry...");
        refreshRegisteredModelsSync();

        // Start periodic background refresh
        startPeriodicRefresh();

        return true;
    }

    /**
     * Generates the OpenAI-compatible /v1/models response as JSON.
     * Triggers an async refresh of the model registry before returning cached data.
     *
     * @return JSON string containing the list of registered models
     */
    public static String generateModelsResponse() {
        refreshRegisteredModelsAsync();

        try {
            List<Map<String, String>> models = registeredModels.keySet().stream()
                    .sorted()
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
     * Retrieves configuration for a specific model.
     * If the model is not found in the registry, triggers a synchronous refresh
     * and checks again (useful for newly added models).
     *
     * @param modelName the model identifier to look up
     * @return the model configuration, or null if not found
     */
    public static ModelConfig getModelConfig(String modelName) {
        ModelConfig config = registeredModels.get(modelName);
        if (config != null) {
            return config;
        }

        // Model not found - trigger synchronous refresh (bypass TTL)
        Logger.info("Model '" + modelName + "' not in registry, triggering refresh...");
        refreshRegisteredModelsSync();

        // Check again after refresh
        return registeredModels.get(modelName);
    }

    /**
     * Triggers a synchronous refresh of the model registry.
     * Called by ConfigWatcher after successful configuration reload.
     *
     * @param runtime the updated runtime configuration
     */
    public static void refreshModels(RuntimeConfig runtime) {
        runtimeConfig = runtime;
        Logger.info("Triggering model registry refresh due to configuration change...");
        refreshRegisteredModelsSync();
    }

    /**
     * Validates that a request path is compatible with the model's endpoint.
     * Checks for mismatches where endpoint ends with /v1 but request path doesn't start with /v1.
     *
     * @param requestPath the incoming request path
     * @param modelEndpoint the endpoint URL for the target model
     * @return true if the endpoint is compatible with the request path
     */
    public static boolean validateModelEndpoint(String requestPath, String modelEndpoint) {
        String v1Prefix = "/v1";
        if (!requestPath.startsWith(v1Prefix) && modelEndpoint.endsWith(v1Prefix)) {
            return false;
        }
        return true;
    }

    /**
     * Builds the final request path by stripping duplicate /v1 prefixes.
     * When endpoint ends with /v1 and request path starts with /v1,
     * removes the leading /v1 from the request path to avoid duplication.
     *
     * @param requestPath the original request path
     * @param endpoint the target endpoint URL
     * @return the adjusted request path
     */
    public static String buildFinalRequestPath(String requestPath, String endpoint) {
        String v1Prefix = "/v1";
        if (endpoint.endsWith(v1Prefix) && requestPath.startsWith(v1Prefix)) {
            return requestPath.substring(v1Prefix.length());
        }
        return requestPath;
    }

    private static void startPeriodicRefresh() {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            return;
        }

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "models-refresh");
            t.setDaemon(true);
            return t;
        });

        scheduledExecutor.scheduleAtFixedRate(
                ModelsManager::refreshRegisteredModelsAsync,
                settings.modelRefreshInterval.getSeconds(),
                settings.modelRefreshInterval.getSeconds(),
                TimeUnit.SECONDS
        );

        Logger.info("Started periodic model refresh every " + settings.modelRefreshInterval.getSeconds() + " seconds");
    }

    private static void refreshRegisteredModelsAsync() {
        // Async refresh respects TTL - only refresh if TTL expired
        if (Instant.now().isBefore(lastRefreshTime.plus(settings.modelRefreshInterval))) {
            return;
        }

        CompletableFuture.runAsync(ModelsManager::refreshRegisteredModelsSync);
    }

    private static void refreshRegisteredModelsSync() {
        if (runtimeConfig == null) {
            Logger.warning("ModelsManager not initialized");
            return;
        }

        Map<String, ModelConfig> temp = new ConcurrentHashMap<>();
        HttpClientWrapper httpClient = new HttpClientWrapper(
                settings.connectionTimeout,
                settings.modelRequestTimeout
        );

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (RuntimeConfig.CompiledServer server : runtimeConfig.serversByName.values()) {
            String endpoint = server.endpoint;
            String apiKey = "bearer".equals(server.authType) ? server.apiKey : null;

            CompletableFuture<Void> t = CompletableFuture.runAsync(() -> {
                try {
                    List<String> models = retrieveServerModels(httpClient, endpoint, apiKey);
                    models = filterAllowedModels(models, server.modelAllowList);

                    // Register base models (optional)
                    if (!server.hideBaseModels) {
                        for (String m : models) {
                            temp.put(m, new ModelConfig(endpoint, m, apiKey));
                        }
                    }

                    // Register virtual models (profiles) for this server
                    for (String m : models) {
                        for (String suffix : server.profilesBySuffix.keySet()) {
                            String virtualName = m + "-" + suffix;
                            temp.put(virtualName, new ModelConfig(endpoint, virtualName, apiKey));
                        }
                    }

                    Logger.info("Server " + server.name + " provides " + models.size() + " models");
                } catch (Exception e) {
                    Logger.warning("Failed to fetch models from " + server.name, e);
                }
            });

            tasks.add(t);
        }

        try {
            CompletableFuture
                    .allOf(tasks.toArray(new CompletableFuture[0]))
                    .get(settings.modelRequestTimeout.getSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            Logger.warning("Some model fetches did not complete in time", e);
        }

        // Atomic update strategy to prevent race conditions:
        // 1. putAll() adds new models and updates existing ones while keeping old entries
        // 2. retainAll() removes only stale entries that are no longer in the new set
        // This ensures concurrent readers never see an empty or partially-updated registry
        registeredModels.putAll(temp);
        registeredModels.keySet().retainAll(temp.keySet());
        lastRefreshTime = Instant.now();

        Logger.info("Model registry updated: " + registeredModels.size() + " models registered");
    }

	private static List<String> retrieveServerModels(HttpClientWrapper httpClient, String baseUrl, String apiKey)
			throws Exception {
		
		// NOTE: We need to try both endpoints, as ik_llama.cpp returns 404 for the "/models" endpoint.
        String[] endpointPaths = {"/models", "/v1/models"};
        
        Map<String, Integer> failedAttempts = new LinkedHashMap<>();
        
        for (String path : endpointPaths) {
            String url = baseUrl + path;
            URI uri = URI.create(url);
            HttpResponse<InputStream> response = httpClient.sendRequest(uri, apiKey, null, false).get();
            int statusCode = response.statusCode();
            
            if (statusCode == 200) {
                byte[] rawBody = response.body().readAllBytes();
                String responseBody = new String(rawBody, StandardCharsets.UTF_8);
                return extractModelNames(responseBody);
            }
            
            failedAttempts.put(path, statusCode);
        }
        
        String errorMsg = failedAttempts.entrySet().stream()
                .map(e -> "HTTP " + e.getValue() + " (" + e.getKey() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("Unknown error");
        throw new Exception("Failed to fetch models: " + errorMsg);
    }

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
     * Filters the models list based on allow-list rules.
     * Hidden models (prefixed with *) are always added regardless of backend response.
     * This allows exposing models that backends like Fireworks don't list in /v1/models.
     */
    private static List<String> filterAllowedModels(List<String> models, List<String> allow) {
        if (allow == null || allow.isEmpty()) return models;
        
        Set<String> allowed = new HashSet<>();
        List<String> hidden = new ArrayList<>();
        
        // Hidden models are prefixed with * and bypass the backend's model list.
        // Example: "*accounts/fireworks/routers/kimi-k2p5-turbo" for Fireworks Fire Pass models
        for (String a : allow) {
            if (a.startsWith("*")) {
                if (a.length() > 1) hidden.add(a.substring(1));
            } else {
                allowed.add(a);
            }
        }
        
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        for (String m : models) {
            if (allowed.contains(m) && seen.add(m)) {
                result.add(m);
            }
        }
        
        for (String h : hidden) {
            if (seen.add(h)) {
                result.add(h);
            }
        }
        
        return result;
    }

}