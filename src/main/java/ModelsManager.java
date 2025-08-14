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
     * Finds the appropriate model configuration for the request, based on model name
     * extracted from the request. If the model is not found or the endpoint is
     * invalid, returns null.
     *
     * @param method  the HTTP method
     * @param path    the requested path
     * @param body    the request body
     * @param headers the request headers
     * @return a ModelConfig matching the request, or null if not found
     */
    public static ModelConfig findModelConfigForRequest(String method, String path, String body,
            Map<String, String> headers) {
        String modelName = extractRequestedModelName(path, body, headers);
        if (modelName == null) {
            return null;
        }

        ModelConfig config = registeredModels.get(modelName);
        if (config == null) {
            Logger.warning("Model '" + modelName + "' not found");
            return null;
        }

        if (!validateModelEndpoint(path, config.endpoint())) {
            Logger.warning("Model '" + modelName + "' endpoint mismatch");
            return null;
        }

        return config;
    }

    /**
     * Determines if the request expects a streaming response by checking the "stream"
     * field in the JSON body. If "stream" is set to false, then streaming is
     * disabled.
     *
     * @param body the request body
     * @return true if request should stream responses, false otherwise
     */
    public static boolean determineIfStreamingRequest(String body) {
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

        // Atomically replace the registered models with the newly constructed snapshot
        registeredModels.clear();
        registeredModels.putAll(tempModels);
        lastRefreshTime = Instant.now();

        Logger.info("Proxy provides " + registeredModels.size() + " models: " + registeredModels.keySet());
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

    /**
     * Extracts the model name from the request based on endpoint type. For standard
     * OpenAI-style calls, reads "model" from the JSON body. For llama.cpp-compatibility,
     * falls back to the Authorization header containing a Bearer token matching the
     * model name.
     *
     * @param path    the request path
     * @param body    the request body
     * @param headers the HTTP request headers
     * @return the requested model name, or null if unavailable
     */
    private static String extractRequestedModelName(String path, String body, Map<String, String> headers) {
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
     * Validates if the request path is compatible with the model's endpoint.
     *
     * @param requestPath   the incoming request path
     * @param modelEndpoint the model's configured endpoint
     * @return true if valid, false otherwise
     */
    private static boolean validateModelEndpoint(String requestPath, String modelEndpoint) {
        if (!requestPath.startsWith(Constants.V1_PREFIX) && modelEndpoint.endsWith(Constants.V1_PREFIX)) {
            return false;
        }
        return true;
    }
}