package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.List;

/**
 * Computes the Effective (Merged) Configuration: an ordered chain of base Config Sets' content
 * folded left-to-right, then an env Config Set's active/pinned content applied as an RFC 7396 JSON
 * Merge Patch on top (FR-8/FR-9/FR-10). Always computed fresh — never persisted as a third,
 * independently-updatable copy (FR-10). This is the single code path shared by validate, substitute,
 * template generation, and the admin UI's live merge preview (FR-16/FR-38) — there must never be a
 * second, divergent merge implementation.
 */
public final class EffectiveConfigResolver {

    private EffectiveConfigResolver() {
    }

    /**
     * Folds an ordered list of base Config Sets' full-document JSON left-to-right (each later base
     * overwrites the running fold's overlapping keys, FR-8), then applies {@code envPatchJson} as the
     * final RFC 7396 merge patch on top (FR-9). An empty {@code baseContentsInOrder} folds to
     * {@code {}}, so the env patch is then applied over an empty object — exactly today's
     * one-implicit-base-with-nothing-there-yet behavior, generalized.
     *
     * @throws IllegalArgumentException if any entry in {@code baseContentsInOrder} does not parse to a
     *                                   JSON object (names its 0-based position in the chain — callers
     *                                   with richer context, e.g. a projectKey, wrap this with that
     *                                   context before it reaches an operator; in practice this is
     *                                   structurally unreachable via the UI/pipeline steps, since every
     *                                   persisted {@code ConfigSetVersion}'s content is already
     *                                   validated as a JSON object at save time — FR-33/{@code
     *                                   ConfigSetPage#validateJsonSyntaxOrFail}).
     */
    public static JsonObject resolveChain(List<String> baseContentsInOrder, String envPatchJson) {
        JsonObject accumulator = new JsonObject();
        int position = 0;
        for (String baseContentJson : baseContentsInOrder) {
            JsonElement base = JsonParser.parseString(baseContentJson);
            if (!base.isJsonObject()) {
                throw new IllegalArgumentException(
                        "Base Config Set content at chain position " + position + " must be a JSON object");
            }
            JsonElement folded = JsonMergePatch.apply(accumulator, base);
            accumulator = folded == null ? new JsonObject() : folded.getAsJsonObject();
            position++;
        }
        JsonElement patch = (envPatchJson == null || envPatchJson.trim().isEmpty())
                ? new JsonObject()
                : JsonParser.parseString(envPatchJson);
        JsonElement merged = JsonMergePatch.apply(accumulator, patch);
        return merged == null ? new JsonObject() : merged.getAsJsonObject();
    }

    /**
     * @param commonContentJson full nested JSON of the common Config Set's chosen version.
     * @param envPatchJson      sparse RFC 7396 merge-patch overlay JSON of the env Config Set's
     *                          chosen version, or {@code null}/blank if the env layer has no active
     *                          version yet (treated as an empty overlay: {@code {}}).
     * @return the effective merged configuration as a {@link JsonObject}.
     */
    public static JsonObject resolve(String commonContentJson, String envPatchJson) {
        return resolveChain(Collections.singletonList(commonContentJson), envPatchJson);
    }
}
