package io.github.retrokharkov1.configtemplatesync;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named, versioned configuration bucket — one per project+environment (or one per
 * "common"/cross-env layer), e.g. key {@code "myapp-dev"} or {@code "myapp-common"}.
 *
 * <p>Deliberately generic: nothing here references any specific project. A caller composes two
 * ConfigSets (a common one and an env one) by naming convention in the calling Jenkinsfile /
 * Script Console session — this class does not know about that convention.
 *
 * <p>Persisted as a whole (key + display name + secrets manifest + full version history) to a
 * single XML file per ConfigSet via {@link ConfigSetRepository}.
 */
public class ConfigSet implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String key;
    private String displayName;

    /** Dotted JSON path -> Jenkins credential ID supplying the real secret value at substitution time. */
    private final Map<String, String> secretsManifest = new LinkedHashMap<>();

    /** Append-only, newest appended last. Never remove or reorder. */
    private final List<ConfigSetVersion> versions = new ArrayList<>();

    public ConfigSet(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Map<String, String> getSecretsManifest() {
        return secretsManifest;
    }

    public void setSecretsManifest(Map<String, String> manifest) {
        secretsManifest.clear();
        if (manifest != null) {
            secretsManifest.putAll(manifest);
        }
    }

    /** Newest-first view of the version history. Never mutate the returned list directly. */
    public List<ConfigSetVersion> listVersionsNewestFirst() {
        List<ConfigSetVersion> copy = new ArrayList<>(versions);
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    public ConfigSetVersion getActiveVersion() {
        for (ConfigSetVersion v : versions) {
            if (v.isActive()) {
                return v;
            }
        }
        return null;
    }

    public ConfigSetVersion getVersion(int versionNumber) {
        for (ConfigSetVersion v : versions) {
            if (v.getVersionNumber() == versionNumber) {
                return v;
            }
        }
        return null;
    }

    private int nextVersionNumber() {
        int max = 0;
        for (ConfigSetVersion v : versions) {
            max = Math.max(max, v.getVersionNumber());
        }
        return max + 1;
    }

    /**
     * Appends a new version. If {@code activateOnSave} is true, deactivates the previously
     * active version (if any) and activates the new one in the same call — otherwise the
     * previously active version (if any) is left untouched and the new version starts inactive.
     *
     * @return the newly assigned version number
     */
    public int save(String contentJson, String note, String author, boolean activateOnSave) {
        int versionNumber = nextVersionNumber();
        ConfigSetVersion newVersion = new ConfigSetVersion(
                versionNumber, contentJson, note, author, System.currentTimeMillis(), false);
        if (activateOnSave) {
            for (ConfigSetVersion v : versions) {
                v.setActive(false);
            }
            newVersion.setActive(true);
        }
        versions.add(newVersion);
        enforceExactlyOneActiveInvariant();
        return versionNumber;
    }

    /**
     * Flips the {@code active} flag onto an existing historical version and off the currently
     * active one — no content is cloned, no new version is minted. This is the literal
     * "rollback" operation.
     *
     * @throws IllegalArgumentException if no version with that number exists
     */
    public void activate(int versionNumber) {
        ConfigSetVersion target = getVersion(versionNumber);
        if (target == null) {
            throw new IllegalArgumentException(
                    "No version " + versionNumber + " in ConfigSet '" + key + "'");
        }
        for (ConfigSetVersion v : versions) {
            v.setActive(v.getVersionNumber() == versionNumber);
        }
        enforceExactlyOneActiveInvariant();
    }

    /**
     * Defensive check run after every save/activate: at most one version may be active. (Zero
     * active is allowed only transiently for a brand-new ConfigSet with no versions yet, or right
     * after a save-without-activate on an otherwise-empty ConfigSet — callers should activate the
     * first version explicitly in that case.)
     */
    private void enforceExactlyOneActiveInvariant() {
        long activeCount = versions.stream().filter(ConfigSetVersion::isActive).count();
        if (activeCount > 1) {
            throw new IllegalStateException(
                    "Invariant violated: ConfigSet '" + key + "' has " + activeCount
                            + " active versions, expected at most 1");
        }
    }
}
