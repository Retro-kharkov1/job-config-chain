package io.github.retrokharkov1.configtemplatesync.model;

/**
 * Whether a {@link BaseConfigReference} always tracks its target Config Set's currently active
 * version, or pins to one specific historical version number (FR-51).
 */
public enum PinMode {
    ACTIVE,
    PINNED
}
