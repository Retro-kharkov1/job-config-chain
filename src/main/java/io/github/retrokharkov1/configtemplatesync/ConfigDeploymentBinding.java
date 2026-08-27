package io.github.retrokharkov1.configtemplatesync;

import java.io.Serializable;

/**
 * Records which exact common+env {@link ConfigSetVersion} numbers were used for a given
 * {@code buildVersion} the last time {@code configTemplateSubstitute} actually ran a real
 * substitution for that build. This is what lets "redeploy build 1.2.3" replay the exact config
 * 1.2.3 originally shipped with, instead of whatever ConfigSet version happens to be "active" now
 * (which may have moved on and no longer be valid for older code).
 *
 * <p>{@code projectEnvKey} is the env-layer {@link ConfigSet} key (e.g. {@code "myapp-dev"})
 * — bindings are scoped per project+env, not per common layer, since one common ConfigSet is
 * typically shared across several envs.
 *
 * <p>{@code commonVersionNumber} is {@code null} when the substitution ran with no common
 * ConfigSet at all (blank {@code commonConfigSet} parameter) — mirrors
 * {@link EffectiveConfigResolver}'s existing "common layer is optional" contract.
 */
public class ConfigDeploymentBinding implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String projectEnvKey;
    private final String buildVersion;
    private final Integer commonVersionNumber;
    private final int envVersionNumber;
    private final long deployedAtUtc;

    public ConfigDeploymentBinding(String projectEnvKey, String buildVersion,
                                    Integer commonVersionNumber, int envVersionNumber,
                                    long deployedAtUtc) {
        this.projectEnvKey = projectEnvKey;
        this.buildVersion = buildVersion;
        this.commonVersionNumber = commonVersionNumber;
        this.envVersionNumber = envVersionNumber;
        this.deployedAtUtc = deployedAtUtc;
    }

    public String getProjectEnvKey() {
        return projectEnvKey;
    }

    public String getBuildVersion() {
        return buildVersion;
    }

    public Integer getCommonVersionNumber() {
        return commonVersionNumber;
    }

    public int getEnvVersionNumber() {
        return envVersionNumber;
    }

    public long getDeployedAtUtc() {
        return deployedAtUtc;
    }

    @Override
    public String toString() {
        return "ConfigDeploymentBinding{projectEnvKey='" + projectEnvKey + "', buildVersion='"
                + buildVersion + "', commonVersionNumber=" + commonVersionNumber
                + ", envVersionNumber=" + envVersionNumber + ", deployedAtUtc=" + deployedAtUtc + "}";
    }
}
