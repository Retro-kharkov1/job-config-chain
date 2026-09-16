package io.jenkins.plugins.jobconfigchain.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * One entry in an env {@link ConfigSetVersion}'s ordered {@code baseChain} (see base-chains.md's
 * "Multi-base config chains" section): operator
 * *intent* at save time — which base Config Set, and whether to always track its active version or
 * pin to one specific historical version. Order within the owning list is significant (later
 * entries win on overlapping keys) but is a property of the LIST, not of this value type.
 */
public final class BaseConfigReference implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String projectKey;
    private final PinMode pinMode;
    private final int pinnedVersionNumber;

    public BaseConfigReference(String projectKey, PinMode pinMode, int pinnedVersionNumber) {
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        if (projectKey.trim().isEmpty()) {
            throw new IllegalArgumentException("projectKey must not be empty");
        }
        this.pinMode = Objects.requireNonNull(pinMode, "pinMode");
        if (pinMode == PinMode.PINNED && pinnedVersionNumber <= 0) {
            throw new IllegalArgumentException(
                    "pinnedVersionNumber must be a positive version number when pinMode is PINNED");
        }
        // ACTIVE carries no meaningful pinned number — normalize to 0 so equals()/hashCode()
        // never depend on a value the UI/caller was never asked to supply meaningfully.
        this.pinnedVersionNumber = pinMode == PinMode.PINNED ? pinnedVersionNumber : 0;
    }

    public static BaseConfigReference active(String projectKey) {
        return new BaseConfigReference(projectKey, PinMode.ACTIVE, 0);
    }

    public static BaseConfigReference pinned(String projectKey, int versionNumber) {
        return new BaseConfigReference(projectKey, PinMode.PINNED, versionNumber);
    }

    public String getProjectKey() {
        return projectKey;
    }

    public PinMode getPinMode() {
        return pinMode;
    }

    public int getPinnedVersionNumber() {
        return pinnedVersionNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseConfigReference)) return false;
        BaseConfigReference that = (BaseConfigReference) o;
        return pinnedVersionNumber == that.pinnedVersionNumber
                && projectKey.equals(that.projectKey)
                && pinMode == that.pinMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectKey, pinMode, pinnedVersionNumber);
    }

    /** Human-readable form used in the pipeline's per-reference fallback log line and the version-history display. */
    @Override
    public String toString() {
        return pinMode == PinMode.PINNED
                ? projectKey + "@PINNED(v" + pinnedVersionNumber + ")"
                : projectKey + "@ACTIVE";
    }
}
