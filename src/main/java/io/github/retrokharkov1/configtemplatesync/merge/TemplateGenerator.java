package io.github.retrokharkov1.configtemplatesync.merge;

import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormat;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormats;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;

import java.util.Map;

/**
 * Generates a copy-paste-ready template (FR-15) by walking a Config Set's content tree and
 * replacing every leaf with its {@code #{Dotted.Path}#} token — the identical token syntax already
 * produced/consumed by {@link TokenExtractor#TOKEN_PATTERN} for validate (FR-17) and substitute
 * (FR-21), so a generated template round-trips through that same drift-detection logic with zero
 * translation. Format-neutral over {@link TreeNode} (FR-66) — every leaf/shape edge case here
 * mirrors {@link io.github.retrokharkov1.configtemplatesync.merge.tree.TreePaths#flatten}'s already-
 * approved semantics (empty object at any path contributes no token and renders as an empty object
 * unchanged; a whole-value leaf — array/attribute/repeated-sibling/sequence — is tokenized as one
 * leaf, never recursed into element-by-element, per OQ-8).
 *
 * <p><b>Deliberate, explicit divergence from {@code TreePaths.flatten}:</b> a {@code null}-valued
 * leaf here IS tokenized like any other leaf, whereas {@code flatten} stores the null node verbatim
 * (used by validate/substitute's flatten-and-compare, where a {@code null} literally appearing in an
 * EFFECTIVE (merged) configuration is a real, comparable value). This is intentional: the RFC 7396
 * "{@code null} removes" rule (FR-9) governs how an ENV overlay relates to the COMMON layer
 * underneath it — it does not apply to how a single Config Set's own stored content is walked for
 * template generation (FR-15's own text calls this out explicitly).</p>
 */
public final class TemplateGenerator {

    private TemplateGenerator() {
    }

    /**
     * FR-15a: walks {@code content}'s own nested shape directly (no merge), replacing every leaf
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
     * FR-15b: resolves the effective (merged) configuration via
     * {@link EffectiveConfigResolver#resolve(ContentType, String, String)} (FR-16 — no second,
     * divergent merge implementation), then applies the identical leaf-tokenization walk used by
     * {@link #fromContent(String, ContentType)} to the merged result.
     */
    public static TreeNode fromEffective(ContentType type, String commonContentRaw, String envPatchRaw) {
        TreeNode effective = EffectiveConfigResolver.resolve(type, commonContentRaw, envPatchRaw);
        return fromEffective(effective, type);
    }

    /**
     * FR-15b generalized to a multi-base chain (FR-51): applies the identical leaf-tokenization walk
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
        // element-by-element (OQ-8). null leaves ARE tokenized here (see class javadoc).
        return format.leaf("#{" + prefix + "}#");
    }
}
