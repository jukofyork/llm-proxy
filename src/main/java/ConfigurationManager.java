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

    public record ServerConfig(String endpoint, String apiKey, List<String> allowedModels) {}

    private static final Map<String, ServerConfig> serverConfigs = new HashMap<>();

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private ConfigurationManager() {
    }

    /**
     * Initializes the configuration by loading TOML file.
     * 
     * @return true if initialization was successful, false otherwise
     */
    public static boolean initialize() {
        return loadConfiguration();
    }

    /**
     * Returns the server configurations mapping.
     * 
     * @return Map of server names to their configurations
     */
    public static Map<String, ServerConfig> getServerConfigs() {
        return Collections.unmodifiableMap(serverConfigs);
    }

    /**
     * Loads server configuration from TOML file.
     * 
     * @return true if configuration loaded successfully, false otherwise
     */
    private static boolean loadConfiguration() {
        try {
            if (!Files.exists(Paths.get(Constants.CONFIG_FILE))) {
                Logger.error("Configuration file not found: " + Constants.CONFIG_FILE);
                return false;
            }

            String tomlContent = Files.readString(Paths.get(Constants.CONFIG_FILE), StandardCharsets.UTF_8);
            ObjectMapper tomlMapper = new ObjectMapper(new TomlFactory());
            JsonNode configRoot = tomlMapper.readTree(tomlContent);

            configRoot.fields().forEachRemaining(entry -> 
                processServerConfiguration(entry.getKey(), entry.getValue()));
            
            Logger.info("Configuration loaded successfully");
            return true;
        } catch (Exception e) {
            Logger.error("Failed to load configuration", e);
            return false;
        }
    }

    /**
     * Processes individual server configuration from TOML.
     */
    private static void processServerConfiguration(String serverName, JsonNode serverNode) {
        String baseEndpoint = serverNode.get("endpoint").asText();
        String apiKey = serverNode.has("api_key") ? serverNode.get("api_key").asText() : null;
        List<String> allowedModels = extractAllowedModels(serverNode);

        if (serverNode.has("ports")) {
            processMultiPortConfiguration(serverName, baseEndpoint, apiKey, allowedModels, serverNode.get("ports"));
        } else {
            addServerConfiguration(serverName, baseEndpoint, apiKey, allowedModels);
        }
    }

    /**
     * Extracts allowed models list from server configuration.
     */
    private static List<String> extractAllowedModels(JsonNode serverNode) {
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
     * Processes configuration for servers with multiple ports.
     */
    private static void processMultiPortConfiguration(String serverName, String baseEndpoint, 
            String apiKey, List<String> allowedModels, JsonNode portsNode) {
        for (JsonNode portNode : portsNode) {
            int port = portNode.asInt();
            String fullEndpoint = buildEndpointWithPort(baseEndpoint, port);
            String configName = serverName + "-" + port;
            addServerConfiguration(configName, fullEndpoint, apiKey, allowedModels);
        }
    }

    /**
     * Adds a server configuration to the registry.
     */
    private static void addServerConfiguration(String name, String endpoint, String apiKey, List<String> allowedModels) {
        serverConfigs.put(name, new ServerConfig(endpoint, apiKey, allowedModels));
        String modelInfo = allowedModels != null ? 
            " (filtered: " + allowedModels.size() + " models)" : " (all models)";
        Logger.info("Loaded server: " + name + " -> " + endpoint + modelInfo);
    }

    /**
     * Builds endpoint URL with specified port.
     */
    private static String buildEndpointWithPort(String endpoint, int port) {
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