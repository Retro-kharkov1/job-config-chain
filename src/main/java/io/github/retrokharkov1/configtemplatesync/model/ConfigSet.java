package io.github.retrokharkov1.configtemplatesync.model;

import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormats;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreePaths;

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
 * {@link #projectKey} (OQ-2, resolved 2026-08-27) — never by free-text name matching.</p>
 */
public class ConfigSet implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Structural identifier tying a common Config Set to its env Config Sets (OQ-2). */
    private final String projectKey;

    private final ConfigSetRole role;

    /** Required for {@link ConfigSetRole#ENV}, must be {@code null} for {@link ConfigSetRole#COMMON}. */
    private final String environment;

    private String displayName;

    /** Dotted path within this Config Set's content -> Jenkins credential ID supplying the real value. */
    private final Map<String, String> secretsManifest = new LinkedHashMap<>();

    /** Append-only version history (FR-2). Never remove or mutate an entry once added. */
    private final List<ConfigSetVersion> versions = new ArrayList<>();

    /** 0 means "no active version yet"; otherwise exactly one version has this number (FR-3). */
    private int activeVersionNumber;

    /**
     * This Config Set's structured content format (FR-59), chosen once at first Save and immutable
     * thereafter. NOT {@code final} — {@link #readResolve()} patches in the FR-69 backward-compat
     * default for XML persisted before this feature existed.
     */
    private ContentType contentType;

    public ConfigSet(String projectKey, ConfigSetRole role, String environment, String displayName,
                      ContentType contentType) {
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        if (projectKey.trim().isEmpty()) {
            throw new IllegalArgumentException("projectKey must not be empty");
        }
        this.role = Objects.requireNonNull(role, "role");
        if (role == ConfigSetRole.ENV) {
            if (environment == null || environment.trim().isEmpty()) {
                throw new IllegalArgumentException("environment is required for an ENV-role ConfigSet");
            }
        } else if (environment != null) {
            throw new IllegalArgumentException("environment must be null for a COMMON-role ConfigSet");
        }
        this.environment = environment;
        this.displayName = displayName;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
    }

    public ContentType getContentType() {
        return contentType;
    }

    /**
     * XStream2/Jenkins persistence idiom (FR-69): a Config Set persisted before this feature existed
     * has no {@code <contentType>} XML element, so this field lands {@code null} after
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
     * lookup never depends on free-text matching (OQ-2).
     */
    public String getStorageKey() {
        return role == ConfigSetRole.COMMON
                ? projectKey + "--common"
                : projectKey + "--env--" + environment;
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
     * Removes a previously-bound secrets-manifest entry (OQ-1 CRUD completion). A pure in-place
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
     * Appends a new version (FR-2, FR-4) after enforcing the secret-placeholder rule (OQ-1/FR-14):
     * every leaf declared secret in {@link #secretsManifest} MUST hold the literal
     * {@link SecretPlaceholder#VALUE} marker, never a real value. Never activates the new version by
     * itself — call {@link #activate(int)} explicitly if desired (FR-3 stays a distinct action).
     *
     * @return the newly assigned, monotonically increasing version number (FR-4).
     */
    public int addVersion(String contentJson, String note, String author, long timestampEpochMillis) {
        return addVersion(contentJson, note, author, timestampEpochMillis, Collections.emptyList());
    }

    /**
     * Appends a new version carrying an explicit base chain (FR-51). A COMMON-role Config Set can
     * never declare a base chain of its own (FR-53) — common Config Sets are always the roots of a
     * chain, never chain members themselves — so a non-empty {@code baseChain} on a COMMON-role
     * Config Set is rejected before the existing secret-placeholder enforcement even runs.
     *
     * @return the newly assigned, monotonically increasing version number (FR-4).
     */
    public int addVersion(String contentJson, String note, String author, long timestampEpochMillis,
                           List<BaseConfigReference> baseChain) {
        return addVersion(contentJson, note, author, timestampEpochMillis, baseChain, false);
    }

    /**
     * Appends a new version carrying an explicit base chain and standalone flag (FR-51, FR-86). Both
     * the FR-53 (non-empty {@code baseChain} on COMMON) and the FR-86 ({@code explicitlyStandalone} on
     * COMMON) checks, plus the FR-86/87 contradiction check ({@code explicitlyStandalone=true} with a
     * non-empty {@code baseChain}), are validated together here as hard {@link IllegalArgumentException}s
     * — never silent normalization, matching FR-53's own existing "fail the save, not silently
     * truncated to empty" convention.
     *
     * @return the newly assigned, monotonically increasing version number (FR-4).
     */
    public int addVersion(String contentJson, String note, String author, long timestampEpochMillis,
                           List<BaseConfigReference> baseChain, boolean explicitlyStandalone) {
        if (role == ConfigSetRole.COMMON) {
            if (baseChain != null && !baseChain.isEmpty()) {
                // FR-53, unchanged.
                throw new IllegalArgumentException(
                        "A COMMON-role Config Set must not declare a base chain of its own");
            }
            if (explicitlyStandalone) {
                // FR-86: mirrors FR-53's own restriction for the new flag.
                throw new IllegalArgumentException(
                        "A COMMON-role Config Set must not be marked explicitlyStandalone");
            }
        }
        if (explicitlyStandalone && baseChain != null && !baseChain.isEmpty()) {
            // New contradiction check (FR-86/87 area).
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
     * is not the literal placeholder marker (OQ-1, resolved 2026-08-27: structural prevention, not
     * heuristic detection).
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
     * deploy of any kind (OQ-6, resolved 2026-08-27: this is a deliberate product decision, not an
     * oversight; a real deploy always requires a separate, explicit pipeline run per UF-5/UF-6).
     */
    public void activate(int versionNumber) {
        if (getVersion(versionNumber) == null) {
            throw new IllegalArgumentException("No such version: " + versionNumber);
        }
        this.activeVersionNumber = versionNumber;
    }
}
