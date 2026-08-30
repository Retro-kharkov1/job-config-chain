package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Computes the Effective (Merged) Configuration: an env Config Set's active/pinned content applied
 * as an RFC 7396 JSON Merge Patch on top of the common Config Set's active/pinned content
 * (FR-8/FR-9/FR-10). Always computed fresh — never persisted as a third, independently-updatable
 * copy (FR-10). This is the single code path shared by validate, substitute, and (in a later
 * milestone) template generation and the admin UI's live merge preview (FR-16/FR-38) — there must
 * never be a second, divergent merge implementation.
 */
public final class EffectiveConfigResolver {

    private EffectiveConfigResolver() {
    }

    /**
     * @param commonContentJson full nested JSON of the common Config Set's chosen version.
     * @param envPatchJson      sparse RFC 7396 merge-patch overlay JSON of the env Config Set's
     *                          chosen version, or {@code null}/blank if the env layer has no active
     *                          version yet (treated as an empty overlay: {@code {}}).
     * @return the effective merged configuration as a {@link JsonObject}.
     */
    public static JsonObject resolve(String commonContentJson, String envPatchJson) {
        JsonElement common = JsonParser.parseString(commonContentJson);
        if (!common.isJsonObject()) {
            throw new IllegalArgumentException("Common Config Set content must be a JSON object");
        }
        JsonElement patch = (envPatchJson == null || envPatchJson.trim().isEmpty())
                ? new JsonObject()
                : JsonParser.parseString(envPatchJson);
        JsonElement merged = JsonMergePatch.apply(common, patch);
        return merged == null ? new JsonObject() : merged.getAsJsonObject();
    }
}
