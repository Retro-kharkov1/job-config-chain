package io.jenkins.plugins.jobconfigchain.model;

/**
 * The single reserved marker value stored in place of any real secret at any leaf declared secret
 * in a Config Set's secrets manifest. Real values are never persisted (see
 * config-sets-and-versioning.md's "Secrets" section and nfr.md's "No real secret values at rest,
 * ever" rule).
 */
public final class SecretPlaceholder {

    public static final String VALUE = "__SECRET__";

    private SecretPlaceholder() {
    }
}
