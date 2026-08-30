package io.github.retrokharkov1.configtemplatesync.model;

/**
 * The single reserved marker value stored in place of any real secret at any leaf declared secret
 * in a Config Set's secrets manifest. Real values are never persisted (FR-12/FR-13, NFR-3).
 */
public final class SecretPlaceholder {

    public static final String VALUE = "__SECRET__";

    private SecretPlaceholder() {
    }
}
