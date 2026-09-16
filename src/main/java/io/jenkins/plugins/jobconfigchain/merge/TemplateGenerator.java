package io.jenkins.plugins.jobconfigchain.merge;

import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormat;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.model.ContentType;

import java.util.Map;

/**
 * Generates a copy-paste-ready template (see pipeline-steps.md's "Template generation" section) by
 * walking a Config Set's content tree and
 * replacing every leaf with its {@code #{Dotted.Path}#} token — the identical token syntax already
 * produced/consumed by {@link TokenExtractor#TOKEN_PATTERN} for validate and substitute,
 * so a generated template round-trips through that same drift-detection logic with zero
 * translation. Format-neutral over {@link TreeNode} — every leaf/shape edge case here
 * mirrors {@link io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths#flatten}'s already-
 * approved semantics (empty object at any path contributes no token and renders as an empty object
 * unchanged; a whole-value leaf — array/attribute/repeated-sibling/sequence — is tokenized as one
 * leaf, never recursed into element-by-element).
 *
 * <p><b>Deliberate, explicit divergence from {@code TreePaths.flatten}:</b> a {@code null}-valued
 * leaf here IS tokenized like any other leaf, whereas {@code flatten} stores the null node verbatim
 * (used by validate/substitute's flatten-and-compare, where a {@code null} literally appearing in an
 * EFFECTIVE (merged) configuration is a real, comparable value). This is intentional: the RFC 7396
 * "{@code null} removes" rule governs how an ENV overlay relates to the COMMON layer
 * underneath it — it does not apply to how a single Config Set's own stored content is walked for
 * template generation (see pipeline-steps.md's "Template generation" section, which calls this out
 * explicitly).</p>
 */
public final class TemplateGenerator {

    private TemplateGenerator() {
    }

    /**
     * Walks {@code content}'s own nested shape directly (no merge), replacing every leaf
     * (secret-manifest-bound or not, {@code null} included — see class javadoc) with its
     * {@code #{Dotted.Path}#} token.
     *
     * @param content a Config Set version's own stored content (must be an object/root element at the
     *                root, in {@code type}'s format).
     * @return the same nested shape, every leaf replaced by its dotted-path token.
     */
    public static TreeNode fromContent(String content, ContentType type) {
        TreeFormat format = TreeFormats.forType(type);
        TreeNode parsed = format.parse(content);
        if (!parsed.isObject()) {
            throw new IllegalArgumentException("Content must have a top-level object/root element");
        }
        return tokenize(parsed, "", format);
    }

    /**
     * Resolves the effective (merged) configuration via
     * {@link EffectiveConfigResolver#resolve(ContentType, String, String)} (no second,
     * divergent merge implementation), then applies the identical leaf-tokenization walk used by
     * {@link #fromContent(String, ContentType)} to the merged result.
     */
    public static TreeNode fromEffective(ContentType type, String commonContentRaw, String envPatchRaw) {
        TreeNode effective = EffectiveConfigResolver.resolve(type, commonContentRaw, envPatchRaw);
        return fromEffective(effective, type);
    }

    /**
     * Generalized to a multi-base chain (see base-chains.md): applies the identical leaf-tokenization walk
     * to an already-resolved effective (merged) configuration — e.g. one computed by
     * {@link EffectiveConfigResolver#resolveChain}, so callers resolving a base chain never need to
     * re-parse content just to reach this class's tokenization logic.
     */
    public static TreeNode fromEffective(TreeNode effective, ContentType type) {
        return tokenize(effective, "", TreeFormats.forType(type));
    }

    /**
     * The single internal tokenization walk, shared by every public entry point above — exactly one
     * tokenization implementation inside this class, never two.
     */
    private static TreeNode tokenize(TreeNode element, String prefix, TreeFormat format) {
        if (element.isObject()) {
            Map<String, TreeNode> children = element.childrenIfObject();
            if (children.isEmpty()) {
                // Empty object at any path (including the root) renders as {} unchanged — an empty
                // object has no leaves to contribute a token for.
                return format.emptyObject();
            }
            TreeNode out = format.emptyObject();
            for (Map.Entry<String, TreeNode> entry : children.entrySet()) {
                String childPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                out.putChild(entry.getKey(), tokenize(entry.getValue(), childPath, format));
            }
            return out;
        }
        // Every non-object leaf — primitive, null, or whole-value (array/attribute/repeated-sibling/
        // sequence) — is tokenized as one whole-leaf token at its own path. Never recursed into
        // element-by-element. null leaves ARE tokenized here (see class javadoc).
        return format.leaf("#{" + prefix + "}#");
    }
}
