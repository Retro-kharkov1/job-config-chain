package io.jenkins.plugins.jobconfigchain.merge.tree;

import java.util.Map;

/**
 * Format-neutral tree abstraction over one Config Set's parsed content, shared by the merge fold
 * ({@link TreeMergePatch}), template generation ({@code TemplateGenerator}), and secret-placeholder
 * enforcement ({@code ConfigSet.enforceSecretPlaceholders}) — one operation set, three formats (see
 * multi-format-content.md). Every method is a structural primitive; format-specific parsing/serialization
 * detail never leaks through this interface (see {@link TreeFormat}).
 */
public interface TreeNode {
    boolean isObject();

    boolean isNull();

    /** Insertion-order preserving. Never called on a non-object node — check {@link #isObject()} first. */
    Map<String, TreeNode> childrenIfObject();

    boolean hasChild(String key);

    /** @return the child, or null if absent. Never called on a non-object node. */
    TreeNode getChild(String key);

    /** Never called on a non-object node. */
    void putChild(String key, TreeNode value);

    /** No-op if key is absent. Never called on a non-object node. */
    void removeChild(String key);

    /** Deep, independent copy — mutating the copy must never affect the original. */
    TreeNode deepCopy();

    /**
     * Renders a non-object leaf as a plain string for token/flatten purposes ({@code
     * TreePaths#leafAsString}). Never called on an object node.
     */
    String leafAsString();
}
