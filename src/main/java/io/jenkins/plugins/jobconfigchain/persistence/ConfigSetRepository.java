package io.jenkins.plugins.jobconfigchain.persistence;

import hudson.XmlFile;
import hudson.util.XStream2;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
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

    public ConfigSet findCommon(String projectKey) {
        return load(projectKey + "--common");
    }

    public ConfigSet find(String projectKey, ConfigSetRole role, String environment) {
        return findCommon(projectKey);
    }

    public void save(ConfigSet configSet) {
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
        List<ConfigSet> result = new ArrayList<>();
        for (File f : listXmlFiles()) {
            String name = baseName(f);
            if (name.endsWith("--common")) {
                ConfigSet loaded = load(name);
                if (loaded != null) {
                    result.add(loaded);
                }
            }
        }
        result.sort(Comparator.comparing(ConfigSet::getProjectKey));
        return result;
    }

    /** Distinct project keys across every persisted Config Set of either role (see nfr.md's "Portability" section). */
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
