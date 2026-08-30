package io.github.retrokharkov1.configtemplatesync.model;

/**
 * The two (and only two) roles a {@link ConfigSet} can take. A Config Set's role is a field on the
 * same Java type, never a separate subtype — see the requirements doc entities section.
 */
public enum ConfigSetRole {
    /** Full nested baseline shape+values shared across all environments for a Config Project. */
    COMMON,
    /** A sparse RFC 7396 JSON Merge Patch overlay scoped to one specific environment. */
    ENV
}
