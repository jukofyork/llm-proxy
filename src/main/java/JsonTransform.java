import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility functions for applying parameter policies, defaults, and overrides.
 */
public class JsonTransform {

    public static void applyDeny(ObjectNode node, List<String> jsonPointers) {
        if (jsonPointers == null || jsonPointers.isEmpty() || node == null) return;
        for (String ptr : jsonPointers) {
            removeByPointer(node, ptr);
        }
    }

    public static void applyDefaults(ObjectNode target, ObjectNode defaults) {
        if (target == null || defaults == null || defaults.isEmpty()) return;
        deepDefaultMerge(target, defaults);
    }

    public static void applyOverrides(ObjectNode target, ObjectNode overrides) {
        if (target == null || overrides == null || overrides.isEmpty()) return;
        deepOverrideMerge(target, overrides);
    }

    private static void deepDefaultMerge(ObjectNode target, ObjectNode defaults) {
        defaults.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode defVal = entry.getValue();
            JsonNode existing = target.get(field);

            if (existing == null || existing.isNull()) {
                target.set(field, defVal.deepCopy());
            } else if (existing.isObject() && defVal.isObject()) {
                deepDefaultMerge((ObjectNode) existing, (ObjectNode) defVal);
            }
        });
    }

    private static void deepOverrideMerge(ObjectNode target, ObjectNode overrides) {
        overrides.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode overVal = entry.getValue();
            JsonNode existing = target.get(field);

            if (existing != null && existing.isObject() && overVal.isObject()) {
                deepOverrideMerge((ObjectNode) existing, (ObjectNode) overVal);
            } else {
                target.set(field, overVal.deepCopy());
            }
        });
    }

    /**
     * Removes the value at a JSON Pointer by navigating to its parent and removing the leaf.
     * Supports only object field removal (arrays not supported in this minimal version).
     */
    private static void removeByPointer(ObjectNode root, String pointer) {
        if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) return;

        String[] tokens = pointer.split("/");
        // tokens[0] is empty due to leading '/'
        ObjectNode parent = root;
        for (int i = 1; i < tokens.length - 1; i++) {
            String key = decodePointerToken(tokens[i]);
            JsonNode child = parent.get(key);
            if (child == null || !child.isObject()) {
                return; // path does not exist
            }
            parent = (ObjectNode) child;
        }
        String leafKey = decodePointerToken(tokens[tokens.length - 1]);
        parent.remove(leafKey);
    }

    private static String decodePointerToken(String t) {
        return t.replace("~1", "/").replace("~0", "~");
    }
}