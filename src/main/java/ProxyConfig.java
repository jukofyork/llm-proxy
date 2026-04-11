import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Raw configuration model loaded directly from TOML.
 * No legacy/adapter logic. Minimal, explicit schema.
 */
public class ProxyConfig {

    public Map<String, Server> servers;

    /**
     * Server configuration loaded from TOML.
     * Contains connection settings, authentication, model filtering, and profiles.
     */
    public static class Server {
        public List<String> endpoints;
        public Auth auth;
        public Models models;
        public Params params;
        public Pool pool;
        public Map<String, Profile> profiles;
    }

    /**
     * Authentication configuration for a server.
     * Supports "none" (no auth) or "bearer" (API key) authentication types.
     */
    public static class Auth {
        public String type;       // "none" | "bearer"
        public String api_key;    // required for "bearer"
    }

    /**
     * Model allow-list configuration.
     * If specified, only listed models are exposed by this server.
     */
    public static class Models {
        public List<String> allow; // optional allow-list
    }

    /**
     * Parameter configuration with defaults and policy rules.
     */
    public static class Params {
        public JsonNode defaults; // object node or null
        public Policy policy;
    }

    /**
     * Policy configuration for parameter filtering.
     * Mode can be "deny" (remove specified paths) or "allow" (future: keep only specified).
     */
    public static class Policy {
        public String mode;       // "deny" (supported), "allow" (future)
        public List<String> paths; // JSON Pointers, e.g. "/stream", "/stream_options/include_usage"
    }

    /**
     * Connection pool configuration for load balancing strategies.
     */
    public static class Pool {
        public String strategy;   // "round_robin" (default)
    }

    /**
     * Profile (virtual model) configuration.
     * Defines suffix-based virtual models that apply overrides to base models.
     */
    public static class Profile {
        public String suffix;       // required
        public JsonNode overrides;  // object node or null
    }
}