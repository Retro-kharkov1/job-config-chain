package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * RFC 7396 JSON Merge Patch — https://www.rfc-editor.org/rfc/rfc7396.
 *
 * <p>Semantics implemented exactly per the RFC's pseudocode: a {@code null} at a path in the patch
 * removes that key from the result; an object in the patch recurses key-by-key into the
 * corresponding object in the target (creating it if absent); any other patch value (including an
 * array) replaces the target value wholesale (FR-9). Per OQ-8, arrays are never merged
 * element-by-element — an array in the patch always replaces the target's array/value entirely,
 * a documented, known limitation of the RFC 7396 spec this plugin intentionally does not extend.
 */
public final class JsonMergePatch {

    private JsonMergePatch() {
    }

    /**
     * Applies {@code patch} on top of {@code target} per RFC 7396 and returns a new merged
     * {@link JsonElement}. Neither input is mutated.
     */
    public static JsonElement apply(JsonElement target, JsonElement patch) {
        if (patch == null || patch.isJsonNull()) {
            return null;
        }
        if (!patch.isJsonObject()) {
            // RFC 7396 step: if Patch is not an Object, return Patch.
            return patch.deepCopy();
        }
        JsonObject patchObj = patch.getAsJsonObject();
        JsonObject result = (target != null && target.isJsonObject())
                ? target.getAsJsonObject().deepCopy()
                : new JsonObject();

        for (Map.Entry<String, JsonElement> entry : patchObj.entrySet()) {
            String key = entry.getKey();
            JsonElement patchValue = entry.getValue();
            if (patchValue == null || patchValue.isJsonNull()) {
                result.remove(key);
            } else {
                JsonElement targetValue = result.has(key) ? result.get(key) : null;
                JsonElement merged = apply(targetValue, patchValue);
                result.add(key, merged);
            }
        }
        return result;
    }
}
