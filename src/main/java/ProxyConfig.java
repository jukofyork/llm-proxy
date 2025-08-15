import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Raw configuration model loaded directly from TOML.
 * No legacy/adapter logic. Minimal, explicit schema.
 */
public class ProxyConfig {

    public Map<String, Server> servers;

    public static class Server {
        public List<String> endpoints;
        public Auth auth;
        public Models models;
        public Params params;
        public Pool pool;
        public Map<String, Profile> profiles;
    }

    public static class Auth {
        public String type;       // "none" | "bearer"
        public String api_key;    // required for "bearer"
    }

    public static class Models {
        public List<String> allow; // optional allow-list
    }

    public static class Params {
        public JsonNode defaults; // object node or null
        public Policy policy;
    }

    public static class Policy {
        public String mode;       // "deny" (supported), "allow" (future)
        public List<String> paths; // JSON Pointers, e.g. "/stream", "/stream_options/include_usage"
    }

    public static class Pool {
        public String strategy;   // "round_robin" (default)
    }

    public static class Profile {
        public String suffix;       // required
        public JsonNode overrides;  // object node or null
    }
}