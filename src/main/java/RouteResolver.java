import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Resolves a requested model name into routing details using RuntimeConfig and ModelsManager registry.
 * Combines server-level and profile-level (virtual) defaults/overrides/deny.
 */
public class RouteResolver {

    private final RuntimeConfig runtime;

    public RouteResolver(RuntimeConfig runtime) {
        this.runtime = runtime;
    }

    public RouteTarget resolve(String requestedModel) {
        ModelsManager.ModelConfig modelCfg = ModelsManager.getModelConfig(requestedModel);
        if (modelCfg == null) {
            return null;
        }

        RuntimeConfig.CompiledServer server = findServerByEndpoint(modelCfg.endpoint());
        if (server == null) {
            Logger.warning("No server config found for endpoint: " + modelCfg.endpoint());
            return new RouteTarget(
                    modelCfg.endpoint(),
                    modelCfg.apiKey(),
                    false,
                    requestedModel,
                    List.of(),
                    emptyObject(),
                    emptyObject()
            );
        }

        String chosenEndpoint = server.endpoint;

        // Detect virtual profile by suffix
        String baseModelName = requestedModel;
        boolean isVirtual = false;
        RuntimeConfig.CompiledProfile profile = null;
        for (String suffix : server.profilesBySuffix.keySet()) {
            String tag = "-" + suffix;
            if (requestedModel.endsWith(tag)) {
                baseModelName = requestedModel.substring(0, requestedModel.length() - tag.length());
                isVirtual = true;
                profile = server.profilesBySuffix.get(suffix);
                break;
            }
        }

        // Combine deny (server + profile)
        List<String> deny = new ArrayList<>(server.denyParamPointers);
        if (profile != null && profile.deny != null && !profile.deny.isEmpty()) {
            deny.addAll(profile.deny);
        }

        // Combine defaults (server then profile)
        ObjectNode mergedDefaults = deepCopy(server.paramDefaults);
        if (profile != null) {
            JsonTransform.applyOverrides(mergedDefaults, profile.defaults);
        }

        // Combine overrides (server then profile)
        ObjectNode mergedOverrides = deepCopy(server.paramOverrides);
        if (profile != null) {
            JsonTransform.applyOverrides(mergedOverrides, profile.overrides);
        }

        String apiKey = "bearer".equals(server.authType) ? server.apiKey : null;

        return new RouteTarget(
                chosenEndpoint,
                apiKey,
                isVirtual,
                baseModelName,
                List.copyOf(deny),
                mergedDefaults,
                mergedOverrides
        );
    }

    private RuntimeConfig.CompiledServer findServerByEndpoint(String endpoint) {
        for (RuntimeConfig.CompiledServer s : runtime.serversByName.values()) {
            if (s.endpoint.equals(endpoint)) {
                return s;
            }
        }
        return null;
    }

    private static ObjectNode deepCopy(ObjectNode node) {
        return (ObjectNode) node.deepCopy();
    }

    private static ObjectNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }
}