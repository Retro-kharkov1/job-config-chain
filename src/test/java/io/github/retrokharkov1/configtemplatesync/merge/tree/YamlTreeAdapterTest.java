package io.github.retrokharkov1.configtemplatesync.merge.tree;

import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class YamlTreeAdapterTest {

    private static final TreeFormat YAML = TreeFormats.forType(ContentType.YAML);

    @Test
    public void objectRecurse() {
        TreeNode root = YAML.parse("database:\n  host: db.local\n");
        assertTrue(root.isObject());
        TreeNode database = root.getChild("database");
        assertTrue(database.isObject());
        assertEquals("db.local", database.getChild("host").leafAsString());
    }

    @Test
    public void nullValueLeafIsRecognizedAsNull() {
        TreeNode root = YAML.parse("password: null\n");
        TreeNode password = root.getChild("password");
        assertTrue(password.isNull());
    }

    @Test
    public void sequenceIsWholeValueLeaf() {
        TreeNode root = YAML.parse("servers:\n  - a\n  - b\n");
        TreeNode servers = root.getChild("servers");
        assertFalse(servers.isObject());
        String rendered = servers.leafAsString();
        assertTrue(rendered.contains("a"));
        assertTrue(rendered.contains("b"));
    }

    @Test
    public void malformedYamlRejected() {
        try {
            YAML.parse("key: [unbalanced\n");
            fail("expected a YAMLException-shaped RuntimeException");
        } catch (RuntimeException expected) {
            // any unchecked RuntimeException satisfies TreeFormat#parse's contract
        }
    }

    @Test
    public void nullRemovesKeyViaMerge() {
        TreeNode target = YAML.parse("a: 1\nb: 2\n");
        TreeNode patch = YAML.parse("b: null\n");
        TreeNode result = TreeMergePatch.apply(target, patch);
        assertFalse(result.hasChild("b"));
        assertTrue(result.hasChild("a"));
    }

    @Test
    public void nestedObjectRecursesKeyByKeyViaMerge() {
        TreeNode target = YAML.parse("database:\n  host: old\n  port: 5432\n");
        TreeNode patch = YAML.parse("database:\n  host: new\n");
        TreeNode result = TreeMergePatch.apply(target, patch);
        TreeNode database = result.getChild("database");
        assertEquals("new", database.getChild("host").leafAsString());
        assertEquals("5432", database.getChild("port").leafAsString());
    }

    @Test
    public void emptyDocumentParsesAsEmptyMapping() {
        TreeNode root = YAML.parse("");
        assertTrue(root.isObject());
        assertTrue(root.childrenIfObject().isEmpty());
    }
}
