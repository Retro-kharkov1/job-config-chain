package io.jenkins.plugins.jobconfigchain.merge;

import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DriftCheckerTest {

    private static TreeNode json(String content) {
        return TreeFormats.forType(ContentType.JSON).parse(content);
    }

    private static TreeNode xml(String content) {
        return TreeFormats.forType(ContentType.XML).parse(content);
    }

    private static TreeNode yaml(String content) {
        return TreeFormats.forType(ContentType.YAML).parse(content);
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

    @Test
    public void detectsMultipleMissingTokensSimultaneously() {
        TreeNode effective = json("{\"a\":1}");
        String fileContent = "#{a}# #{b}# #{c}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(2, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("b"));
        assertTrue(result.getMissingKeys().contains("c"));
    }

    @Test
    public void missingAndOrphanedKeysCoexistIndependently() {
        TreeNode effective = json("{\"a\":1,\"b\":2}");
        String fileContent = "#{a}# #{missing}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(1, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("missing"));

        assertTrue(result.hasOrphanedKeys());
        assertEquals(1, result.getOrphanedKeys().size());
        assertTrue(result.getOrphanedKeys().contains("b"));
    }

    @Test
    public void detectsMultipleMissingTokensSimultaneouslyXml() {
        TreeNode effective = xml("<root><a>1</a></root>");
        String fileContent = "#{a}# #{b}# #{c}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(2, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("b"));
        assertTrue(result.getMissingKeys().contains("c"));
    }

    @Test
    public void detectsMultipleMissingTokensSimultaneouslyYaml() {
        TreeNode effective = yaml("a: 1\n");
        String fileContent = "#{a}# #{b}# #{c}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(2, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("b"));
        assertTrue(result.getMissingKeys().contains("c"));
    }

    @Test
    public void missingAndOrphanedKeysCoexistIndependentlyXml() {
        TreeNode effective = xml("<root><a>1</a><b>2</b></root>");
        String fileContent = "#{a}# #{missing}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(1, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("missing"));

        assertTrue(result.hasOrphanedKeys());
        assertEquals(1, result.getOrphanedKeys().size());
        assertTrue(result.getOrphanedKeys().contains("b"));
    }

    @Test
    public void missingAndOrphanedKeysCoexistIndependentlyYaml() {
        TreeNode effective = yaml("a: 1\nb: 2\n");
        String fileContent = "#{a}# #{missing}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(1, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("missing"));

        assertTrue(result.hasOrphanedKeys());
        assertEquals(1, result.getOrphanedKeys().size());
        assertTrue(result.getOrphanedKeys().contains("b"));
    }

    @Test
    public void detectsMissingTokenNotInEffectiveConfigXml() {
        TreeNode effective = xml("<root><a>1</a></root>");
        String fileContent = "value=#{a}# other=#{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(1, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("b"));
    }

    @Test
    public void detectsMissingTokenNotInEffectiveConfigYaml() {
        TreeNode effective = yaml("a: 1\n");
        String fileContent = "value=#{a}# other=#{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(1, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("b"));
    }

    @Test
    public void detectsOrphanedKeyNotInFileXml() {
        TreeNode effective = xml("<root><a>1</a><b>2</b></root>");
        String fileContent = "value=#{a}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertTrue(result.hasOrphanedKeys());
        assertTrue(result.getOrphanedKeys().contains("b"));
    }

    @Test
    public void detectsOrphanedKeyNotInFileYaml() {
        TreeNode effective = yaml("a: 1\nb: 2\n");
        String fileContent = "value=#{a}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertTrue(result.hasOrphanedKeys());
        assertTrue(result.getOrphanedKeys().contains("b"));
    }

    @Test
    public void noDriftWhenSetsMatchExactlyXml() {
        TreeNode effective = xml("<root><a>1</a><b>2</b></root>");
        String fileContent = "#{a}# #{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertFalse(result.hasOrphanedKeys());
    }

    @Test
    public void noDriftWhenSetsMatchExactlyYaml() {
        TreeNode effective = yaml("a: 1\nb: 2\n");
        String fileContent = "#{a}# #{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertFalse(result.hasOrphanedKeys());
    }
}
