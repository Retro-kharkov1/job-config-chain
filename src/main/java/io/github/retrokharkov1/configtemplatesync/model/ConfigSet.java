package io.github.retrokharkov1.configtemplatesync.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.retrokharkov1.configtemplatesync.merge.JsonPaths;

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

    public ConfigSet(String projectKey, ConfigSetRole role, String environment, String displayName) {
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
        enforceSecretPlaceholders(contentJson);
        int nextVersionNumber = nextVersionNumber();
        versions.add(new ConfigSetVersion(nextVersionNumber, contentJson, note, author, timestampEpochMillis));
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
    private void enforceSecretPlaceholders(String contentJson) {
        if (secretsManifest.isEmpty()) {
            return;
        }
        JsonElement parsed = JsonParser.parseString(contentJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Config Set content must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        for (String dottedPath : secretsManifest.keySet()) {
            JsonElement leaf = JsonPaths.get(root, dottedPath);
            if (leaf == null) {
                // absent leaf for a manifest path is allowed on an env overlay (sparse content) —
                // there is simply nothing to enforce at this version for that path.
                continue;
            }
            String asString = leaf.isJsonPrimitive() && leaf.getAsJsonPrimitive().isString()
                    ? leaf.getAsString()
                    : null;
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
