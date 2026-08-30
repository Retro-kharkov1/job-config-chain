package io.github.retrokharkov1.configtemplatesync.persistence;

import hudson.XmlFile;
import hudson.util.XStream2;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Persists {@link ConfigSet}s as one {@code hudson.XmlFile}/{@code XStream2} document per Config
 * Set under {@code $JENKINS_HOME/config-template-sync/} (NFR-1: no external database, NFR-2: not
 * git-backed). See https://javadoc.jenkins.io/hudson/XmlFile.html.
 *
 * <p>Lookup by (projectKey, role, environment) is O(1) via {@link ConfigSet#getStorageKey()}'s
 * deterministic filename convention — never a scan-and-match by free-text name (OQ-2).</p>
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

    public ConfigSet findEnv(String projectKey, String environment) {
        return load(projectKey + "--env--" + environment);
    }

    public ConfigSet find(String projectKey, ConfigSetRole role, String environment) {
        return role == ConfigSetRole.COMMON ? findCommon(projectKey) : findEnv(projectKey, environment);
    }

    public void save(ConfigSet configSet) {
        try {
            xmlFile(configSet.getStorageKey()).write(configSet);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save ConfigSet " + configSet.getStorageKey(), e);
        }
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
