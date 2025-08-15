// src/main/java/ConfigLoader.java
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;

/**
 * Loads, validates and compiles TOML configuration into RuntimeConfig.
 * New minimal schema:
 *
 * [ServerName]
 * endpoints = ["https://..."]              # or: endpoint = "http://host", ports = [8080,8081]
 * api_key = "sk-..."                       # optional; if present => Bearer auth, else no auth
 * models = ["id1","id2"]                   # optional allow-list
 * defaults = { ... }                       # object; applied if missing
 * overrides = { ... }                      # object; force-set (override)
 * deny = ["/temperature","/nested/key"]    # optional JSON Pointers
 * hide_base_models = true                  # optional; default false; if true, only profile (virtual) models are listed
 *
 * [ServerName.profileSuffix]
 * defaults = { ... }
 * overrides = { ... }
 * deny = ["/..."]
 */
public class ConfigLoader {

    private static final ObjectMapper TOML = new ObjectMapper(new TomlFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    public static RuntimeConfig load(String tomlPath) {
        try {
            String content = Files.readString(Path.of(tomlPath), StandardCharsets.UTF_8);
            JsonNode root = TOML.readTree(content);

            RuntimeConfig compiled = compile(root);
            Logger.info("Configuration loaded successfully");
            return compiled;
        } catch (IOException e) {
            Logger.error("Failed to read configuration: " + tomlPath, e);
            return null;
        } catch (Exception e) {
            Logger.error("Invalid configuration", e);
            return null;
        }
    }

    private static RuntimeConfig compile(JsonNode root) {
        if (root == null || !root.isObject() || !root.fields().hasNext()) {
            throw new IllegalArgumentException("Empty configuration");
        }

        Map<String, RuntimeConfig.CompiledServer> servers = new HashMap<>();

        root.fields().forEachRemaining(entry -> {
            String serverName = entry.getKey();
            JsonNode serverNode = entry.getValue();

            if (!serverNode.isObject()) {
                throw new IllegalArgumentException("Section [" + serverName + "] must be a table/object");
            }

            ObjectNode serverObj = (ObjectNode) serverNode;

            // Endpoints
            List<String> endpoints = buildEndpoints(serverName, serverObj);
            if (endpoints.isEmpty()) {
                throw new IllegalArgumentException("Server [" + serverName + "] must define at least one endpoint");
            }
            endpoints.forEach(url -> validateUrl(url, "Server [" + serverName + "] endpoint"));

            // Auth
            String apiKey = getText(serverObj, "api_key");
            String authType = (apiKey != null && !apiKey.isEmpty()) ? "bearer" : "none";

            // Models allow-list
            List<String> modelsAllow = getStringArray(serverObj.get("models"));

            // Params: defaults, overrides, deny
            ObjectNode defaults = objectOrEmpty(serverObj.get("defaults"));
            ObjectNode overrides = objectOrEmpty(serverObj.get("overrides"));
            List<String> deny = compileDeny(serverObj);

            // Profiles: any nested object fields that are not recognized server-level keys
            Map<String, RuntimeConfig.CompiledProfile> profiles = new HashMap<>();
            Set<String> reserved = Set.of(
                    "endpoints", "endpoint", "port", "ports",
                    "api_key", "models", "defaults", "overrides",
                    "deny", "hide_base_models");

            serverObj.fields().forEachRemaining(fe -> {
                String key = fe.getKey();
                JsonNode val = fe.getValue();
                if (reserved.contains(key)) return;
                if (!val.isObject()) return; // ignore non-object unexpected things gracefully

                String suffix = key; // suffix deduced from name (e.g., [OpenAI.fast] -> "fast")
                ObjectNode profObj = (ObjectNode) val;
                ObjectNode pDefaults = objectOrEmpty(profObj.get("defaults"));
                ObjectNode pOverrides = objectOrEmpty(profObj.get("overrides"));
                List<String> pDeny = compileDeny(profObj);

                profiles.put(suffix, new RuntimeConfig.CompiledProfile(suffix, pDefaults, pOverrides, pDeny));
            });

            boolean hideBaseModels = getBoolean(serverObj, "hide_base_models", false);

            RuntimeConfig.CompiledServer compiled = new RuntimeConfig.CompiledServer(
                    serverName,
                    endpoints,
                    authType,
                    apiKey,
                    modelsAllow,
                    defaults,
                    overrides,
                    deny,
                    profiles,
                    hideBaseModels
            );

            servers.put(serverName, compiled);
        });

        return new RuntimeConfig(servers);
    }

    private static List<String> buildEndpoints(String serverName, ObjectNode serverObj) {
        List<String> result = new ArrayList<>();

        JsonNode endpointsNode = serverObj.get("endpoints");
        JsonNode endpointNode = serverObj.get("endpoint");
        JsonNode portsNode = serverObj.get("ports");
        JsonNode portNode = serverObj.get("port");

        if (endpointsNode != null && endpointsNode.isArray()) {
            List<String> bases = getStringArray(endpointsNode);
            if (portNode != null && portNode.isInt()) {
                int commonPort = portNode.asInt();
                for (String base : bases) {
                    result.add(applyPort(base, commonPort));
                }
            } else {
                result.addAll(bases);
            }
        } else if (endpointNode != null && endpointNode.isTextual()) {
            String base = endpointNode.asText();
            if (portsNode != null && portsNode.isArray() && portsNode.size() > 0) {
                for (JsonNode p : portsNode) {
                    if (!p.isInt()) {
                        throw new IllegalArgumentException("Server [" + serverName + "] ports must be integers");
                    }
                    result.add(applyPort(base, p.asInt()));
                }
            } else {
                result.add(base);
            }
        }

        return result;
    }

    private static String getText(ObjectNode obj, String field) {
        JsonNode n = obj.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }

    private static boolean getBoolean(ObjectNode obj, String field, boolean defaultVal) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) return defaultVal;
        if (!n.isBoolean()) throw new IllegalArgumentException("Expected boolean for '" + field + "' but got: " + n.getNodeType());
        return n.asBoolean();
    }

    private static List<String> getStringArray(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isArray()) throw new IllegalArgumentException("Expected array of strings: " + node);
        List<String> out = new ArrayList<>();
        for (JsonNode el : node) {
            if (!el.isTextual()) throw new IllegalArgumentException("Expected array of strings, got: " + el);
            out.add(el.asText());
        }
        return out;
    }

    private static ObjectNode objectOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.objectNode();
        if (node.isObject()) return (ObjectNode) node;
        throw new IllegalArgumentException("Expected object but got: " + node.getNodeType());
    }

    private static List<String> compileDeny(ObjectNode obj) {
        List<String> out = new ArrayList<>();
        // preferred: "deny"
        JsonNode deny = obj.get("deny");
        if (deny != null && !deny.isNull()) {
            if (!deny.isArray()) throw new IllegalArgumentException("'deny' must be an array of JSON Pointers");
            for (JsonNode el : deny) {
                if (!el.isTextual()) throw new IllegalArgumentException("'deny' must be array of strings");
                out.add(toPointer(el.asText()));
            }
        }
        return List.copyOf(out);
    }

    private static String toPointer(String s) {
        if (s == null || s.isEmpty()) return "/";
        if (s.startsWith("/")) return s;
        // support dot-path "a.b.c" and simple "temperature"
        String[] parts = s.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append('/').append(escapePointerToken(p));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String escapePointerToken(String t) {
        return t.replace("~", "~0").replace("/", "~1");
    }

    private static void validateUrl(String url, String label) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(label + " invalid: " + url);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " invalid: " + url);
        }
    }

    private static String applyPort(String baseUrl, int port) {
        try {
            URI u = URI.create(baseUrl);
            String scheme = u.getScheme();
            String host = u.getHost();
            String path = u.getRawPath();
            String query = u.getRawQuery();
            String fragment = u.getRawFragment();

            if (scheme == null || host == null) return fallbackPortAppend(baseUrl, port);

            URI rebuilt = new URI(
                    scheme,
                    null,
                    host,
                    port,
                    path != null ? path : "",
                    query,
                    fragment
            );
            return rebuilt.toString();
        } catch (Exception e) {
            return fallbackPortAppend(baseUrl, port);
        }
    }

    private static String fallbackPortAppend(String url, int port) {
        int idx = url.indexOf("/", 8);
        String base = idx > 0 ? url.substring(0, idx) : url;
        String path = idx > 0 ? url.substring(idx) : "";
        return base + ":" + port + path;
    }
}