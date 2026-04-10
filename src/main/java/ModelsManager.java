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

    private static Instant lastRefreshTime = Instant.EPOCH;

    private static ScheduledExecutorService scheduledExecutor;

    private ModelsManager() {}

    public static boolean initialize(RuntimeConfig runtime) {
        runtimeConfig = runtime;

        // Perform initial synchronous fetch before accepting requests
        Logger.info("Initializing model registry...");
        refreshRegisteredModelsSync();

        // Start periodic background refresh
        startPeriodicRefresh();

        return true;
    }

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

    public static boolean validateModelEndpoint(String requestPath, String modelEndpoint) {
        if (!requestPath.startsWith(Constants.V1_PREFIX) && modelEndpoint.endsWith(Constants.V1_PREFIX)) {
            return false;
        }
        return true;
    }

    public static String buildFinalRequestPath(String requestPath, String endpoint) {
        if (endpoint.endsWith(Constants.V1_PREFIX) && requestPath.startsWith(Constants.V1_PREFIX)) {
            return requestPath.substring(Constants.V1_PREFIX.length());
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
                Constants.MODEL_REFRESH_TTL.toSeconds(),
                Constants.MODEL_REFRESH_TTL.toSeconds(),
                TimeUnit.SECONDS
        );

        Logger.info("Started periodic model refresh every " + Constants.MODEL_REFRESH_TTL.toSeconds() + " seconds");
    }

    private static void refreshRegisteredModelsAsync() {
        // Async refresh respects TTL - only refresh if TTL expired
        if (Instant.now().isBefore(lastRefreshTime.plus(Constants.MODEL_REFRESH_TTL))) {
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
                Constants.MODEL_CONNECTION_TIMEOUT,
                Constants.MODEL_REQUEST_TIMEOUT
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
                    .get(Constants.MODEL_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            Logger.warning("Some model fetches did not complete in time", e);
        }

        // Atomic update: putAll updates/adds entries, then remove stale entries
        // This avoids a window where the map is empty (race condition)
        registeredModels.putAll(temp);
        registeredModels.keySet().retainAll(temp.keySet());
        lastRefreshTime = Instant.now();

        Logger.info("Model registry updated: " + registeredModels.size() + " models registered");
    }

	private static List<String> retrieveServerModels(HttpClientWrapper httpClient, String baseUrl, String apiKey)
			throws Exception {
		
		// NOTE: We need to try both endpoints, as ik_llama.cpp returns 404 for the "/models" endpoint.
        String[] endpointPaths = {Constants.MODELS_ENDPOINT, Constants.V1_PREFIX + Constants.MODELS_ENDPOINT};
        
        Map<String, Integer> failedAttempts = new LinkedHashMap<>();
        
        for (String path : endpointPaths) {
            String url = baseUrl + path;
            URI uri = URI.create(url);
            HttpResponse<InputStream> response = httpClient.sendRequest(uri, apiKey, null, false);
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

    private static List<String> filterAllowedModels(List<String> models, List<String> allow) {
        if (allow == null || allow.isEmpty()) return models;
        
        Set<String> allowed = new HashSet<>();
        List<String> hidden = new ArrayList<>();
        
        // Hidden models are prefixed with * and should be added regardless of whether they appear in the models list.
        // Example: "*accounts/fireworks/routers/kimi-k2p5-turbo" is a hidden model for Fireworks' "Fire Pass" plan.
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