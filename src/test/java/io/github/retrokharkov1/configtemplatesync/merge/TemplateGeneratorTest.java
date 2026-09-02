package io.github.retrokharkov1.configtemplatesync.merge;

import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreePaths;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TemplateGenerator} (FR-15/FR-16). Covers: nested objects, empty objects
 * render as {@code {}}, arrays tokenize as one whole-leaf token (OQ-8), null leaves ARE tokenized
 * (deliberate divergence from {@code TreePaths.flattenInto}), and secrets render as identical tokens
 * with no special-casing.
 */
public class TemplateGeneratorTest {

    private static String at(TreeNode root, String dottedPath) {
        return TreePaths.get(root, dottedPath).leafAsString();
    }

    @Test
    public void fromContent_tokenizesNestedObjectLeavesWithDottedPaths() {
        TreeNode result = TemplateGenerator.fromContent(
                "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}", ContentType.JSON);

        assertEquals("#{database.host}#", at(result, "database.host"));
        assertEquals("#{database.port}#", at(result, "database.port"));
    }

    @Test
    public void fromContent_topLevelPrimitiveLeafTokenizedAtItsOwnPath() {
        TreeNode result = TemplateGenerator.fromContent("{\"featureFlag\":true}", ContentType.JSON);
        assertEquals("#{featureFlag}#", at(result, "featureFlag"));
    }

    @Test
    public void fromContent_emptyObjectLeafRendersAsEmptyObjectUnchanged() {
        TreeNode result = TemplateGenerator.fromContent("{\"featureFlags\":{}}", ContentType.JSON);
        TreeNode featureFlags = result.getChild("featureFlags");
        assertTrue(featureFlags.isObject());
        assertTrue(featureFlags.childrenIfObject().isEmpty());
    }

    @Test
    public void fromContent_emptyRootObjectRendersAsEmptyObject() {
        TreeNode result = TemplateGenerator.fromContent("{}", ContentType.JSON);
        assertTrue(result.childrenIfObject().isEmpty());
    }

    @Test
    public void fromContent_arrayLeafTokenizedAsOneWholeLeafToken_neverRecursedInto() {
        TreeNode result = TemplateGenerator.fromContent("{\"tags\":[\"a\",\"b\",\"c\"]}", ContentType.JSON);
        TreeNode tags = result.getChild("tags");
        assertFalse(tags.isObject());
        assertEquals("#{tags}#", tags.leafAsString());
    }

    @Test
    public void fromContent_nullLeafIsTokenizedLikeAnyOtherLeaf() {
        // Deliberate divergence from TreePaths.flattenInto (which preserves the null node verbatim):
        // a null-valued leaf in a common Config Set's OWN content is tokenized like any other
        // leaf — the RFC 7396 "null removes" rule only governs an ENV overlay's relationship to
        // common (FR-9), not how a single Config Set's own content is walked (FR-15).
        TreeNode result = TemplateGenerator.fromContent("{\"database\":{\"host\":null}}", ContentType.JSON);
        assertEquals("#{database.host}#", at(result, "database.host"));
    }

    @Test
    public void fromContent_secretPlaceholderLeafRendersAsIdenticalTokenTextNoSpecialCasing() {
        // TemplateGenerator never reads the secrets manifest — a secret-bound leaf's persisted
        // content is always the SecretPlaceholder.VALUE string, indistinguishable at the tree level
        // from any other string leaf (FR-42: identical token text, no distinguishing marker).
        TreeNode result = TemplateGenerator.fromContent("{\"database\":{\"password\":\"__SECRET__\"}}", ContentType.JSON);
        assertEquals("#{database.password}#", at(result, "database.password"));
    }

    @Test
    public void fromEffective_composesEffectiveConfigResolverThenTokenizes_nullOverlayRemovesKey() {
        String common = "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}";
        String envPatch = "{\"database\":{\"host\":null,\"password\":\"__SECRET__\"}}";

        TreeNode result = TemplateGenerator.fromEffective(ContentType.JSON, common, envPatch);

        TreeNode database = result.getChild("database");
        assertFalse("null in the env overlay must remove the key from the effective config (RFC 7396) "
                        + "before tokenization ever sees it",
                database.hasChild("host"));
        assertEquals("#{database.port}#", at(result, "database.port"));
        assertEquals("#{database.password}#", at(result, "database.password"));
    }

    @Test
    public void fromEffective_absentEnvPathInheritsCommonValueUnchangedThenTokenizes() {
        String common = "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}";
        String envPatch = "{\"featureFlags\":{\"newCheckout\":true}}";

        TreeNode result = TemplateGenerator.fromEffective(ContentType.JSON, common, envPatch);

        assertEquals("#{database.host}#", at(result, "database.host"));
        assertEquals("#{database.port}#", at(result, "database.port"));
        assertEquals("#{featureFlags.newCheckout}#", at(result, "featureFlags.newCheckout"));
    }

    @Test
    public void fromEffective_nullOrBlankEnvPatchTreatedAsEmptyOverlay() {
        String common = "{\"database\":{\"host\":\"db.internal\"}}";

        TreeNode result = TemplateGenerator.fromEffective(ContentType.JSON, common, null);

        assertEquals("#{database.host}#", at(result, "database.host"));
    }

    @Test
    public void fromEffective_arrayLeafInMergedResultTokenizedAsOneWholeLeafToken() {
        String common = "{\"tags\":[\"a\",\"b\"]}";
        String envPatch = "{}";

        TreeNode result = TemplateGenerator.fromEffective(ContentType.JSON, common, envPatch);

        assertEquals("#{tags}#", at(result, "tags"));
    }
}
