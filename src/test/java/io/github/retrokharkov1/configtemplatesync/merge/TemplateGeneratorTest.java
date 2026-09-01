package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TemplateGenerator} (FR-15/FR-16). Covers: nested objects, empty objects
 * render as {@code {}}, arrays tokenize as one whole-leaf token (OQ-8), null leaves ARE tokenized
 * (deliberate divergence from {@link JsonPaths#flattenInto}), and secrets render as identical
 * tokens with no special-casing.
 */
public class TemplateGeneratorTest {

    @Test
    public void fromContent_tokenizesNestedObjectLeavesWithDottedPaths() {
        JsonObject result = TemplateGenerator.fromContent(
                "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}");

        assertEquals("#{database.host}#", result.getAsJsonObject("database").get("host").getAsString());
        assertEquals("#{database.port}#", result.getAsJsonObject("database").get("port").getAsString());
    }

    @Test
    public void fromContent_topLevelPrimitiveLeafTokenizedAtItsOwnPath() {
        JsonObject result = TemplateGenerator.fromContent("{\"featureFlag\":true}");
        assertEquals("#{featureFlag}#", result.get("featureFlag").getAsString());
    }

    @Test
    public void fromContent_emptyObjectLeafRendersAsEmptyObjectUnchanged() {
        JsonObject result = TemplateGenerator.fromContent("{\"featureFlags\":{}}");
        assertTrue(result.get("featureFlags").isJsonObject());
        assertTrue(result.getAsJsonObject("featureFlags").entrySet().isEmpty());
    }

    @Test
    public void fromContent_emptyRootObjectRendersAsEmptyObject() {
        JsonObject result = TemplateGenerator.fromContent("{}");
        assertTrue(result.entrySet().isEmpty());
    }

    @Test
    public void fromContent_arrayLeafTokenizedAsOneWholeLeafToken_neverRecursedInto() {
        JsonObject result = TemplateGenerator.fromContent("{\"tags\":[\"a\",\"b\",\"c\"]}");
        assertTrue(result.get("tags").isJsonPrimitive());
        assertEquals("#{tags}#", result.get("tags").getAsString());
    }

    @Test
    public void fromContent_nullLeafIsTokenizedLikeAnyOtherLeaf() {
        // Deliberate divergence from JsonPaths.flattenInto (which preserves JsonNull.INSTANCE):
        // a null-valued leaf in a common Config Set's OWN content is tokenized like any other
        // leaf — the RFC 7396 "null removes" rule only governs an ENV overlay's relationship to
        // common (FR-9), not how a single Config Set's own content is walked (FR-15).
        JsonObject result = TemplateGenerator.fromContent("{\"database\":{\"host\":null}}");
        assertEquals("#{database.host}#", result.getAsJsonObject("database").get("host").getAsString());
    }

    @Test
    public void fromContent_secretPlaceholderLeafRendersAsIdenticalTokenTextNoSpecialCasing() {
        // TemplateGenerator never reads the secrets manifest — a secret-bound leaf's persisted
        // content is always the SecretPlaceholder.VALUE string, indistinguishable at the JSON-tree
        // level from any other string leaf (FR-42: identical token text, no distinguishing marker).
        JsonObject result = TemplateGenerator.fromContent("{\"database\":{\"password\":\"__SECRET__\"}}");
        assertEquals("#{database.password}#",
                result.getAsJsonObject("database").get("password").getAsString());
    }

    @Test
    public void fromEffective_composesEffectiveConfigResolverThenTokenizes_nullOverlayRemovesKey() {
        String common = "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}";
        String envPatch = "{\"database\":{\"host\":null,\"password\":\"__SECRET__\"}}";

        JsonObject result = TemplateGenerator.fromEffective(common, envPatch);

        JsonObject database = result.getAsJsonObject("database");
        assertFalse("null in the env overlay must remove the key from the effective config (RFC 7396) "
                        + "before tokenization ever sees it",
                database.has("host"));
        assertEquals("#{database.port}#", database.get("port").getAsString());
        assertEquals("#{database.password}#", database.get("password").getAsString());
    }

    @Test
    public void fromEffective_absentEnvPathInheritsCommonValueUnchangedThenTokenizes() {
        String common = "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}";
        String envPatch = "{\"featureFlags\":{\"newCheckout\":true}}";

        JsonObject result = TemplateGenerator.fromEffective(common, envPatch);

        assertEquals("#{database.host}#", result.getAsJsonObject("database").get("host").getAsString());
        assertEquals("#{database.port}#", result.getAsJsonObject("database").get("port").getAsString());
        assertEquals("#{featureFlags.newCheckout}#",
                result.getAsJsonObject("featureFlags").get("newCheckout").getAsString());
    }

    @Test
    public void fromEffective_nullOrBlankEnvPatchTreatedAsEmptyOverlay() {
        String common = "{\"database\":{\"host\":\"db.internal\"}}";

        JsonObject result = TemplateGenerator.fromEffective(common, null);

        assertEquals("#{database.host}#", result.getAsJsonObject("database").get("host").getAsString());
    }

    @Test
    public void fromEffective_arrayLeafInMergedResultTokenizedAsOneWholeLeafToken() {
        String common = "{\"tags\":[\"a\",\"b\"]}";
        String envPatch = "{}";

        JsonObject result = TemplateGenerator.fromEffective(common, envPatch);

        assertEquals("#{tags}#", result.get("tags").getAsString());
    }
}
