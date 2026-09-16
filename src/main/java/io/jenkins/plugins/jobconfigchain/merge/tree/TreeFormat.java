package io.jenkins.plugins.jobconfigchain.merge.tree;

import io.jenkins.plugins.jobconfigchain.model.ContentType;

/**
 * One content type's parse/serialize contract (see multi-format-content.md's "Content type model"
 * section). Registered per {@link ContentType} via
 * {@link TreeFormats#forType(ContentType)} — the single dispatch point every content-reading call
 * site in this plugin MUST go through, never a hardcoded Gson/DOM/SnakeYAML call outside
 * this package.
 */
public interface TreeFormat {
    ContentType contentType();

    /**
     * @throws RuntimeException a format-specific UNCHECKED syntax exception (e.g.
     *         com.google.gson.JsonSyntaxException, this package's own XmlSyntaxException, or
     *         org.yaml.snakeyaml.error.YAMLException) on malformed content. Callers dispatch through
     *         {@code validateSyntaxOrFail}, which catches {@link RuntimeException} uniformly across
     *         all three formats rather than three format-specific catch clauses.
     */
    TreeNode parse(String content);

    /** A fresh, empty object-rooted node — used to seed a chain fold's initial accumulator. */
    TreeNode emptyObject();

    /**
     * A fresh leaf node of this format's concrete type, holding {@code value} verbatim — used by
     * {@code TemplateGenerator}'s tokenization walk, which must construct a brand-new leaf without an
     * existing sibling node to clone from.
     */
    TreeNode leaf(String value);

    /** Pretty-printed, canonical serialization for this format — the ONLY serialization path. */
    String serialize(TreeNode root);
}
