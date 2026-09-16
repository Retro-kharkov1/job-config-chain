package io.jenkins.plugins.jobconfigchain.merge.tree;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dotted-path helpers shared by the secret-placeholder enforcement, the effective-config flattening
 * used by validate/substitute, and template generation — format-neutral,
 * operating over {@link TreeNode} rather than any one format's concrete parse tree (generalized from
 * the former JSON-only {@code JsonPaths}).
 */
public final class TreePaths {

    private TreePaths() {
    }

    /**
     * Looks up a dotted path (e.g. {@code "ConnectionStrings.Default"}) inside an object-rooted tree.
     * Returns {@code null} if any segment of the path is absent.
     */
    public static TreeNode get(TreeNode root, String dottedPath) {
        TreeNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            if (!current.hasChild(segment)) {
                return null;
            }
            current = current.getChild(segment);
        }
        return current;
    }

    /**
     * Flattens a nested object tree into a map of dotted-path -&gt; leaf {@link TreeNode}. Arrays/
     * repeated-elements/attributes are treated as leaves (whole-value), never recursed into
     * element-by-element (see out-of-scope.md's "Per-element array merging" entry — this plugin
     * replaces such values wholesale, it does not attempt per-element paths/merges).
     */
    public static Map<String, TreeNode> flatten(TreeNode root) {
        Map<String, TreeNode> out = new LinkedHashMap<>();
        flattenInto(root, "", out);
        return out;
    }

    private static void flattenInto(TreeNode node, String prefix, Map<String, TreeNode> out) {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty()) {
                out.put(prefix, node);
            }
            return;
        }
        if (node.isObject()) {
            Map<String, TreeNode> children = node.childrenIfObject();
            if (children.isEmpty() && !prefix.isEmpty()) {
                // an empty object leaf has no further paths to contribute
                return;
            }
            for (Map.Entry<String, TreeNode> entry : children.entrySet()) {
                String childPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenInto(entry.getValue(), childPath, out);
            }
        } else {
            // primitive/array/attribute/repeated-sibling leaf
            out.put(prefix, node);
        }
    }

    public static String leafAsString(TreeNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.leafAsString();
    }
}
