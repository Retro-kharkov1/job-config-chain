package io.github.retrokharkov1.configtemplatesync;

import hudson.XmlFile;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists {@link ConfigSet}s to {@code <root>/config-template-sync/&lt;configSetKey&gt;.xml},
 * one XML file per ConfigSet, using Jenkins' own {@link XmlFile}/{@code XStream2} persistence
 * convention — the same mechanism Jenkins uses for job configs and global configuration. No git,
 * no external database.
 *
 * <p>The root directory is injected rather than hardcoded to {@code $JENKINS_HOME} so this class
 * is unit-testable with plain JUnit against a temp directory, without requiring a running Jenkins
 * instance. {@link #onMaster()} returns the singleton bound to the real {@code $JENKINS_HOME}
 * for use inside a running controller (pipeline steps, future UI, Script Console).
 */
public class ConfigSetRepository {

    private static volatile ConfigSetRepository MASTER_INSTANCE;

    private final File rootDir;
    private final Map<String, ConfigSet> cache = new ConcurrentHashMap<>();

    public ConfigSetRepository(File rootDir) {
        this.rootDir = rootDir;
        if (!rootDir.exists() && !rootDir.mkdirs() && !rootDir.exists()) {
            throw new IllegalStateException("Could not create config-template-sync root dir: " + rootDir);
        }
    }

    /** Repository bound to the real {@code $JENKINS_HOME/config-template-sync} directory. */
    public static synchronized ConfigSetRepository onMaster() {
        if (MASTER_INSTANCE == null) {
            File jenkinsHome = Jenkins.get().getRootDir();
            MASTER_INSTANCE = new ConfigSetRepository(new File(jenkinsHome, "config-template-sync"));
        }
        return MASTER_INSTANCE;
    }

    /** Test/tooling hook: reset the master singleton (e.g. between JenkinsRule restarts). */
    static synchronized void resetMasterInstanceForTests() {
        MASTER_INSTANCE = null;
    }

    private XmlFile xmlFileFor(String configSetKey) {
        return new XmlFile(Jenkins.XSTREAM2, new File(rootDir, configSetKey + ".xml"));
    }

    /**
     * Loads a ConfigSet from disk (cached after first load) or creates a brand-new, empty,
     * in-memory one if no XML file exists yet for this key.
     */
    public synchronized ConfigSet getOrCreate(String configSetKey, String displayNameIfNew) {
        ConfigSet cached = cache.get(configSetKey);
        if (cached != null) {
            return cached;
        }
        XmlFile xml = xmlFileFor(configSetKey);
        ConfigSet loaded;
        if (xml.exists()) {
            try {
                loaded = (ConfigSet) xml.read();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read ConfigSet '" + configSetKey + "' from "
                        + xml.getFile(), e);
            }
        } else {
            loaded = new ConfigSet(configSetKey, displayNameIfNew != null ? displayNameIfNew : configSetKey);
        }
        cache.put(configSetKey, loaded);
        return loaded;
    }

    /** Like {@link #getOrCreate(String, String)} but throws if the ConfigSet does not exist yet. */
    public synchronized ConfigSet get(String configSetKey) {
        ConfigSet cached = cache.get(configSetKey);
        if (cached != null) {
            return cached;
        }
        XmlFile xml = xmlFileFor(configSetKey);
        if (!xml.exists()) {
            throw new IllegalArgumentException("No ConfigSet found for key '" + configSetKey + "'");
        }
        return getOrCreate(configSetKey, null);
    }

    public synchronized void persist(ConfigSet configSet) {
        cache.put(configSet.getKey(), configSet);
        try {
            xmlFileFor(configSet.getKey()).write(configSet);
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist ConfigSet '" + configSet.getKey() + "'", e);
        }
    }

    // ---- Facade API matching the design spec's save/activate/listVersions/getActive contract ----

    /**
     * @return the newly assigned version number
     */
    public synchronized int save(String configSetKey, String contentJson,
                                  Map<String, String> secretsManifest, String note, String author,
                                  boolean activateOnSave) {
        ConfigSet configSet = getOrCreate(configSetKey, null);
        if (secretsManifest != null) {
            configSet.setSecretsManifest(secretsManifest);
        }
        int versionNumber = configSet.save(contentJson, note, author, activateOnSave);
        persist(configSet);
        return versionNumber;
    }

    public synchronized void activate(String configSetKey, int versionNumber, String activatedByUser) {
        ConfigSet configSet = get(configSetKey);
        configSet.activate(versionNumber);
        persist(configSet);
    }

    public List<ConfigSetVersion> listVersions(String configSetKey) {
        return get(configSetKey).listVersionsNewestFirst();
    }

    /** @return the active version's JSON content, or {@code null} if no version is active. */
    public String getActive(String configSetKey) {
        ConfigSetVersion active = get(configSetKey).getActiveVersion();
        return active == null ? null : active.getContentJson();
    }

    public ConfigSet getConfigSet(String configSetKey) {
        return get(configSetKey);
    }
}
