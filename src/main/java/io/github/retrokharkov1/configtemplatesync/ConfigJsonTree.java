package io.github.retrokharkov1.configtemplatesync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared JSON tree helpers for the two pipeline steps: deep-merge two config layers (common +
 * env override), flatten a merged tree to dotted {@code KEY=VALUE} pairs, and extract
 * {@code #{Path}#} tokens from a target file's text.
 *
 * <p>Kept as a small, dependency-free, reusable utility because the (deferred) "Generate
 * template" feature needs the same tree-walking logic — see the README TODO.
 */
public final class ConfigJsonTree {

    /**
     * Same token pattern already used by this org's {@code ConfigurationValidationService.cs} —
     * kept byte-identical so both layers agree on what a "token" is.
     */
    public static final Pattern TOKEN_PATTERN = Pattern.compile("#\\{([^}]+)\\}#");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigJsonTree() {
    }

    /**
     * Applies the "env" layer as an RFC 7396 JSON Merge Patch on top of the "common" layer's
     * JSON — i.e. the env {@code ConfigSetVersion.contentJson} is a <b>sparse overlay</b>, not a
     * full duplicated copy of common: only keys being added/overridden need to be present. Per
     * RFC 7396 (<a href="https://www.rfc-editor.org/rfc/rfc7396">rfc-editor.org/rfc/rfc7396</a>):
     * <ul>
     *   <li>a key present in the patch with a non-null, non-object value replaces that key's
     *       value in the target outright;</li>
     *   <li>a key present in the patch with an object value recurses (object nodes merge
     *       key-by-key, not wholesale replace);</li>
     *   <li>a key present in the patch with JSON {@code null} is <b>removed</b> from the merged
     *       result — this is the standard's defined delete semantics;</li>
     *   <li>a key absent from the patch is left untouched, i.e. inherited from common unchanged.</li>
     * </ul>
     *
     * @param baseJson  JSON text of the "common" layer (may be {@code null}/blank, treated as {})
     * @param patchJson JSON text of the "env" layer's merge-patch overlay (may be {@code null}/blank,
     *                  treated as {}, i.e. "no overrides")
     * @return merged (effective) tree as a Jackson {@link JsonNode}
     */
    public static JsonNode deepMerge(String baseJson, String patchJson) {
        JsonNode base = parseOrEmptyObject(baseJson);
        JsonNode patch = parseOrEmptyObject(patchJson);
        return mergePatch(base, patch);
    }

    /** RFC 7396 `MergePatch(Target, Patch)` recursive algorithm. */
    private static JsonNode mergePatch(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            // Patch is a scalar/array (or the whole patch document itself is a leaf) -> it replaces
            // the target outright, per RFC 7396 step "if Patch is not an object, return Patch".
            return patch;
        }
        ObjectNode result = target.isObject() ? ((ObjectNode) target).deepCopy() : MAPPER.createObjectNode();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JsonNode patchValue = entry.getValue();
            if (patchValue.isNull()) {
                result.remove(name); // RFC 7396: null in the patch deletes the member
            } else {
                JsonNode targetValue = result.has(name) ? result.get(name) : MAPPER.createObjectNode();
                result.set(name, mergePatch(targetValue, patchValue));
            }
        }
        return result;
    }

    private static JsonNode parseOrEmptyObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node == null ? MAPPER.createObjectNode() : node;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON in ConfigSet content: " + e.getMessage(), e);
        }
    }

    /**
     * Flattens a JSON object tree to dotted-path -> leaf-value pairs, e.g.
     * {@code {"A":{"B":"x"}}} -> {@code {"A.B":"x"}}. Only leaf (non-object) values are emitted;
     * arrays are treated as leaves (their JSON text form is the value) since this org's config
     * shape does not use arrays for substitutable settings.
     */
    public static Map<String, String> flatten(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        flattenInto(node, "", out);
        return out;
    }

    private static void flattenInto(JsonNode node, String prefix, Map<String, String> out) {
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenInto(entry.getValue(), path, out);
            }
        } else if (!prefix.isEmpty()) {
            out.put(prefix, node.isTextual() ? node.asText() : node.toString());
        }
    }

    /** Extracts every {@code #{Path}#} token's inner path from the given file text, in file order. */
    public static java.util.List<String> extractTokens(String fileText) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(fileText);
        while (m.find()) {
            tokens.add(m.group(1));
        }
        return tokens;
    }
}
