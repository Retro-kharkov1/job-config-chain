package io.jenkins.plugins.jobconfigchain.persistence;

/**
 * Thrown when an ordinary save would have written over a soft-deleted Config Set.
 *
 * <p>A deleted Config Set keeps its own file at its own storage key, and that occupancy is what
 * reserves the name: creating a new Config Set with the key of a deleted one is refused until the
 * old one is restored or purged. This exception is the enforcement of that rule, and it lives in
 * the repository rather than in the UI on purpose — every creation and every version append funnels
 * through one {@code save}, so no path, present or future, can silently clobber an archived
 * history. Callers that reach it through the UI are expected to refuse earlier with a readable
 * message; this is the backstop that makes the guarantee unconditional rather than a convention.</p>
 */
public class ConfigSetDeletedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String storageKey;

    public ConfigSetDeletedException(String storageKey) {
        super("Config Set '" + storageKey + "' is deleted. Restore it or purge it before writing to "
                + "this key — overwriting would destroy its archived version history.");
        this.storageKey = storageKey;
    }

    public String getStorageKey() {
        return storageKey;
    }
}
