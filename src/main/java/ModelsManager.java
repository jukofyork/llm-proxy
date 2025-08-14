import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages model discovery and routing by fetching fresh model data on each request.
 */
public class ModelsManager {

	public record ModelConfig(String endpoint, String displayName, String apiKey) {
	}

	private static final Map<String, ModelConfig> modelBackends = new ConcurrentHashMap<>();
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private static Map<String, ConfigurationManager.ServerConfig> serverConfigs = new ConcurrentHashMap<>();

	/**
	 * Private constructor to prevent instantiation of the class.
	 */
	private ModelsManager() {
	}

	/**
	 * Initializes the ModelsManager with server configurations.
	 * 
	 * @param configs Map of server configurations from ConfigurationManager
	 * @return true if initialization was successful, false otherwise
	 */
	public static boolean initialize(Map<String, ConfigurationManager.ServerConfig> configs) {
		serverConfigs = new ConcurrentHashMap<>(configs);
		return true;
	}

	/**
	 * Generates models response for /models endpoint by fetching fresh data from
	 * all servers.
	 * 
	 * @return JSON string containing all available models
	 */
	public static String generateModelsResponse() {
		fetchAllAvailableModels();

		try {
			List<Map<String, String>> models = modelBackends.keySet().stream()
					.map(modelId -> Map.of("id", modelId, "object", "model")).toList();

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
	public static ModelConfig findModelConfigForRequest(String method, String path, String body,
			Map<String, String> headers) {
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
	 * Fetches available models from all configured servers in parallel.
	 */
	private static void fetchAllAvailableModels() {
		Logger.info("Fetching models from configured servers...");

		modelBackends.clear();
		HttpClientWrapper httpClient = new HttpClientWrapper(Constants.MODEL_CONNECTION_TIMEOUT,
				Constants.MODEL_REQUEST_TIMEOUT);

		List<CompletableFuture<Void>> fetchTasks = new ArrayList<>();

		for (Map.Entry<String, ConfigurationManager.ServerConfig> entry : serverConfigs.entrySet()) {
			String serverName = entry.getKey();
			ConfigurationManager.ServerConfig config = entry.getValue();

			CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
				try {
					List<String> models = fetchModelsFromServer(httpClient, config.endpoint(), config.apiKey());
					models = filterModelsByAllowedList(models, config.allowedModels());

					updateModelBackendsForServer(serverName, models, config);

					Logger.info("Server " + serverName + " provides " + models.size() + " models");

				} catch (Exception e) {
					Logger.warning("Failed to fetch models from " + serverName, e);
				}
			});

			fetchTasks.add(task);
		}

		// Wait for all fetches to complete
		try {
			CompletableFuture.allOf(fetchTasks.toArray(new CompletableFuture[0]))
					.get(Constants.MODEL_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		} catch (Exception e) {
			Logger.warning("Some model fetches did not complete in time", e);
		}

		Logger.info("Proxy provides " + modelBackends.size() + " models: " + modelBackends.keySet());
	}

	/**
	 * Updates the global model backends map for a specific server.
	 */
	private static void updateModelBackendsForServer(String serverName, List<String> models,
			ConfigurationManager.ServerConfig config) {
		for (String modelName : models) {
			modelBackends.put(modelName, new ModelConfig(config.endpoint(), modelName, config.apiKey()));
		}
	}

	/**
	 * Fetches available models from a specific server endpoint.
	 */
	private static List<String> fetchModelsFromServer(HttpClientWrapper httpClient, String baseUrl, String apiKey)
			throws Exception {
		String modelsUrl = baseUrl + Constants.MODELS_ENDPOINT;
		URI modelsUri = URI.create(modelsUrl);

		HttpResponse<InputStream> response = httpClient.sendRequest(modelsUri, apiKey, null, false);

		if (response.statusCode() != 200) {
			throw new Exception("HTTP " + response.statusCode() + ": "
					+ new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
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
	 * Extracts model name from request based on endpoint type.
	 */
	private static String extractModelNameFromRequest(String path, String body, Map<String, String> headers) {
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
	 */
	private static boolean isValidEndpointForModel(String requestPath, String modelEndpoint) {
		if (!requestPath.startsWith(Constants.V1_PREFIX) && modelEndpoint.endsWith(Constants.V1_PREFIX)) {
			return false;
		}
		return true;
	}
}