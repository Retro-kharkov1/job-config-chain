package io.jenkins.plugins.jobconfigchain.merge.tree;

import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Behavior-neutral refactor check for {@link TreePaths} against the JSON adapter (formerly {@code JsonPathsTest}). */
public class TreePathsTest {

    private static final TreeFormat JSON = TreeFormats.forType(ContentType.JSON);

    @Test
    public void flattenProducesDottedPaths() {
        TreeNode root = JSON.parse("{\"a\":{\"b\":1,\"c\":{\"d\":2}},\"e\":3}");
        Map<String, TreeNode> flattened = TreePaths.flatten(root);

        assertEquals(3, flattened.size());
        assertTrue(flattened.containsKey("a.b"));
        assertTrue(flattened.containsKey("a.c.d"));
        assertTrue(flattened.containsKey("e"));
    }

    @Test
    public void getReturnsNullForMissingPath() {
        TreeNode root = JSON.parse("{\"a\":{\"b\":1}}");
        assertNull(TreePaths.get(root, "a.x"));
        assertNull(TreePaths.get(root, "z"));
    }

    @Test
    public void getResolvesExistingPath() {
        TreeNode root = JSON.parse("{\"a\":{\"b\":1}}");
        TreeNode value = TreePaths.get(root, "a.b");
        assertEquals("1", value.leafAsString());
    }
}
