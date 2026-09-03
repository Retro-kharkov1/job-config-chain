package io.github.retrokharkov1.configtemplatesync.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, append-only snapshot of one {@link ConfigSet}'s content at a point in time
 * (FR-2). Version numbers are monotonically increasing and never reused or renumbered (FR-4).
 * Instances are never mutated after construction — "rollback" only ever repoints
 * {@link ConfigSet}'s active version number, it never edits an existing version's fields.
 */
public class ConfigSetVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int versionNumber;
    private final String contentJson;
    private final String note;
    private final String author;
    private final long timestampEpochMillis;
    private final List<BaseConfigReference> baseChain;
    private final boolean explicitlyStandalone;

    /** Pre-existing 5-arg constructor — unchanged call sites keep compiling; defaults baseChain to empty. */
    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestampEpochMillis) {
        this(versionNumber, contentJson, note, author, timestampEpochMillis, Collections.emptyList(), false);
    }

    /** Pre-existing 6-arg constructor (FR-51) — unchanged call sites keep compiling; defaults explicitlyStandalone false. */
    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestampEpochMillis, List<BaseConfigReference> baseChain) {
        this(versionNumber, contentJson, note, author, timestampEpochMillis, baseChain, false);
    }

    /** New overload (FR-86): persists explicitlyStandalone as part of this version's own immutable record. */
    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestampEpochMillis, List<BaseConfigReference> baseChain,
                             boolean explicitlyStandalone) {
        if (note == null || note.trim().isEmpty()) {
            // FR-5: non-empty change note is mandatory on every saved version.
            throw new IllegalArgumentException("Change note must not be empty");
        }
        this.versionNumber = versionNumber;
        this.contentJson = Objects.requireNonNull(contentJson, "contentJson");
        this.note = note;
        this.author = Objects.requireNonNull(author, "author");
        this.timestampEpochMillis = timestampEpochMillis;
        this.baseChain = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(baseChain, "baseChain")));
        this.explicitlyStandalone = explicitlyStandalone;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getContentJson() {
        return contentJson;
    }

    public String getNote() {
        return note;
    }

    public String getAuthor() {
        return author;
    }

    public long getTimestampEpochMillis() {
        return timestampEpochMillis;
    }

    /** Never null (FR-52) — empty for a COMMON-role version (FR-53) or a pre-chain env version. */
    public List<BaseConfigReference> getBaseChain() {
        return baseChain;
    }

    /**
     * FR-86: a deliberate, first-class save-time declaration (meaningful only on an ENV-role
     * version) that this version has zero base configs and its own content IS the entire effective
     * configuration — distinct from FR-52's "empty/absent baseChain defaults to one self-referencing
     * ACTIVE entry" rule (see FR-87/{@code StepSupport#effectiveBaseChain}). A primitive {@code
     * boolean} — old XML missing this field deserializes to {@code false} automatically (FR-88), no
     * {@code readResolve()} logic needed for this field specifically (unlike {@code baseChain}, a
     * reference type).
     */
    public boolean isExplicitlyStandalone() {
        return explicitlyStandalone;
    }

    /**
     * XStream2/Jenkins persistence idiom for evolving a persisted class: XML written before this
     * field existed simply has no {@code <baseChain>} element, so {@code baseChain} lands {@code null}
     * after reflection-based deserialization (XStream bypasses the constructor entirely). Rebuild via
     * the real constructor so every other reader can treat {@link #getBaseChain()} as always non-null
     * (FR-52) without a null-check anywhere else in the codebase. A version this feature's own code
     * creates always has an empty (never null) list, so "null after deserialization" unambiguously
     * means "pre-dates this feature" here, never a legitimate empty-on-purpose case that got lost.
     * {@code explicitlyStandalone} is passed through (rather than hardcoded {@code false}) as
     * defensive precision, even though any document old enough to have {@code baseChain == null}
     * necessarily predates FR-86 too.
     */
    private Object readResolve() {
        if (baseChain == null) {
            return new ConfigSetVersion(versionNumber, contentJson, note, author, timestampEpochMillis,
                    Collections.emptyList(), explicitlyStandalone);
        }
        return this;
    }
}
