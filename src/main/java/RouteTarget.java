import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Result of route resolution for a requested model.
 */
public record RouteTarget(
        String endpoint,
        String apiKey,
        boolean isVirtual,
        String baseModelName,
        List<String> denyPaths,
        JsonNode defaults,
        JsonNode overrides
) {}