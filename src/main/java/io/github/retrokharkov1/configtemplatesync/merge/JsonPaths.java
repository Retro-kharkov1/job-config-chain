package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dotted-path helpers shared by the secret-placeholder enforcement (OQ-1), the effective-config
 * flattening used by validate/substitute (FR-17/FR-21), and template generation (FR-15).
 */
public final class JsonPaths {

    private JsonPaths() {
    }

    /**
     * Looks up a dotted path (e.g. {@code "ConnectionStrings.Default"}) inside a JSON object tree.
     * Returns {@code null} if any segment of the path is absent.
     */
    public static JsonElement get(JsonObject root, String dottedPath) {
        JsonElement current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            JsonObject obj = current.getAsJsonObject();
            if (!obj.has(segment)) {
                return null;
            }
            current = obj.get(segment);
        }
        return current;
    }

    /**
     * Flattens a nested JSON object into a map of dotted-path -> leaf value. Arrays are treated as
     * leaves (whole-array value), never recursed into element-by-element (see OQ-8: this plugin
     * replaces arrays wholesale, it does not attempt per-element paths/merges).
     */
    public static Map<String, JsonElement> flatten(JsonElement root) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        flattenInto(root, "", out);
        return out;
    }

    private static void flattenInto(JsonElement element, String prefix, Map<String, JsonElement> out) {
        if (element == null || element.isJsonNull()) {
            if (!prefix.isEmpty()) {
                out.put(prefix, JsonNull.INSTANCE);
            }
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.entrySet().isEmpty() && !prefix.isEmpty()) {
                // an empty object leaf has no further paths to contribute
                return;
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String childPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenInto(entry.getValue(), childPath, out);
            }
        } else {
            // primitive or array leaf
            out.put(prefix, element);
        }
    }

    public static String leafAsString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element instanceof JsonPrimitive) {
            JsonPrimitive p = (JsonPrimitive) element;
            if (p.isString()) {
                return p.getAsString();
            }
            return p.toString();
        }
        if (element instanceof JsonArray) {
            return element.toString();
        }
        return element.toString();
    }
}
