package io.github.retrokharkov1.configtemplatesync;

import java.io.Serializable;
import java.util.Objects;

/**
 * One append-only, immutable-content snapshot of a {@link ConfigSet}'s JSON body.
 *
 * <p>{@code versionNumber} is monotonically increasing per {@link ConfigSet} (max+1, never
 * reused). {@code contentJson} is a nested JSON string matching the shape of the target
 * appsettings-style file this ConfigSet feeds; secret leaves hold the literal marker
 * {@code "__SECRET__"} — real secret values never enter this field, they live only in Jenkins
 * credentials referenced by the owning {@link ConfigSet}'s secrets manifest.
 *
 * <p>{@code active} is mutable in place: exactly one version per ConfigSet must have
 * {@code active == true} at all times (invariant enforced by {@link ConfigSet}), and
 * "rollback" is implemented by flipping this flag back onto an older version, not by cloning
 * content into a new version.
 */
public class ConfigSetVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int versionNumber;
    private final String contentJson;
    private final String note;
    private final String author;
    private final long timestamp;
    private boolean active;

    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestamp, boolean active) {
        this.versionNumber = versionNumber;
        this.contentJson = Objects.requireNonNull(contentJson, "contentJson");
        this.note = note;
        this.author = author;
        this.timestamp = timestamp;
        this.active = active;
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

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return "ConfigSetVersion{v=" + versionNumber + ", active=" + active
                + ", author='" + author + "', note='" + note + "'}";
    }
}
