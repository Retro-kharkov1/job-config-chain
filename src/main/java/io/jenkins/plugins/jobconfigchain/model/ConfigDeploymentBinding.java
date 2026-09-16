package io.jenkins.plugins.jobconfigchain.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Records exactly which base chain (frozen as concrete {@link ResolvedBaseVersion} entries) and
 * own-config-version-number were used the last time a given Run substituted for real (see
 * pipeline-steps.md's "Build-identity pinning (rollback safety)" section). Re-keyed to a pure
 * Run-identity string (tech-lead scoping decision, 2026-09-14,
 * pipeline-steps.md §1) once every pipeline call started resolving per-Job by construction — a
 * binding is inherently per-Job already (the Run that wrote it belongs to exactly one Job), so no
 * separate Config-Key/environment field is needed to disambiguate it. Default-on write, not opt-in
 * (see pipeline-steps.md's "Build-identity pinning" section).
 */
public class ConfigDeploymentBinding implements Serializable {

    private static final long serialVersionUID = 3L;

    private final String buildVersion;

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
     * The calling Job's own resolved config version number used for this binding, or {@code 0} when
     * the Job's own config was not consulted at all (resolution-matrix rows 6/7 — direct global COMMON
     * lookup via {@code useBase}+{@code configKey}, see pipeline-steps.md's resolution matrix).
     * Renamed from {@code getEnvVersionNumber()} (2026-09-14) — same
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
}
