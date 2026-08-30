package io.github.retrokharkov1.configtemplatesync.model;

import java.io.Serializable;
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

    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestampEpochMillis) {
        if (note == null || note.trim().isEmpty()) {
            // FR-5: non-empty change note is mandatory on every saved version.
            throw new IllegalArgumentException("Change note must not be empty");
        }
        this.versionNumber = versionNumber;
        this.contentJson = Objects.requireNonNull(contentJson, "contentJson");
        this.note = note;
        this.author = Objects.requireNonNull(author, "author");
        this.timestampEpochMillis = timestampEpochMillis;
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
}
