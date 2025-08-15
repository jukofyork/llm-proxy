import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compiled, validated, immutable runtime configuration used by the proxy.
 */
public class RuntimeConfig {

    public static final class CompiledProfile {
        public final String suffix;
        public final ObjectNode defaults;   // applied if missing
        public final ObjectNode overrides;  // force-set (override)
        public final List<String> deny;     // JSON Pointers

        public CompiledProfile(String suffix, ObjectNode defaults, ObjectNode overrides, List<String> deny) {
            this.suffix = suffix;
            this.defaults = defaults;
            this.overrides = overrides;
            this.deny = deny != null ? List.copyOf(deny) : List.of();
        }
    }

    public static final class CompiledServer {
        public final String name;
        public final List<String> endpoints;
        public final String authType;               // "none" | "bearer"
        public final String apiKey;                 // for "bearer"
        public final List<String> modelAllowList;   // nullable
        public final ObjectNode paramDefaults;      // never null
        public final ObjectNode paramOverrides;     // never null
        public final List<String> denyParamPointers;// never null
        public final Map<String, CompiledProfile> profilesBySuffix; // never null
        public final boolean hideBaseModels;        // if true, base models are not listed (only profiles)
        private final AtomicInteger rrCounter = new AtomicInteger(0);

        public CompiledServer(
                String name,
                List<String> endpoints,
                String authType,
                String apiKey,
                List<String> modelAllowList,
                ObjectNode paramDefaults,
                ObjectNode paramOverrides,
                List<String> denyParamPointers,
                Map<String, CompiledProfile> profilesBySuffix,
                boolean hideBaseModels) {
            this.name = name;
            this.endpoints = List.copyOf(endpoints);
            this.authType = authType;
            this.apiKey = apiKey;
            this.modelAllowList = modelAllowList != null ? List.copyOf(modelAllowList) : null;
            this.paramDefaults = paramDefaults;
            this.paramOverrides = paramOverrides;
            this.denyParamPointers = List.copyOf(denyParamPointers);
            this.profilesBySuffix = Map.copyOf(profilesBySuffix);
            this.hideBaseModels = hideBaseModels;
        }

        public String nextEndpoint() {
            if (endpoints.isEmpty()) return null;
            int idx = Math.abs(rrCounter.getAndIncrement());
            return endpoints.get(idx % endpoints.size());
        }
    }

    public final Map<String, CompiledServer> serversByName;

    public RuntimeConfig(Map<String, CompiledServer> serversByName) {
        this.serversByName = Map.copyOf(serversByName);
    }
}