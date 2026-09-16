package io.jenkins.plugins.jobconfigchain.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * One already-concrete {@code (projectKey, versionNumber)} pair frozen into a
 * {@link ConfigDeploymentBinding#getResolvedBaseChain()} entry (see pipeline-steps.md's
 * "Build-identity pinning" section). Never carries a pin
 * mode — by the time this type exists, "active vs. pinned" has already been resolved to a number.
 */
public final class ResolvedBaseVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String projectKey;
    private final int versionNumber;

    public ResolvedBaseVersion(String projectKey, int versionNumber) {
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        if (projectKey.trim().isEmpty()) {
            throw new IllegalArgumentException("projectKey must not be empty");
        }
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber must be a positive, concrete version number");
        }
        this.versionNumber = versionNumber;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolvedBaseVersion)) return false;
        ResolvedBaseVersion that = (ResolvedBaseVersion) o;
        return versionNumber == that.versionNumber && projectKey.equals(that.projectKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectKey, versionNumber);
    }

    @Override
    public String toString() {
        return projectKey + "@v" + versionNumber;
    }
}
