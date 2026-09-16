package io.jenkins.plugins.jobconfigchain.merge.tree;

import java.util.Map;

/**
 * RFC 7396 JSON Merge Patch semantics — https://www.rfc-editor.org/rfc/rfc7396 — generalized over
 * {@link TreeNode} rather than Gson's {@code JsonElement} (formerly {@code JsonMergePatch},
 * hard-removed per the tech-lead review's deprecation policy, not kept as a JSON-only wrapper).
 *
 * <p>Semantics implemented exactly per the RFC's pseudocode: a {@code null} at a path in the patch
 * removes that key from the result; an object in the patch recurses key-by-key into the
 * corresponding object in the target (creating it if absent); any other patch value (including an
 * array/attribute/repeated-sibling leaf) replaces the target value wholesale. Such
 * whole-value leaves are never merged element-by-element (see out-of-scope.md's "Per-element array
 * merging" entry) — a documented, known limitation this
 * plugin intentionally does not extend, uniform across all 3 formats.</p>
 */
public final class TreeMergePatch {

    private TreeMergePatch() {
    }

    /**
     * Applies {@code patch} on top of {@code target} per RFC 7396 and returns a new merged
     * {@link TreeNode}. Neither input is mutated.
     */
    public static TreeNode apply(TreeNode target, TreeNode patch) {
        if (patch == null || patch.isNull()) {
            return null;
        }
        if (!patch.isObject()) {
            // RFC 7396 step: if Patch is not an Object, return Patch.
            return patch.deepCopy();
        }
        // Result starts as a deep copy of the target (if it's already an object) or a fresh empty
        // object of the same concrete type as the patch (RFC 7396's "creating it if absent" rule).
        TreeNode result = (target != null && target.isObject()) ? target.deepCopy() : emptyObjectLike(patch);
        for (Map.Entry<String, TreeNode> entry : patch.childrenIfObject().entrySet()) {
            String key = entry.getKey();
            TreeNode patchValue = entry.getValue();
            if (patchValue == null || patchValue.isNull()) {
                result.removeChild(key);
            } else {
                TreeNode targetValue = result.hasChild(key) ? result.getChild(key) : null;
                TreeNode merged = apply(targetValue, patchValue);
                result.putChild(key, merged);
            }
        }
        return result;
    }

    private static TreeNode emptyObjectLike(TreeNode sample) {
        // Every TreeNode implementation in this package is a thin wrapper whose deepCopy() already
        // preserves concrete type; the simplest type-preserving "empty object" is an emptied deep
        // copy of an object-shaped node. Since `patch` (an object) is always available at every call
        // site that reaches here, use it directly rather than requiring a TreeFormat reference.
        TreeNode empty = sample.deepCopy();
        for (String key : empty.childrenIfObject().keySet()) {
            empty.removeChild(key);
        }
        return empty;
    }
}
