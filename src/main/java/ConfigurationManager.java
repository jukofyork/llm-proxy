import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;

/**
 * Manages server configuration loading from TOML files.
 * Handles TOML configuration parsing and server endpoint management.
 */
public class ConfigurationManager {

    /**
     * Holds relevant server configuration details.
     */
    public record ServerConfig(String endpoint, String apiKey, List<String> allowedModels, JsonNode params, List<String> allowedParams, List<String> disallowedParams, String virtualEndpointFor) {}

    private static final Map<String, ServerConfig> serverRegistry = new HashMap<>();

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private ConfigurationManager() {
    }

    /**
     * Initializes the configuration by loading the TOML file.
     *
     * @return true if initialization was successful, false otherwise
     */
    public static boolean initialize() {
        return loadAllConfigurations();
    }

    /**
     * Returns an unmodifiable map of server names to their configurations.
     *
     * @return Map of server names to their ServerConfig objects
     */
    public static Map<String, ServerConfig> getServerConfigs() {
        return Collections.unmodifiableMap(serverRegistry);
    }

    /**
     * Loads all server configurations from the TOML file.
     *
     * @return true if configuration loaded successfully, false otherwise
     */
    private static boolean loadAllConfigurations() {
        try {
            if (!Files.exists(Paths.get(Constants.CONFIG_FILE))) {
                Logger.error("Configuration file not found: " + Constants.CONFIG_FILE);
                return false;
            }

            String tomlContent = Files.readString(Paths.get(Constants.CONFIG_FILE), StandardCharsets.UTF_8);
            ObjectMapper tomlMapper = new ObjectMapper(new TomlFactory());
            JsonNode configRoot = tomlMapper.readTree(tomlContent);

            // First pass: load all configs
            configRoot.fields().forEachRemaining(entry ->
                parseServerConfiguration(entry.getKey(), entry.getValue())
            );

            // Second pass: detect virtual endpoints
            detectVirtualEndpoints();

            Logger.info("Configuration loaded successfully");
            return true;
        } catch (Exception e) {
            Logger.error("Failed to load configuration", e);
            return false;
        }
    }

    /**
     * Detects and configures virtual endpoints based on naming patterns.
     */
    private static void detectVirtualEndpoints() {
        List<String> baseServers = serverRegistry.entrySet().stream()
            .filter(entry -> entry.getValue().endpoint() != null)
            .map(Map.Entry::getKey)
            .toList();

        for (Map.Entry<String, ServerConfig> entry : serverRegistry.entrySet()) {
            String configName = entry.getKey();
            ServerConfig config = entry.getValue();

            // Skip if already has endpoint (is a base server)
            if (config.endpoint() != null) continue;

            // Find longest matching base server
            String baseServer = findLongestMatchingBaseServer(configName, baseServers);
            if (baseServer != null) {
                ConfigurationManager.ServerConfig baseConfig = serverRegistry.get(baseServer);
                if (baseConfig == null || baseConfig.endpoint() == null) {
                    Logger.warning("Virtual endpoint " + configName + " references non-existent base server: " + baseServer);
                    continue;
                }
                // Update config to mark as virtual
                ServerConfig updatedConfig = new ServerConfig(
                    null, config.apiKey(), config.allowedModels(), config.params(),
                    config.allowedParams(), config.disallowedParams(), baseServer
                );
                serverRegistry.put(configName, updatedConfig);
                Logger.info("Detected virtual endpoint: " + configName + " -> " + baseServer);
            }
        }
    }

    /**
     * Finds the longest matching base server name for a given config name.
     *
     * @param configName the name of the configuration
     * @param baseServers list of base server names
     * @return the longest matching base server name, or null if none found
     */
    private static String findLongestMatchingBaseServer(String configName, List<String> baseServers) {
        return baseServers.stream()
            .filter(base -> configName.startsWith(base + "-"))
            .max(Comparator.comparing(String::length))
            .orElse(null);
    }

    /**
     * Parses the server configuration for a single server name entry.
     *
     * @param serverName the logical name of the server
     * @param serverNode the JSON node corresponding to that server's details
     */
    private static void parseServerConfiguration(String serverName, JsonNode serverNode) {
        String baseEndpoint = serverNode.has("endpoint") ? serverNode.get("endpoint").asText() : null;
        String apiKey = serverNode.has("api_key") ? serverNode.get("api_key").asText() : null;
        List<String> allowedModels = parseAllowedModels(serverNode);
        JsonNode params = parseParamOverrides(serverNode);
        List<String> allowedParams = parseParamList(serverNode, "allowed_params");
        List<String> disallowedParams = parseParamList(serverNode, "disallowed_params");

        if (serverNode.has("ports") && baseEndpoint != null) {
            parseMultiPortServers(serverName, baseEndpoint, apiKey, allowedModels, serverNode.get("ports"), params, allowedParams, disallowedParams);
        } else {
            registerServer(serverName, baseEndpoint, apiKey, allowedModels, params, allowedParams, disallowedParams);
        }
    }

    /**
     * Parses the allowed models from a server configuration node.
     *
     * @param serverNode the JSON node for the server configuration
     * @return a list of allowed model names, or null if no models field is present
     */
    private static List<String> parseAllowedModels(JsonNode serverNode) {
        if (!serverNode.has("models")) {
            return null;
        }

        List<String> models = new ArrayList<>();
        for (JsonNode modelNode : serverNode.get("models")) {
            models.add(modelNode.asText());
        }
        return models;
    }

    /**
     * Parses parameter overrides from a server configuration node.
     *
     * @param serverNode the JSON node for the server configuration
     * @return a JsonNode containing parameter overrides, or null if no params field is present
     */
    private static JsonNode parseParamOverrides(JsonNode serverNode) {
        return serverNode.has("params") ? serverNode.get("params") : null;
    }

    /**
     * Parses a parameter list from a server configuration node.
     *
     * @param serverNode the JSON node for the server configuration
     * @param fieldName the name of the field to parse (e.g., "allowed_params", "disallowed_params")
     * @return a list of parameter paths, or null if the field is not present
     */
    private static List<String> parseParamList(JsonNode serverNode, String fieldName) {
        if (!serverNode.has(fieldName)) {
            return null;
        }

        List<String> params = new ArrayList<>();
        for (JsonNode paramNode : serverNode.get(fieldName)) {
            params.add(paramNode.asText());
        }
        return params;
    }

    /**
     * Parses server configurations for multiple port entries.
     *
     * @param serverName       the base name of the server
     * @param baseEndpoint     the base endpoint URL
     * @param apiKey           the API key for this server
     * @param allowedModels    an optional list of allowed models
     * @param portsNode        the JSON node containing port numbers
     * @param params           parameter overrides for this server
     * @param allowedParams    parameters allowed to pass through (whitelist)
     * @param disallowedParams parameters to block (blacklist)
     */
    private static void parseMultiPortServers(
            String serverName,
            String baseEndpoint,
            String apiKey,
            List<String> allowedModels,
            JsonNode portsNode,
            JsonNode params,
            List<String> allowedParams,
            List<String> disallowedParams
    ) {
        for (JsonNode portNode : portsNode) {
            int port = portNode.asInt();
            String fullEndpoint = constructEndpointWithPort(baseEndpoint, port);
            String configName = serverName + "-" + port;
            registerServer(configName, fullEndpoint, apiKey, allowedModels, params, allowedParams, disallowedParams);
        }
    }

    /**
     * Registers a single server configuration.
     *
     * @param name             the name of the server
     * @param endpoint         the endpoint URL (null for virtual endpoints)
     * @param apiKey           the API key (if any)
     * @param allowedModels    an optional list of allowed models
     * @param params           parameter overrides for this server
     * @param allowedParams    parameters allowed to pass through (whitelist)
     * @param disallowedParams parameters to block (blacklist)
     */
    private static void registerServer(String name, String endpoint, String apiKey, List<String> allowedModels, JsonNode params, List<String> allowedParams, List<String> disallowedParams) {
        serverRegistry.put(name, new ServerConfig(endpoint, apiKey, allowedModels, params, allowedParams, disallowedParams, null));
        
        if (endpoint != null) {
            String modelInfo = allowedModels != null
                    ? " (filtered: " + allowedModels.size() + " models)"
                    : " (all models)";
            int paramsCount = (params != null && params.isObject()) ? params.size() : 0;
            String paramsInfo = paramsCount > 0 ? " (params: " + paramsCount + " overrides)" : "";
            int allowedCount = allowedParams != null ? allowedParams.size() : 0;
            int disallowedCount = disallowedParams != null ? disallowedParams.size() : 0;
            String filterInfo = "";
            if (allowedCount > 0) {
                filterInfo += " (allow: " + allowedCount + " params)";
            }
            if (disallowedCount > 0) {
                filterInfo += " (block: " + disallowedCount + " params)";
            }
            Logger.info("Loaded server: " + name + " -> " + endpoint + modelInfo + paramsInfo + filterInfo);
        }
    }

    /**
     * Constructs an endpoint URL with a specified port.
     *
     * @param endpoint the base endpoint URL
     * @param port     the port to include
     * @return a string representing the new endpoint URL with the specified port
     */
    private static String constructEndpointWithPort(String endpoint, int port) {
        try {
            URL url = new URL(endpoint);
            return new URL(url.getProtocol(), url.getHost(), port, url.getFile()).toString();
        } catch (Exception e) {
            int pathIndex = endpoint.indexOf("/", 8);
            String baseUrl = pathIndex > 0 ? endpoint.substring(0, pathIndex) : endpoint;
            String path = pathIndex > 0 ? endpoint.substring(pathIndex) : "";
            return baseUrl + ":" + port + path;
        }
    }
}