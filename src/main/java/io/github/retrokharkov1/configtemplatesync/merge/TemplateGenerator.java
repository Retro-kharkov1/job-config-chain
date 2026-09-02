package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * Generates a copy-paste-ready JSON template (FR-15) by walking a Config Set's content tree and
 * replacing every leaf with its {@code #{Dotted.Path}#} token — the identical token syntax already
 * produced/consumed by {@link TokenExtractor#TOKEN_PATTERN} for validate (FR-17) and substitute
 * (FR-21), so a generated template round-trips through that same drift-detection logic with zero
 * translation.
 *
 * <p><b>Relationship to {@link JsonPaths}:</b> the leaf/shape edge cases here are deliberately
 * copied from {@link JsonPaths#flatten}/{@link JsonPaths#flattenInto}'s already-approved semantics
 * (empty object at any path contributes no token and renders as {@code {}} unchanged; an
 * array-valued leaf is tokenized as a single whole-leaf token at its own path, never recursed into
 * element-by-element — per OQ-8). This class does NOT reuse {@code flatten}/{@code flattenInto}
 * directly, though, because the two methods have genuinely different output contracts: {@code
 * flatten} discards tree shape entirely (flat {@code Map<dottedPath, JsonElement>}), while template
 * generation needs a JSON tree of the IDENTICAL shape as the source with every leaf replaced by a
 * token string — rebuilding a tree from a flattened map would work but is strictly more code than a
 * dedicated recursive walk.</p>
 *
 * <p><b>Deliberate, explicit divergence from {@code JsonPaths.flattenInto} (FR-15 restated):</b> a
 * {@code null}-valued leaf here IS tokenized like any other leaf, whereas {@code flattenInto} stores
 * {@code JsonNull.INSTANCE} verbatim (used by validate/substitute's flatten-and-compare, where a
 * {@code null} literally appearing in an EFFECTIVE (merged) configuration is a real, comparable
 * value). This is intentional, not an inconsistency to "fix": the RFC 7396 "{@code null} removes"
 * rule (FR-9) is a cross-layer rule — it governs how an ENV overlay relates to the COMMON layer
 * underneath it — it does not apply to how a single Config Set's own stored content is walked for
 * template generation (FR-15's own text calls this out explicitly). A future reader diffing this
 * class against {@code JsonPaths} should see this comment before assuming the difference is a bug.</p>
 */
public final class TemplateGenerator {

    private TemplateGenerator() {
    }

    /**
     * FR-15a: walks {@code contentJson}'s own nested shape directly (no merge), replacing every leaf
     * (secret-manifest-bound or not, {@code null} included — see class javadoc) with its
     * {@code #{Dotted.Path}#} token. Empty objects render as {@code {}} unchanged; array leaves
     * tokenize as one whole-leaf token (never recursed into), per FR-15's restated JsonPaths edge
     * cases.
     *
     * @param contentJson a Config Set version's own stored {@code contentJson} (must be a JSON
     *                    object at the root).
     * @return the same nested shape, every leaf replaced by its dotted-path token.
     */
    public static JsonObject fromContent(String contentJson) {
        JsonElement parsed = com.google.gson.JsonParser.parseString(contentJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Content must be a JSON object");
        }
        JsonElement tokenized = tokenize(parsed, "");
        return tokenized.getAsJsonObject();
    }

    /**
     * FR-15b: resolves the effective (merged) configuration via
     * {@link EffectiveConfigResolver#resolve(String, String)} (FR-16 — no second, divergent merge
     * implementation), then applies the identical leaf-tokenization walk used by
     * {@link #fromContent(String)} to the merged result.
     *
     * @param commonContentJson the common Config Set's chosen version content (full nested JSON).
     * @param envPatchJson      the env Config Set's chosen version content (sparse RFC 7396
     *                          merge-patch overlay), or {@code null}/blank if the env layer has no
     *                          active version yet (treated as an empty overlay — see
     *                          {@link EffectiveConfigResolver#resolve}).
     * @return the effective merged configuration, every leaf replaced by its dotted-path token.
     */
    public static JsonObject fromEffective(String commonContentJson, String envPatchJson) {
        JsonObject effective = EffectiveConfigResolver.resolve(commonContentJson, envPatchJson);
        return fromEffective(effective);
    }

    /**
     * FR-15b generalized to a multi-base chain (FR-51): applies the identical leaf-tokenization walk
     * to an already-resolved effective (merged) configuration — e.g. one computed by
     * {@link EffectiveConfigResolver#resolveChain}, so callers resolving a base chain never need to
     * re-flatten common+env content just to reach this class's tokenization logic.
     *
     * @param effective an already-computed effective (merged) configuration.
     * @return the same nested shape, every leaf replaced by its dotted-path token.
     */
    public static JsonObject fromEffective(JsonObject effective) {
        JsonElement tokenized = tokenize(effective, "");
        return tokenized.getAsJsonObject();
    }

    /**
     * The single internal tokenization walk, shared by both {@link #fromContent(String)} (called
     * directly against a Config Set's own content) and {@link #fromEffective(String, String)}
     * (called against {@link EffectiveConfigResolver}'s output) — exactly one tokenization
     * implementation inside this class, never two.
     */
    private static JsonElement tokenize(JsonElement element, String prefix) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.entrySet().isEmpty()) {
                // Empty object at any path (including the root) renders as {} unchanged — an empty
                // object has no leaves to contribute a token for (FR-15's restated flatten semantics).
                return new JsonObject();
            }
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String childPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                out.add(entry.getKey(), tokenize(entry.getValue(), childPath));
            }
            return out;
        }
        // Every non-object leaf — primitive (string/number/boolean), null, or array — is tokenized
        // as one whole-leaf token at its own path. Arrays are never recursed into element-by-element
        // (OQ-8). null leaves ARE tokenized here (see class javadoc for why this deliberately
        // diverges from JsonPaths.flattenInto's null-preserving behavior).
        return new JsonPrimitive("#{" + prefix + "}#");
    }
}
