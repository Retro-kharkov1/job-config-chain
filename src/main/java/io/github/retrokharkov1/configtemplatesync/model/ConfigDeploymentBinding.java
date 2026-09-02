package io.github.retrokharkov1.configtemplatesync.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Records exactly which base chain (frozen as concrete {@link ResolvedBaseVersion} entries) and
 * env-version-number were used the last time a given (projectKey, environment, buildVersion) tuple
 * was substituted for real (FR-23, FR-25). Pinning is opt-in — a binding is only ever consulted when
 * a caller explicitly passes {@code buildVersion} to the substitute step (OQ-3, resolved 2026-08-27:
 * opt-in stays for v1).
 */
public class ConfigDeploymentBinding implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String projectKey;
    private final String environment;
    private final String buildVersion;

    /**
     * @deprecated legacy single-base field. Kept ONLY so a {@code deployment-bindings.xml} document
     * written before the multi-base chain feature still deserializes without a ConversionException.
     * Never written to by any current code path ({@link #update} and the current constructor always
     * populate {@link #resolvedBaseChain} instead). Read exactly once, by {@link #readResolve()}, to
     * synthesize a one-element chain for a pre-migration record; not read anywhere else in this
     * codebase. Do not remove this field without first confirming no surviving pre-2026-09
     * {@code deployment-bindings.xml} needs to load.
     */
    @Deprecated
    private int commonVersionNumber;

    private List<ResolvedBaseVersion> resolvedBaseChain;
    private int envVersionNumber;
    private long deployedAtUtcEpochMillis;

    public ConfigDeploymentBinding(String projectKey, String environment, String buildVersion,
                                    List<ResolvedBaseVersion> resolvedBaseChain, int envVersionNumber,
                                    long deployedAtUtcEpochMillis) {
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.buildVersion = Objects.requireNonNull(buildVersion, "buildVersion");
        this.resolvedBaseChain = copyOf(resolvedBaseChain);
        this.envVersionNumber = envVersionNumber;
        this.deployedAtUtcEpochMillis = deployedAtUtcEpochMillis;
    }

    private static List<ResolvedBaseVersion> copyOf(List<ResolvedBaseVersion> source) {
        return Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(source, "resolvedBaseChain")));
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getBuildVersion() {
        return buildVersion;
    }

    /** Never null. Order-significant, mirrors the env version's resolved baseChain at substitution time. */
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

    public int getEnvVersionNumber() {
        return envVersionNumber;
    }

    public long getDeployedAtUtcEpochMillis() {
        return deployedAtUtcEpochMillis;
    }

    /** Updates this binding's frozen chain/env version/timestamp in place (used-not-duplicated on repeat). */
    public void update(List<ResolvedBaseVersion> resolvedBaseChain, int envVersionNumber,
                        long deployedAtUtcEpochMillis) {
        this.resolvedBaseChain = copyOf(resolvedBaseChain);
        this.envVersionNumber = envVersionNumber;
        this.deployedAtUtcEpochMillis = deployedAtUtcEpochMillis;
    }

    /** True if this binding matches the given (projectKey, environment, buildVersion) tuple. */
    public boolean matches(String projectKey, String environment, String buildVersion) {
        return this.projectKey.equals(projectKey)
                && this.environment.equals(environment)
                && this.buildVersion.equals(buildVersion);
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
            List<ResolvedBaseVersion> migrated = commonVersionNumber > 0
                    ? Collections.singletonList(new ResolvedBaseVersion(projectKey, commonVersionNumber))
                    : Collections.emptyList();
            return new ConfigDeploymentBinding(projectKey, environment, buildVersion, migrated,
                    envVersionNumber, deployedAtUtcEpochMillis);
        }
        return this;
    }
}
