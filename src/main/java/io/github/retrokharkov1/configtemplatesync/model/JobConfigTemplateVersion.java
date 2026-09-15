package io.github.retrokharkov1.configtemplatesync.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, append-only snapshot of one {@code Job}'s Config Templates content at a point in
 * time — the job-scoped sibling of {@link ConfigSetVersion} (tech-lead design contract,
 * 2026-09-09). Version numbers are monotonically increasing and never reused or renumbered, and
 * instances are never mutated after construction, exactly mirroring {@link ConfigSetVersion}'s own
 * contract.
 *
 * <p><b>Deliberately no {@code explicitlyStandalone} field.</b> {@link ConfigSetVersion}'s flag
 * exists to disambiguate "zero base configs on purpose" from "empty/absent baseChain defaults to
 * one self-referencing ACTIVE entry" (FR-52/FR-86/FR-87) — a real ambiguity only because an
 * ENV-role {@link ConfigSet} implicitly self-references its own paired project when its chain is
 * empty. A {@code Job} has no such implicit self-reference to guard against: an empty
 * {@link #baseChain} on a job version unambiguously means "no bases," full stop. This is a real
 * simplification of the job-scoped model, not an omission.</p>
 */
public class JobConfigTemplateVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int versionNumber;
    private final String contentJson;
    private final String note;
    private final String author;
    private final long timestampEpochMillis;
    private final List<BaseConfigReference> baseChain;

    public JobConfigTemplateVersion(int versionNumber, String contentJson, String note, String author,
                                     long timestampEpochMillis, List<BaseConfigReference> baseChain) {
        if (note == null || note.trim().isEmpty()) {
            // Same non-empty-note rule as ConfigSetVersion (FR-5).
            throw new IllegalArgumentException("Change note must not be empty");
        }
        this.versionNumber = versionNumber;
        this.contentJson = Objects.requireNonNull(contentJson, "contentJson");
        this.note = note;
        this.author = Objects.requireNonNull(author, "author");
        this.timestampEpochMillis = timestampEpochMillis;
        this.baseChain = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(baseChain, "baseChain")));
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

    /**
     * Never null — an empty list unambiguously means "no bases" for a job version (see class
     * javadoc). Never defaults to a synthesized self-referencing entry, unlike
     * {@code io.github.retrokharkov1.configtemplatesync.ui.EnvConfigSetPage}'s own FR-52 default —
     * a job has no paired project of its own to fall back to.
     */
    public List<BaseConfigReference> getBaseChain() {
        return baseChain;
    }

    /**
     * XStream2/Jenkins persistence idiom for evolving a persisted class — mirrors
     * {@link ConfigSetVersion#readResolve()} exactly: XML written before {@code baseChain} existed
     * on this type has no {@code <baseChain>} element, so it lands {@code null} after
     * reflection-based deserialization (XStream bypasses the constructor entirely). Rebuild via the
     * real constructor so {@link #getBaseChain()} can always be treated as non-null. A version this
     * feature's own code creates always has an empty (never null) list, so "null after
     * deserialization" unambiguously means "pre-dates this field."
     */
    private Object readResolve() {
        if (baseChain == null) {
            return new JobConfigTemplateVersion(versionNumber, contentJson, note, author,
                    timestampEpochMillis, Collections.emptyList());
        }
        return this;
    }
}
