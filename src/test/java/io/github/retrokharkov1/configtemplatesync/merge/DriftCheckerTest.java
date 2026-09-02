package io.github.retrokharkov1.configtemplatesync.merge;

import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormats;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DriftCheckerTest {

    private static TreeNode json(String content) {
        return TreeFormats.forType(ContentType.JSON).parse(content);
    }

    @Test
    public void detectsMissingTokenNotInEffectiveConfig() {
        TreeNode effective = json("{\"a\":1}");
        String fileContent = "value=#{a}# other=#{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(1, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("b"));
    }

    @Test
    public void detectsOrphanedKeyNotInFile() {
        TreeNode effective = json("{\"a\":1,\"b\":2}");
        String fileContent = "value=#{a}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertTrue(result.hasOrphanedKeys());
        assertTrue(result.getOrphanedKeys().contains("b"));
    }

    @Test
    public void noDriftWhenSetsMatchExactly() {
        TreeNode effective = json("{\"a\":1,\"b\":2}");
        String fileContent = "#{a}# #{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertFalse(result.hasOrphanedKeys());
    }
}
