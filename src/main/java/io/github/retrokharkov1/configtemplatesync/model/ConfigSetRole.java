package io.github.retrokharkov1.configtemplatesync.model;

/**
 * The role a {@link ConfigSet} can take. A Config Set's role is a field on the same Java type,
 * never a separate subtype — see the requirements doc entities section.
 *
 * <p>{@code ENV} was removed in full (2026-09-14) once the Job-scoped pipeline-call resolution
 * model shipped — see {@code removal-candidates.md}'s "The global env-level admin screen — FULL
 * ENTITY REMOVAL" section. Only {@code COMMON} remains.</p>
 */
public enum ConfigSetRole {
    /** Full nested baseline shape+values shared across all environments for a Config Project. */
    COMMON
}
