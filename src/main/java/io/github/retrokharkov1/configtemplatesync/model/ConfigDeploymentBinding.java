package io.github.retrokharkov1.configtemplatesync.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Records exactly which common-version-number and env-version-number were used the last time a
 * given (projectKey, environment, buildVersion) tuple was substituted for real (FR-23). Pinning is
 * opt-in — a binding is only ever consulted when a caller explicitly passes {@code buildVersion} to
 * the substitute step (OQ-3, resolved 2026-08-27: opt-in stays for v1).
 */
public class ConfigDeploymentBinding implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String projectKey;
    private final String environment;
    private final String buildVersion;
    private int commonVersionNumber;
    private int envVersionNumber;
    private long deployedAtUtcEpochMillis;

    public ConfigDeploymentBinding(String projectKey, String environment, String buildVersion,
                                    int commonVersionNumber, int envVersionNumber,
                                    long deployedAtUtcEpochMillis) {
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.buildVersion = Objects.requireNonNull(buildVersion, "buildVersion");
        this.commonVersionNumber = commonVersionNumber;
        this.envVersionNumber = envVersionNumber;
        this.deployedAtUtcEpochMillis = deployedAtUtcEpochMillis;
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

    public int getCommonVersionNumber() {
        return commonVersionNumber;
    }

    public int getEnvVersionNumber() {
        return envVersionNumber;
    }

    public long getDeployedAtUtcEpochMillis() {
        return deployedAtUtcEpochMillis;
    }

    /** Updates this binding's recorded versions/timestamp in place (used-not-duplicated on repeat). */
    public void update(int commonVersionNumber, int envVersionNumber, long deployedAtUtcEpochMillis) {
        this.commonVersionNumber = commonVersionNumber;
        this.envVersionNumber = envVersionNumber;
        this.deployedAtUtcEpochMillis = deployedAtUtcEpochMillis;
    }

    /** True if this binding matches the given (projectKey, environment, buildVersion) tuple. */
    public boolean matches(String projectKey, String environment, String buildVersion) {
        return this.projectKey.equals(projectKey)
                && this.environment.equals(environment)
                && this.buildVersion.equals(buildVersion);
    }
}
