package io.github.retrokharkov1.configtemplatesync.merge;

import java.util.Collections;
import java.util.Set;

/**
 * Result of comparing the effective configuration's flattened key set against the actual
 * {@code #{...}#} tokens found in a target file (FR-17).
 */
public final class DriftResult {

    private final Set<String> missingKeys;
    private final Set<String> orphanedKeys;

    public DriftResult(Set<String> missingKeys, Set<String> orphanedKeys) {
        this.missingKeys = Collections.unmodifiableSet(missingKeys);
        this.orphanedKeys = Collections.unmodifiableSet(orphanedKeys);
    }

    /** Tokens present in the target file with no matching key in the effective configuration. */
    public Set<String> getMissingKeys() {
        return missingKeys;
    }

    /** Keys present in the effective configuration with no matching token in the target file. */
    public Set<String> getOrphanedKeys() {
        return orphanedKeys;
    }

    public boolean hasMissingKeys() {
        return !missingKeys.isEmpty();
    }

    public boolean hasOrphanedKeys() {
        return !orphanedKeys.isEmpty();
    }
}
