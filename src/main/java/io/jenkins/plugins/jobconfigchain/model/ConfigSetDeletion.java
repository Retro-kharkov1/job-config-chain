package io.jenkins.plugins.jobconfigchain.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Who withdrew a {@link ConfigSet} from service, and when.
 *
 * <p>Deletion in this plugin is reversible: the record keeps its entire append-only version history
 * and its secrets manifest, and an administrator can restore it or purge it permanently. This type
 * carries the audit trail that makes the difference between the two states legible — a restore is
 * an administrative action worth attributing, and the deleted rows on the global list page show
 * both fields.</p>
 *
 * <p>Its presence on a {@link ConfigSet} <em>is</em> the deleted flag: {@code null} means live. A
 * nullable reference rather than a {@code boolean} is deliberate, and not only to carry the two
 * fields — it is also what makes every Config Set persisted before this feature existed read back
 * correctly with no migration, because XStream leaves an absent element at the field's Java
 * default, and for a reference that default is exactly the "live" state. See
 * {@code ConfigSet#readResolve()}, which had to patch {@code contentType} precisely because
 * {@code null} was not a valid value there.</p>
 */
public final class ConfigSetDeletion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String deletedBy;

    /** Epoch milliseconds, matching {@code ConfigSetVersion#timestampEpochMillis}'s convention. */
    private final long deletedAtEpochMillis;

    public ConfigSetDeletion(String deletedBy, long deletedAtEpochMillis) {
        this.deletedBy = Objects.requireNonNull(deletedBy, "deletedBy");
        this.deletedAtEpochMillis = deletedAtEpochMillis;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public long getDeletedAtEpochMillis() {
        return deletedAtEpochMillis;
    }

    @Override
    public String toString() {
        return "ConfigSetDeletion{deletedBy='" + deletedBy + "', deletedAtEpochMillis="
                + deletedAtEpochMillis + "}";
    }
}
