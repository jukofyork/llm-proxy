import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Routes requests based on model name using RuntimeConfig via RouteResolver.
 * Applies deny, defaults (missing), and overrides (force-set). Rewrites virtual model to base.
 */
public class ModelRequestRouter implements HttpProxy.RequestRouter {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final RouteResolver resolver;

    public ModelRequestRouter(RuntimeConfig runtime) {
        this.resolver = new RouteResolver(runtime);
    }

    @Override
    public HttpProxy.ProxyTarget route(String method, String requestPath, String requestBody, Map<String, String> headers) {
        String modelName = extractRequestedModelName(requestPath, requestBody, headers);
        if (modelName == null) {
            return null;
        }

        RouteTarget target = resolver.resolve(modelName);
        if (target == null) {
            Logger.warning("Model '" + modelName + "' not found");
            return null;
        }

        if (!ModelsManager.validateModelEndpoint(requestPath, target.endpoint())) {
            Logger.warning("Model '" + modelName + "' endpoint mismatch");
            return null;
        }

        String transformedBody = transformRequestBody(requestBody, target, modelName);
        logRequestPayloadIfDebug(transformedBody);

        boolean isStreaming = determineIfStreamingRequest(transformedBody);

        String resolvedPath = ModelsManager.buildFinalRequestPath(requestPath, target.endpoint());
        String resolvedUrl = target.endpoint() + resolvedPath;
        URI targetUri = URI.create(resolvedUrl);

        return new HttpProxy.ProxyTarget(targetUri, target.apiKey(), isStreaming, transformedBody);
    }

    private String extractRequestedModelName(String path, String body, Map<String, String> headers) {
        if (path.startsWith(Constants.V1_PREFIX)) {
            try {
                JsonNode root = JSON_MAPPER.readTree(body);
                return root.has("model") ? root.get("model").asText() : null;
            } catch (Exception e) {
                Logger.error("Invalid JSON in request body");
                return null;
            }
        }
        String authHeader = headers.get("authorization");
        return authHeader != null ? authHeader.replace("Bearer ", "") : null;
    }

    private String transformRequestBody(String requestBody, RouteTarget target, String requestedModel) {
        if (requestBody == null || requestBody.isEmpty()) {
            return requestBody;
        }

        try {
            JsonNode parsed = JSON_MAPPER.readTree(requestBody);
            if (!parsed.isObject()) {
                return requestBody;
            }
            ObjectNode objectNode = (ObjectNode) parsed;

            // If virtual, rewrite model to base
            if (target.isVirtual() && target.baseModelName() != null) {
                objectNode.put("model", target.baseModelName());
            }

            // Apply deny-list
            JsonTransform.applyDeny(objectNode, target.denyPaths());

            // Apply defaults (server+profile merged, missing only)
            if (target.defaults() != null && target.defaults().isObject()) {
                JsonTransform.applyDefaults(objectNode, (ObjectNode) target.defaults());
            }

            // Apply overrides (server+profile merged, force-set)
            if (target.overrides() != null && target.overrides().isObject()) {
                JsonTransform.applyOverrides(objectNode, (ObjectNode) target.overrides());
            }

            // Upsert default system/developer messages for chat-style requests
            upsertRoleDefaults(objectNode, target.defaultSystemMessage(), target.defaultDeveloperMessage());

            return JSON_MAPPER.writeValueAsString(objectNode);
        } catch (Exception e) {
            Logger.error("Failed to transform request body", e);
            return requestBody;
        }
    }

    private void upsertRoleDefaults(ObjectNode objectNode, String defaultSystem, String defaultDeveloper) {
        JsonNode msgsNode = objectNode.get("messages");
        if (msgsNode == null || !msgsNode.isArray()) {
            return; // Only operate on chat-style payloads
        }

        ArrayNode messages = (ArrayNode) msgsNode;

        // Detect existing roles
        int firstSystemIdx = -1;
        boolean hasDeveloper = false;
        for (int i = 0; i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            if (!m.isObject()) continue;
            JsonNode role = m.get("role");
            if (role != null && role.isTextual()) {
                String r = role.asText();
                if (firstSystemIdx == -1 && "system".equals(r)) {
                    firstSystemIdx = i;
                }
                if ("developer".equals(r)) {
                    hasDeveloper = true;
                }
            }
        }

        // Insert system message if missing
        if (defaultSystem != null && firstSystemIdx == -1) {
            ObjectNode sys = JSON_MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", defaultSystem);
            messages.insert(0, sys);
            firstSystemIdx = 0; // Just inserted
        }

        // Insert developer message if missing
        if (defaultDeveloper != null && !hasDeveloper) {
            ObjectNode dev = JSON_MAPPER.createObjectNode();
            dev.put("role", "developer");
            dev.put("content", defaultDeveloper);

            int insertIdx = 0;
            if (firstSystemIdx >= 0) {
                insertIdx = Math.min(firstSystemIdx + 1, messages.size());
            }
            messages.insert(insertIdx, dev);
        }
    }

    private boolean determineIfStreamingRequest(String body) {
        try {
            if (body == null || body.isEmpty()) {
                return true;
            }
            JsonNode root = JSON_MAPPER.readTree(body);
            JsonNode streamNode = root.get("stream");
            if (streamNode != null && streamNode.isBoolean() && !streamNode.asBoolean()) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private void logRequestPayloadIfDebug(String requestBody) {
        if (Constants.DEBUG_REQUEST && requestBody != null && !requestBody.isEmpty()) {
            try {
                JsonNode jsonNode = JSON_MAPPER.readTree(requestBody);
                String prettyJson = JSON_MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(jsonNode);
                Logger.info("Sending JSON:\n" + prettyJson + "\n---");
            } catch (Exception e) {
                Logger.info("Sending JSON:\n" + requestBody + "\n---");
            }
        }
    }
}