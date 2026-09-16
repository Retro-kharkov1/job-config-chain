package io.jenkins.plugins.jobconfigchain.model;

/**
 * Whether a {@link BaseConfigReference} always tracks its target Config Set's currently active
 * version, or pins to one specific historical version number (see base-chains.md's "Multi-base
 * config chains" section).
 */
public enum PinMode {
    ACTIVE,
    PINNED
}
