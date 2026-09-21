package io.jenkins.plugins.jobconfigchain.model;

import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A named, independently-versioned collection of configuration structured to mirror the shape of
 * one target application config file, per {@code <entities>} in the requirements spec. Common and
 * env Config Sets are the same Java type, distinguished by {@link #role}, never by subtype.
 *
 * <p>Pairing between a common Config Set and its env Config Sets is structural, via
 * {@link #projectKey} — never by free-text name matching.</p>
 */
public class ConfigSet implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Structural identifier tying a common Config Set to its env Config Sets. */
    private final String projectKey;

    private final ConfigSetRole role;

    /**
     * Always {@code null} now that {@code ConfigSetRole#ENV} was removed in full (2026-09-14) — kept
     * as a field (rather than deleted) only because {@link #getStorageKey()} and other pre-existing
     * call sites still reference it structurally; see {@code removal-candidates.md}'s "FULL ENTITY
     * REMOVAL" section.
     */
    private final String environment;

    private String displayName;

    /** Dotted path within this Config Set's content -> Jenkins credential ID supplying the real value. */
    private final Map<String, String> secretsManifest = new LinkedHashMap<>();

    /** Append-only version history (see config-sets-and-versioning.md). Never remove or mutate an entry once added. */
    private final List<ConfigSetVersion> versions = new ArrayList<>();

    /** 0 means "no active version yet"; otherwise exactly one version has this number. */
    private int activeVersionNumber;

    /**
     * This Config Set's structured content format (see multi-format-content.md's "Content type
     * model" section), chosen once at first Save and immutable
     * thereafter. NOT {@code final} — {@link #readResolve()} patches in the backward-compat
     * default for pre-existing content persisted before this feature existed.
     */
    private ContentType contentType;

    /**
     * {@code null} means this Config Set is live; non-null means it has been soft-deleted and
     * carries who did it and when. Deletion is reversible and deliberately leaves everything else
     * on this object untouched — the whole premise is that the history survives a withdrawal.
     *
     * <p>No {@link #readResolve()} patching is needed for it, unlike {@link #contentType}: a
     * Config Set persisted before this feature existed simply has no {@code <deletion>} element, so
     * reflection-based deserialization leaves the field {@code null}, which is already the correct
     * and safe "live" state. That is the reason this is a nullable reference rather than a
     * {@code boolean} with a sentinel.</p>
     */
    private ConfigSetDeletion deletion;

    public ConfigSet(String projectKey, ConfigSetRole role, String environment, String displayName,
                      ContentType contentType) {
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        if (projectKey.trim().isEmpty()) {
            throw new IllegalArgumentException("projectKey must not be empty");
        }
        this.role = Objects.requireNonNull(role, "role");
        if (environment != null) {
            throw new IllegalArgumentException("environment must be null for a COMMON-role ConfigSet");
        }
        // Assign the literal, not the now-provably-always-null `environment` parameter: SpotBugs'
        // NP_LOAD_OF_KNOWN_NULL_VALUE check (introduced by the 2026-09-14 ConfigSetRole.ENV removal
        // — see this field's own javadoc, "Always null now that ConfigSetRole#ENV was removed in
        // full") flags loading a local already proven null by the check above. Semantically
        // identical; this form just doesn't trip the analyzer.
        this.environment = null;
        this.displayName = displayName;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
    }

    public ContentType getContentType() {
        return contentType;
    }

    /**
     * XStream2/Jenkins persistence idiom (see multi-format-content.md's "every Config Set persisted
     * before this feature existed is treated, on load, as JSON" rule): a Config Set persisted before
     * this feature existed has no {@code <contentType>} XML element, so this field lands {@code null} after
     * reflection-based deserialization (XStream bypasses the constructor entirely). Plain
     * field-default assignment, not object reconstruction — reconstructing via the constructor would
     * lose {@code displayName}/{@code versions}/{@code secretsManifest}/{@code activeVersionNumber},
     * all populated post-construction via mutators.
     */
    private Object readResolve() {
        if (contentType == null) {
            contentType = ContentType.JSON;
        }
        return this;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public ConfigSetRole getRole() {
        return role;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The deterministic storage key for this Config Set, used as the persistence filename (see
     * {@code ConfigSetRepository}). Derived structurally from projectKey/role/environment so
     * lookup never depends on free-text matching.
     */
    public String getStorageKey() {
        return role == ConfigSetRole.COMMON
                ? projectKey + "--common"
                : projectKey + "--env--" + environment;
    }

    /** Whether this Config Set has been withdrawn from service but not yet purged. */
    public boolean isDeleted() {
        return deletion != null;
    }

    /** The audit trail of the withdrawal, or {@code null} while this Config Set is live. */
    public ConfigSetDeletion getDeletion() {
        return deletion;
    }

    /**
     * Withdraws this Config Set from service, keeping every version, the secrets manifest, the
     * active-version pointer and the content type exactly as they are. Nothing here is destructive;
     * {@link #restore()} is its exact inverse.
     *
     * @throws IllegalStateException if it is already deleted — a second deletion would overwrite
     *         the original actor and timestamp, losing the audit trail this exists to keep.
     */
    public void markDeleted(String actor, long epochMillis) {
        if (deletion != null) {
            throw new IllegalStateException(
                    "Config Set '" + getStorageKey() + "' is already deleted (by "
                            + deletion.getDeletedBy() + ")");
        }
        deletion = new ConfigSetDeletion(actor, epochMillis);
    }

    /** Returns this Config Set to service. @throws IllegalStateException if it is not deleted. */
    public void restore() {
        if (deletion == null) {
            throw new IllegalStateException(
                    "Config Set '" + getStorageKey() + "' is not deleted, so it cannot be restored");
        }
        deletion = null;
    }

    public Map<String, String> getSecretsManifest() {
        return Collections.unmodifiableMap(secretsManifest);
    }

    public void putSecretManifestEntry(String dottedPath, String credentialId) {
        secretsManifest.put(
                Objects.requireNonNull(dottedPath, "dottedPath"),
                Objects.requireNonNull(credentialId, "credentialId"));
    }

    /**
     * Removes a previously-bound secrets-manifest entry. A pure in-place
     * mutation of this Config Set's manifest map — mirrors {@link #putSecretManifestEntry} exactly
     * (no new {@link ConfigSetVersion} is created; the manifest is metadata alongside the version
     * history, not itself versioned), so the caller persists via the same
     * {@code ConfigSetRepository#save(ConfigSet)} call already used after a put.
     *
     * @return {@code true} if an entry for {@code dottedPath} existed and was removed, {@code false}
     *         if there was nothing bound at that path.
     */
    public boolean removeSecretManifestEntry(String dottedPath) {
        return secretsManifest.remove(Objects.requireNonNull(dottedPath, "dottedPath")) != null;
    }

    public List<ConfigSetVersion> getVersions() {
        return Collections.unmodifiableList(versions);
    }

    public int getActiveVersionNumber() {
        return activeVersionNumber;
    }

    public ConfigSetVersion getActiveVersion() {
        if (activeVersionNumber == 0) {
            return null;
        }
        return getVersion(activeVersionNumber);
    }

    public ConfigSetVersion getVersion(int versionNumber) {
        for (ConfigSetVersion v : versions) {
            if (v.getVersionNumber() == versionNumber) {
                return v;
            }
        }
        return null;
    }

    /**
     * Appends a new version after enforcing the secret-placeholder rule (see
     * config-sets-and-versioning.md's "Secrets" section — structural prevention, not heuristic
     * detection): every leaf declared secret in {@link #secretsManifest} MUST hold the literal
     * {@link SecretPlaceholder#VALUE} marker, never a real value. Never activates the new version by
     * itself — call {@link #activate(int)} explicitly if desired (activation stays a distinct action).
     *
     * @return the newly assigned, monotonically increasing version number.
     */
    public int addVersion(String contentJson, String note, String author, long timestampEpochMillis) {
        return addVersion(contentJson, note, author, timestampEpochMillis, Collections.emptyList());
    }

    /**
     * Appends a new version carrying an explicit base chain (see base-chains.md's "Multi-base config
     * chains" section). A COMMON-role Config Set can
     * never declare a base chain of its own — common Config Sets are always the roots of a
     * chain, never chain members themselves — so a non-empty {@code baseChain} on a COMMON-role
     * Config Set is rejected before the existing secret-placeholder enforcement even runs.
     *
     * @return the newly assigned, monotonically increasing version number.
     */
    public int addVersion(String contentJson, String note, String author, long timestampEpochMillis,
                           List<BaseConfigReference> baseChain) {
        return addVersion(contentJson, note, author, timestampEpochMillis, baseChain, false);
    }

    /**
     * Appends a new version carrying an explicit base chain and standalone flag (see base-chains.md's
     * "Standalone env Config Sets" section — requirement history, kept live here only for the
     * COMMON-role rejection check below). Both the non-empty-{@code baseChain}-on-COMMON check and the
     * {@code explicitlyStandalone}-on-COMMON check, plus the contradiction check
     * ({@code explicitlyStandalone=true} with a non-empty {@code baseChain}), are validated together
     * here as hard {@link IllegalArgumentException}s — never silent normalization, matching the
     * existing "fail the save, not silently truncated to empty" convention.
     *
     * @return the newly assigned, monotonically increasing version number.
     */
    public int addVersion(String contentJson, String note, String author, long timestampEpochMillis,
                           List<BaseConfigReference> baseChain, boolean explicitlyStandalone) {
        if (role == ConfigSetRole.COMMON) {
            if (baseChain != null && !baseChain.isEmpty()) {
                throw new IllegalArgumentException(
                        "A COMMON-role Config Set must not declare a base chain of its own");
            }
            if (explicitlyStandalone) {
                // Mirrors the base-chain rejection above, for the standalone flag.
                throw new IllegalArgumentException(
                        "A COMMON-role Config Set must not be marked explicitlyStandalone");
            }
        }
        if (explicitlyStandalone && baseChain != null && !baseChain.isEmpty()) {
            throw new IllegalArgumentException(
                    "explicitlyStandalone=true is contradictory with a non-empty baseChain — an "
                            + "explicitly-standalone env Config Set Version must declare zero base configs");
        }
        enforceSecretPlaceholders(contentJson);
        int nextVersionNumber = nextVersionNumber();
        versions.add(new ConfigSetVersion(nextVersionNumber, contentJson, note, author, timestampEpochMillis,
                baseChain, explicitlyStandalone));
        return nextVersionNumber;
    }

    private int nextVersionNumber() {
        int max = 0;
        for (ConfigSetVersion v : versions) {
            max = Math.max(max, v.getVersionNumber());
        }
        return max + 1;
    }

    /**
     * Structurally rejects a save where a manifest-declared path's leaf value in {@code contentJson}
     * is not the literal placeholder marker (see config-sets-and-versioning.md's "Secrets" section:
     * structural prevention, not heuristic detection).
     */
    private void enforceSecretPlaceholders(String content) {
        if (secretsManifest.isEmpty()) {
            return;
        }
        TreeNode root = TreeFormats.forType(this.contentType).parse(content);
        if (!root.isObject()) {
            throw new IllegalArgumentException(
                    "Config Set content must be a " + contentType + " object/root element");
        }
        for (String dottedPath : secretsManifest.keySet()) {
            TreeNode leaf = TreePaths.get(root, dottedPath);
            if (leaf == null) {
                // absent leaf for a manifest path is allowed on an env overlay (sparse content) —
                // there is simply nothing to enforce at this version for that path.
                continue;
            }
            String asString = !leaf.isObject() && !leaf.isNull() ? leaf.leafAsString() : null;
            if (asString == null || !asString.equals(SecretPlaceholder.VALUE)) {
                throw new IllegalArgumentException(
                        "Manifest-declared secret path '" + dottedPath
                                + "' must hold the placeholder '" + SecretPlaceholder.VALUE
                                + "', not a real value");
            }
        }
    }

    /**
     * Activates (or rolls back to) a previously-saved version. This is a pure metadata flip — it
     * flips which version number is "active," nothing else — and never itself triggers a live
     * deploy of any kind (a deliberate product decision, not an oversight; a real deploy always
     * requires a separate, explicit pipeline run — see user-flows.md's operator rollback flows).
     */
    public void activate(int versionNumber) {
        if (getVersion(versionNumber) == null) {
            throw new IllegalArgumentException("No such version: " + versionNumber);
        }
        this.activeVersionNumber = versionNumber;
    }
}
