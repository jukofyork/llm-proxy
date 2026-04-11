import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Resolves a requested model name into routing details using RuntimeConfig and ModelsManager registry.
 * Combines server-level and profile-level (virtual) defaults/overrides/deny.
 */
public class RouteResolver {

    /**
     * Resolves a requested model name into routing details.
     * Handles virtual profiles (suffix-based), combines server and profile-level
     * deny lists, defaults, overrides, and default messages.
     *
     * @param runtime the runtime configuration snapshot to use for resolution
     * @param requestedModel the model identifier from the request (may include profile suffix)
     * @return route target with resolved endpoint and transformation rules, or null if not found
     */
    public RouteTarget resolve(RuntimeConfig runtime, String requestedModel) {
        ModelsManager.ModelConfig modelCfg = ModelsManager.getModelConfig(requestedModel);
        if (modelCfg == null) {
            return null;
        }

        RuntimeConfig.CompiledServer server = findServerByEndpoint(runtime, modelCfg.endpoint());
        if (server == null) {
            Logger.warning("No server config found for endpoint: " + modelCfg.endpoint());
            return new RouteTarget(
                    modelCfg.endpoint(),
                    modelCfg.apiKey(),
                    false,
                    requestedModel,
                    null,
                    null,
                    List.of(),
                    emptyObject(),
                    emptyObject()
            );
        }

        String chosenEndpoint = server.endpoint;

        // Virtual profile detection: model names ending in "-<suffix>" are virtual.
        // The suffix identifies which profile to apply, and the base model name is extracted
        // by stripping the suffix. Example: "gpt-4-fast" -> base="gpt-4", profile="fast"
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

        // Merge rules: profile settings override server settings
        // Combine deny (server + profile)
        List<String> deny = new ArrayList<>(server.denyParamPointers);
        if (profile != null && profile.deny != null && !profile.deny.isEmpty()) {
            deny.addAll(profile.deny);
        }

        // Apply profile defaults on top of server defaults
        ObjectNode mergedDefaults = deepCopy(server.paramDefaults);
        if (profile != null) {
            JsonTransform.applyOverrides(mergedDefaults, profile.defaults);
        }

        // Apply profile overrides on top of server overrides
        ObjectNode mergedOverrides = deepCopy(server.paramOverrides);
        if (profile != null) {
            JsonTransform.applyOverrides(mergedOverrides, profile.overrides);
        }

        // Default messages: profile values take precedence over server values
        String effectiveSystemMessage = server.defaultSystemMessage;
        String effectiveDeveloperMessage = server.defaultDeveloperMessage;
        if (profile != null) {
            if (profile.defaultSystemMessage != null) {
                effectiveSystemMessage = profile.defaultSystemMessage;
            }
            if (profile.defaultDeveloperMessage != null) {
                effectiveDeveloperMessage = profile.defaultDeveloperMessage;
            }
        }

        String apiKey = "bearer".equals(server.authType) ? server.apiKey : null;

        return new RouteTarget(
                chosenEndpoint,
                apiKey,
                isVirtual,
                baseModelName,
                effectiveSystemMessage,
                effectiveDeveloperMessage,
                List.copyOf(deny),
                mergedDefaults,
                mergedOverrides
        );
    }

    private RuntimeConfig.CompiledServer findServerByEndpoint(RuntimeConfig runtime, String endpoint) {
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