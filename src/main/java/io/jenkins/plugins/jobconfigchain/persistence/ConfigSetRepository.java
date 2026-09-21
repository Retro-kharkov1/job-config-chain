package io.jenkins.plugins.jobconfigchain.persistence;

import hudson.XmlFile;
import hudson.util.XStream2;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists {@link ConfigSet}s as one {@code hudson.XmlFile}/{@code XStream2} document per Config
 * Set under {@code $JENKINS_HOME/config-template-sync/} (see nfr.md's "No external database of any
 * kind" and "Not git-backed" rules). See https://javadoc.jenkins.io/hudson/XmlFile.html.
 *
 * <p>Lookup by (projectKey, role, environment) is O(1) via {@link ConfigSet#getStorageKey()}'s
 * deterministic filename convention — never a scan-and-match by free-text name.</p>
 */
public class ConfigSetRepository {

    private static final String SUBDIRECTORY = "config-template-sync";

    private final File baseDir;

    /** For production use: persists under the running Jenkins controller's {@code $JENKINS_HOME}. */
    public ConfigSetRepository() {
        this(new File(Jenkins.get().getRootDir(), SUBDIRECTORY));
    }

    /** For unit tests (and any embedding that wants an explicit root directory) without a running Jenkins. */
    public ConfigSetRepository(File baseDir) {
        this.baseDir = baseDir;
        if (!baseDir.exists() && !baseDir.mkdirs() && !baseDir.exists()) {
            throw new UncheckedIOException(new IOException("Could not create " + baseDir));
        }
    }

    /**
     * The live Config Set for this project key, or {@code null} if there is none <em>or</em> it has
     * been soft-deleted.
     *
     * <p>Treating a deleted Config Set as missing is deliberate, and it is the single decision that
     * makes withdrawal safe. Every resolver in the plugin - base-chain resolution, the Pipeline
     * steps, the frozen-binding redeploy path, the chain type check - already handles a
     * {@code null} here, and every one of them fails loud. Putting the check in this one lowest
     * lookup therefore makes all of them correct with no change, and makes a future call site
     * correct by default rather than by remembering. The alternative, an {@code isDeleted()} test
     * at each resolver, is a list that eventually misses one, and a missed one means builds quietly
     * consuming configuration an administrator believes they withdrew.</p>
     *
     * <p>Use {@link #findCommonIncludingDeleted(String)} where the deleted record itself is the
     * subject: the deleted-state page, the lifecycle operations, and the "was deleted rather than
     * never existed" wording on the already-failing paths.</p>
     */
    public ConfigSet findCommon(String projectKey) {
        ConfigSet loaded = load(projectKey + "--common");
        return loaded == null || loaded.isDeleted() ? null : loaded;
    }

    /** The record for this project key whether live or soft-deleted; {@code null} if absent. */
    public ConfigSet findCommonIncludingDeleted(String projectKey) {
        return load(projectKey + "--common");
    }

    public ConfigSet find(String projectKey, ConfigSetRole role, String environment) {
        return findCommon(projectKey);
    }

    /**
     * Persists a Config Set, refusing to write over a soft-deleted record at the same storage key.
     *
     * <p>That refusal is what reserves a deleted Config Set name. It lives here rather than in the
     * UI because every creation and every version append funnels through this method, so the
     * guarantee holds for callers that do not exist yet. The extra read costs one deserialization
     * of an already-existing file, negligible beside the write it guards.</p>
     *
     * @throws ConfigSetDeletedException if the on-disk record at this key is deleted while the
     *         incoming one is not - i.e. an ordinary save, not a lifecycle operation.
     */
    public void save(ConfigSet configSet) {
        if (!configSet.isDeleted()) {
            ConfigSet existing = load(configSet.getStorageKey());
            if (existing != null && existing.isDeleted()) {
                throw new ConfigSetDeletedException(configSet.getStorageKey());
            }
        }
        write(configSet);
    }

    /**
     * Withdraws a Config Set from service, keeping its entire history. Reversible via
     * {@link #restoreCommon(String)}.
     *
     * @throws IllegalStateException if there is no such Config Set, or it is already deleted.
     */
    public synchronized void softDeleteCommon(String projectKey, String actor, long epochMillis) {
        ConfigSet configSet = requireForLifecycle(projectKey);
        configSet.markDeleted(actor, epochMillis);
        write(configSet);
    }

    /** Returns a withdrawn Config Set to service. @throws IllegalStateException if it is not deleted. */
    public synchronized void restoreCommon(String projectKey) {
        ConfigSet configSet = requireForLifecycle(projectKey);
        configSet.restore();
        write(configSet);
    }

    /**
     * Permanently removes a soft-deleted Config Set and its whole version history from disk.
     *
     * <p>This is the only code in the plugin that deletes a file. It removes exactly one path,
     * computed from the same storage-key convention every other method here uses - never a glob,
     * never a directory - so sibling Config Sets and the {@code deployment-bindings.xml} that
     * shares this directory cannot be caught by it.</p>
     *
     * <p>It refuses a live record outright, so that purging is always a second deliberate step and
     * a bug upstream cannot turn it into a one-click destruction of a Config Set in service.</p>
     *
     * @return {@code false} if the file had already gone - a missing file is the desired end state,
     *         not a failure, and a concurrent purge by another administrator is not an error.
     * @throws IllegalStateException if there is no such Config Set, or it is still live.
     */
    public synchronized boolean purgeCommon(String projectKey) {
        ConfigSet configSet = load(projectKey + "--common");
        if (configSet == null) {
            throw new IllegalStateException("No Config Set " + projectKey + " to purge");
        }
        if (!configSet.isDeleted()) {
            throw new IllegalStateException("Config Set " + projectKey + " is still live - delete it "
                    + "before purging, so that purging is always a second, deliberate step");
        }
        File file = new File(baseDir, configSet.getStorageKey() + ".xml");
        try {
            return Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to purge ConfigSet " + configSet.getStorageKey(), e);
        }
    }

    private ConfigSet requireForLifecycle(String projectKey) {
        ConfigSet configSet = load(projectKey + "--common");
        if (configSet == null) {
            throw new IllegalStateException("No Config Set " + projectKey);
        }
        return configSet;
    }

    /** The unguarded write the lifecycle operations use; {@link #save} adds the deleted-record guard. */
    private void write(ConfigSet configSet) {
        try {
            xmlFile(configSet.getStorageKey()).write(configSet);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save ConfigSet " + configSet.getStorageKey(), e);
        }
    }

    /**
     * Lists every persisted common Config Set (used by the global "Config Templates" admin list
     * view — see admin-ui.md's "Global level" section). Storage-key-driven, not a free-text scan — mirrors {@link ConfigSet#getStorageKey()}'s
     * {@code <projectKey>--common} convention.
     */
    public List<ConfigSet> listAllCommon() {
        return listCommon(false);
    }

    /**
     * Only the soft-deleted Config Sets, for the administrator view of what can be restored or
     * purged. Deliberately a separate method rather than a flag on {@link #listAllCommon()}: every
     * existing caller of that method wants the live inventory, so making live-only its meaning
     * keeps all of them correct without being touched.
     */
    public List<ConfigSet> listDeletedCommon() {
        return listCommon(true);
    }

    private List<ConfigSet> listCommon(boolean deleted) {
        List<ConfigSet> result = new ArrayList<>();
        for (File f : listXmlFiles()) {
            String name = baseName(f);
            if (name.endsWith("--common")) {
                ConfigSet loaded = load(name);
                if (loaded != null && loaded.isDeleted() == deleted) {
                    result.add(loaded);
                }
            }
        }
        result.sort(Comparator.comparing(ConfigSet::getProjectKey));
        return result;
    }

    /**
     * Distinct project keys across every persisted Config Set of either role (see nfr.md
     * "Portability"). Derived from filenames, so it spans soft-deleted records too - which is the
     * right answer for its stated purpose of which keys are taken, since a deleted key stays
     * reserved until it is restored or purged.
     */
    public Set<String> listProjectKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (File f : listXmlFiles()) {
            String name = baseName(f);
            if (name.endsWith("--common")) {
                keys.add(name.substring(0, name.length() - "--common".length()));
            } else if (name.contains("--env--")) {
                keys.add(name.substring(0, name.indexOf("--env--")));
            }
        }
        return keys;
    }

    private File[] listXmlFiles() {
        File[] files = baseDir.listFiles((dir, name) -> name.endsWith(".xml"));
        return files == null ? new File[0] : files;
    }

    private String baseName(File f) {
        String name = f.getName();
        return name.substring(0, name.length() - ".xml".length());
    }

    private ConfigSet load(String storageKey) {
        XmlFile file = xmlFile(storageKey);
        if (!file.exists()) {
            return null;
        }
        try {
            return (ConfigSet) file.read();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load ConfigSet " + storageKey, e);
        }
    }

    private XmlFile xmlFile(String storageKey) {
        return new XmlFile(new XStream2(), new File(baseDir, storageKey + ".xml"));
    }
}
