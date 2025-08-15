import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads, validates and compiles TOML configuration into RuntimeConfig.
 * Minimal schema:
 *
 * [ServerName]
 * endpoint = "https://..."                 # single endpoint per server (required)
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

            // Endpoint (single)
            String endpoint = getText(serverObj, "endpoint");
            if (endpoint == null || endpoint.isEmpty()) {
                throw new IllegalArgumentException("Server [" + serverName + "] must define 'endpoint' as a string");
            }
            validateUrl(endpoint, "Server [" + serverName + "] endpoint");

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
                "endpoint",
                "api_key", "models", "defaults", "overrides",
                "deny", "hide_base_models"
            );

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
                endpoint,
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
}