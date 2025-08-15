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

    private ModelsManager() {}

    public static boolean initialize(RuntimeConfig runtime) {
        runtimeConfig = runtime;
        return true;
    }

    public static String generateModelsResponse() {
        refreshRegisteredModels();

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

    private static void refreshRegisteredModels() {
        if (Instant.now().isBefore(lastRefreshTime.plus(Constants.MODEL_REFRESH_TTL))) {
            return;
        }

        Map<String, ModelConfig> temp = new ConcurrentHashMap<>();
        HttpClientWrapper httpClient = new HttpClientWrapper(
                Constants.MODEL_CONNECTION_TIMEOUT,
                Constants.MODEL_REQUEST_TIMEOUT
        );

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (RuntimeConfig.CompiledServer server : runtimeConfig.serversByName.values()) {
            // Choose a representative endpoint for model listing (assumed identical across pool)
            String endpoint = server.endpoints.get(0);
            String apiKey = "bearer".equals(server.authType) ? server.apiKey : null;

            CompletableFuture<Void> t = CompletableFuture.runAsync(() -> {
                try {
                    List<String> models = retrieveServerModels(httpClient, endpoint, apiKey);
                    models = filterAllowedModels(models, server.modelAllowList);

                    // Register base models
                    for (String m : models) {
                        temp.put(m, new ModelConfig(endpoint, m, apiKey));
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

        registeredModels.clear();
        registeredModels.putAll(temp);
        lastRefreshTime = Instant.now();
    }

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
        Set<String> allowSet = new HashSet<>(allow);
        return models.stream().filter(allowSet::contains).toList();
    }
}