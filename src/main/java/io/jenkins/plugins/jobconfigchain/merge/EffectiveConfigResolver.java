package io.jenkins.plugins.jobconfigchain.merge;

import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormat;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeMergePatch;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.model.ContentType;

import java.util.Collections;
import java.util.List;

/**
 * Computes the Effective (Merged) Configuration: an ordered chain of base Config Sets' content
 * folded left-to-right, then an env Config Set's active/pinned content applied as an RFC 7396 merge
 * patch on top (see overview.md's "Effective (Merged) Configuration" entity). Always computed fresh
 * — never persisted as a third, independently-updatable copy. This is the single code path shared
 * by validate, substitute, template generation, and the admin UI's live merge preview — there must
 * never be a second, divergent merge implementation.
 *
 * <p>Format-neutral over {@link TreeNode} — every base and the env patch share one resolved
 * {@link ContentType} across the whole chain, enforced by callers BEFORE this class is reached (see
 * multi-format-content.md's "Cross-chain type consistency" section); this class itself never
 * inspects/mixes types.</p>
 */
public final class EffectiveConfigResolver {

    private EffectiveConfigResolver() {
    }

    /**
     * Folds an ordered list of base Config Sets' already-parsed content left-to-right (each later
     * base overwrites the running fold's overlapping keys), then applies {@code envPatchRaw}
     * (parsed in {@code contentType}'s format) as the final RFC 7396 merge patch on top. An
     * empty {@code baseContentsInOrder} folds to an empty object, so the env patch is then applied
     * over an empty object — exactly today's one-implicit-base-with-nothing-there-yet behavior,
     * generalized.
     */
    public static TreeNode resolveChain(ContentType contentType, List<TreeNode> baseContentsInOrder,
                                         String envPatchRaw) {
        TreeFormat format = TreeFormats.forType(contentType);
        TreeNode accumulator = format.emptyObject();
        for (TreeNode base : baseContentsInOrder) {
            TreeNode folded = TreeMergePatch.apply(accumulator, base);
            accumulator = folded == null ? format.emptyObject() : folded;
        }
        TreeNode patch = (envPatchRaw == null || envPatchRaw.trim().isEmpty())
                ? format.emptyObject() : format.parse(envPatchRaw);
        TreeNode merged = TreeMergePatch.apply(accumulator, patch);
        return merged == null ? format.emptyObject() : merged;
    }

    /**
     * @param commonContentRaw full nested content of the common Config Set's chosen version, in
     *                          {@code contentType}'s format.
     * @param envPatchRaw       sparse RFC 7396 merge-patch overlay content of the env Config Set's
     *                          chosen version, or {@code null}/blank if the env layer has no active
     *                          version yet (treated as an empty overlay).
     * @return the effective merged configuration as a {@link TreeNode}.
     */
    public static TreeNode resolve(ContentType contentType, String commonContentRaw, String envPatchRaw) {
        return resolveChain(contentType,
                Collections.singletonList(TreeFormats.forType(contentType).parse(commonContentRaw)), envPatchRaw);
    }
}
