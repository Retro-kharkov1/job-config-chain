package io.github.retrokharkov1.configtemplatesync.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Records exactly which base chain (frozen as concrete {@link ResolvedBaseVersion} entries) and
 * own-config-version-number were used the last time a given Run substituted for real (FR-23,
 * FR-25). Re-keyed to a pure Run-identity string (tech-lead scoping decision, 2026-09-14,
 * pipeline-steps.md §1) once every pipeline call started resolving per-Job by construction — a
 * binding is inherently per-Job already (the Run that wrote it belongs to exactly one Job), so no
 * separate Config-Key/environment field is needed to disambiguate it. Default-on write, not opt-in
 * (see pipeline-steps.md's "Build-identity pinning" section).
 */
public class ConfigDeploymentBinding implements Serializable {

    private static final long serialVersionUID = 3L;

    private final String buildVersion;

    /**
     * @deprecated legacy single-base field. Kept ONLY so a {@code deployment-bindings.xml} document
     * written before the multi-base chain feature still deserializes without a ConversionException.
     * Never written to by any current code path ({@link #update} and the current constructor always
     * populate {@link #resolvedBaseChain} instead). Read exactly once, by {@link #readResolve()}, to
     * synthesize a one-element chain for a pre-migration record; not read anywhere else in this
     * codebase. Do not remove this field without first confirming no surviving pre-2026-09
     * {@code deployment-bindings.xml} needs to load. Unrelated to, and untouched by, the 2026-09-14
     * projectKey/environment re-key — see that field's own removal note below.
     */
    @Deprecated
    private int commonVersionNumber;

    private List<ResolvedBaseVersion> resolvedBaseChain;
    private int ownConfigVersionNumber;
    private long deployedAtUtcEpochMillis;

    public ConfigDeploymentBinding(String buildVersion, List<ResolvedBaseVersion> resolvedBaseChain,
                                    int ownConfigVersionNumber, long deployedAtUtcEpochMillis) {
        this.buildVersion = Objects.requireNonNull(buildVersion, "buildVersion");
        this.resolvedBaseChain = copyOf(resolvedBaseChain);
        this.ownConfigVersionNumber = ownConfigVersionNumber;
        this.deployedAtUtcEpochMillis = deployedAtUtcEpochMillis;
    }

    private static List<ResolvedBaseVersion> copyOf(List<ResolvedBaseVersion> source) {
        return Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(source, "resolvedBaseChain")));
    }

    public String getBuildVersion() {
        return buildVersion;
    }

    /** Never null. Order-significant, mirrors the resolved baseChain at substitution time. */
    public List<ResolvedBaseVersion> getResolvedBaseChain() {
        return resolvedBaseChain;
    }

    /**
     * @deprecated since the multi-base chain feature — a binding may now freeze zero, one, or many
     * base Config Sets (see {@link #getResolvedBaseChain()}), so "the" common version number is no
     * longer well-defined in general. Kept for source/API compatibility with any existing external
     * caller (e.g. a Groovy/Script-Console reader of this persisted object). Returns the FIRST entry's
     * {@code versionNumber} in {@link #getResolvedBaseChain()}, or {@code 0} if the chain is empty.
     * New code MUST call {@link #getResolvedBaseChain()} instead.
     */
    @Deprecated
    public int getCommonVersionNumber() {
        return resolvedBaseChain.isEmpty() ? 0 : resolvedBaseChain.get(0).getVersionNumber();
    }

    /**
     * The calling Job's own resolved config version number used for this binding, or {@code 0} when
     * the Job's own config was not consulted at all (matrix rows 6/7 — direct global COMMON lookup
     * via {@code useBase`+`configKey`). Renamed from {@code getEnvVersionNumber()} (2026-09-14) — same
     * "0 means not applicable" convention, not a new one.
     */
    public int getOwnConfigVersionNumber() {
        return ownConfigVersionNumber;
    }

    public long getDeployedAtUtcEpochMillis() {
        return deployedAtUtcEpochMillis;
    }

    /** Updates this binding's frozen chain/own-config version/timestamp in place (used-not-duplicated on repeat). */
    public void update(List<ResolvedBaseVersion> resolvedBaseChain, int ownConfigVersionNumber,
                        long deployedAtUtcEpochMillis) {
        this.resolvedBaseChain = copyOf(resolvedBaseChain);
        this.ownConfigVersionNumber = ownConfigVersionNumber;
        this.deployedAtUtcEpochMillis = deployedAtUtcEpochMillis;
    }

    /** True if this binding matches the given buildVersion (a Run's own identity string). */
    public boolean matches(String buildVersion) {
        return this.buildVersion.equals(buildVersion);
    }

    /**
     * Migrates a pre-multi-base-chain record on load (FR-23's amendment note). {@code resolvedBaseChain}
     * is {@code null} here — never merely empty — only when XStream deserialized an old-shape document
     * (the field did not exist in that XML, so {@code RobustReflectionConverter} leaves it at its
     * no-arg default). A binding created by this feature's own constructor/{@link #update} can never
     * have a null chain (both reject {@code null} via {@link #copyOf}), so this branch unambiguously
     * means "pre-dates this feature," never "someone legitimately set it to null."
     */
    private Object readResolve() {
        if (resolvedBaseChain == null) {
            // commonVersionNumber's own projectKey namesake no longer exists as a field on this class
            // (removed with the 2026-09-14 re-key) — this pre-migration synthesis only ever needed
            // commonVersionNumber itself, never projectKey, so the removal does not affect this
            // branch's INTENT. It does affect the literal value, though: ResolvedBaseVersion's
            // constructor has always required a non-null projectKey (an invariant this plan never
            // touched), so passing the no-longer-available real value is not an option. A fixed
            // placeholder is safe here — this synthesized single-entry chain exists purely for the
            // deprecated getCommonVersionNumber()/getResolvedBaseChain() API-compat readers, which
            // only ever read the versionNumber back out, never this placeholder projectKey string.
            List<ResolvedBaseVersion> migrated = commonVersionNumber > 0
                    ? Collections.singletonList(new ResolvedBaseVersion("legacy", commonVersionNumber))
                    : Collections.emptyList();
            return new ConfigDeploymentBinding(buildVersion, migrated,
                    ownConfigVersionNumber, deployedAtUtcEpochMillis);
        }
        return this;
    }
}
