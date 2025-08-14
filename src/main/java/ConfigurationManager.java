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
    public record ServerConfig(String endpoint, String apiKey, List<String> allowedModels) {}

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

            configRoot.fields().forEachRemaining(entry ->
                parseServerConfiguration(entry.getKey(), entry.getValue())
            );

            Logger.info("Configuration loaded successfully");
            return true;
        } catch (Exception e) {
            Logger.error("Failed to load configuration", e);
            return false;
        }
    }

    /**
     * Parses the server configuration for a single server name entry.
     *
     * @param serverName the logical name of the server
     * @param serverNode the JSON node corresponding to that server's details
     */
    private static void parseServerConfiguration(String serverName, JsonNode serverNode) {
        String baseEndpoint = serverNode.get("endpoint").asText();
        String apiKey = serverNode.has("api_key") ? serverNode.get("api_key").asText() : null;
        List<String> allowedModels = parseAllowedModels(serverNode);

        if (serverNode.has("ports")) {
            parseMultiPortServers(serverName, baseEndpoint, apiKey, allowedModels, serverNode.get("ports"));
        } else {
            registerServer(serverName, baseEndpoint, apiKey, allowedModels);
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
     * Parses server configurations for multiple port entries.
     *
     * @param serverName    the base name of the server
     * @param baseEndpoint  the base endpoint URL
     * @param apiKey        the API key for this server
     * @param allowedModels an optional list of allowed models
     * @param portsNode     the JSON node containing port numbers
     */
    private static void parseMultiPortServers(
            String serverName,
            String baseEndpoint,
            String apiKey,
            List<String> allowedModels,
            JsonNode portsNode
    ) {
        for (JsonNode portNode : portsNode) {
            int port = portNode.asInt();
            String fullEndpoint = constructEndpointWithPort(baseEndpoint, port);
            String configName = serverName + "-" + port;
            registerServer(configName, fullEndpoint, apiKey, allowedModels);
        }
    }

    /**
     * Registers a single server configuration.
     *
     * @param name          the name of the server
     * @param endpoint      the endpoint URL
     * @param apiKey        the API key (if any)
     * @param allowedModels an optional list of allowed models
     */
    private static void registerServer(String name, String endpoint, String apiKey, List<String> allowedModels) {
        serverRegistry.put(name, new ServerConfig(endpoint, apiKey, allowedModels));
        String modelInfo = allowedModels != null
                ? " (filtered: " + allowedModels.size() + " models)"
                : " (all models)";
        Logger.info("Loaded server: " + name + " -> " + endpoint + modelInfo);
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