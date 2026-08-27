package io.github.retrokharkov1.configtemplatesync;

import hudson.XmlFile;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists {@link ConfigDeploymentBinding}s to
 * {@code <root>/config-template-sync/bindings/&lt;projectEnvKey&gt;.xml}, one XML file per
 * project+env key, holding every {@code buildVersion} ever recorded for that key — same
 * Jenkins-native {@link XmlFile}/{@code XStream2} persistence convention as
 * {@link ConfigSetRepository}, following the same pattern deliberately (root dir injected for
 * plain-JUnit testability, {@link #onMaster()} binds to the real {@code $JENKINS_HOME}).
 */
public class ConfigDeploymentBindingRepository {

    private static volatile ConfigDeploymentBindingRepository MASTER_INSTANCE;

    private final File rootDir;
    private final Map<String, BindingsForKey> cache = new ConcurrentHashMap<>();

    public ConfigDeploymentBindingRepository(File rootDir) {
        this.rootDir = rootDir;
        if (!rootDir.exists() && !rootDir.mkdirs() && !rootDir.exists()) {
            throw new IllegalStateException(
                    "Could not create config-template-sync bindings root dir: " + rootDir);
        }
    }

    /** Repository bound to the real {@code $JENKINS_HOME/config-template-sync/bindings} directory. */
    public static synchronized ConfigDeploymentBindingRepository onMaster() {
        if (MASTER_INSTANCE == null) {
            File jenkinsHome = Jenkins.get().getRootDir();
            MASTER_INSTANCE = new ConfigDeploymentBindingRepository(
                    new File(new File(jenkinsHome, "config-template-sync"), "bindings"));
        }
        return MASTER_INSTANCE;
    }

    /** Test/tooling hook: reset the master singleton (e.g. between JenkinsRule restarts). */
    static synchronized void resetMasterInstanceForTests() {
        MASTER_INSTANCE = null;
    }

    private XmlFile xmlFileFor(String projectEnvKey) {
        return new XmlFile(Jenkins.XSTREAM2, new File(rootDir, projectEnvKey + ".xml"));
    }

    private synchronized BindingsForKey load(String projectEnvKey) {
        BindingsForKey cached = cache.get(projectEnvKey);
        if (cached != null) {
            return cached;
        }
        XmlFile xml = xmlFileFor(projectEnvKey);
        BindingsForKey loaded;
        if (xml.exists()) {
            try {
                loaded = (BindingsForKey) xml.read();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read deployment bindings for '" + projectEnvKey
                        + "' from " + xml.getFile(), e);
            }
        } else {
            loaded = new BindingsForKey();
        }
        cache.put(projectEnvKey, loaded);
        return loaded;
    }

    private synchronized void persist(String projectEnvKey, BindingsForKey bindings) {
        cache.put(projectEnvKey, bindings);
        try {
            xmlFileFor(projectEnvKey).write(bindings);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to persist deployment bindings for '" + projectEnvKey + "'", e);
        }
    }

    /**
     * @return the existing binding for this exact {@code (projectEnvKey, buildVersion)} pair, or
     *         {@code null} if none has been recorded yet.
     */
    public synchronized ConfigDeploymentBinding find(String projectEnvKey, String buildVersion) {
        if (buildVersion == null || buildVersion.trim().isEmpty()) {
            return null;
        }
        return load(projectEnvKey).byBuildVersion.get(buildVersion);
    }

    /**
     * Creates or overwrites the binding for {@code (projectEnvKey, buildVersion)} with the given
     * version numbers actually used, and persists immediately. Called after every successful real
     * substitution (never after a validate-only run).
     */
    public synchronized ConfigDeploymentBinding record(String projectEnvKey, String buildVersion,
                                                        Integer commonVersionNumber, int envVersionNumber) {
        BindingsForKey bindings = load(projectEnvKey);
        ConfigDeploymentBinding binding = new ConfigDeploymentBinding(
                projectEnvKey, buildVersion, commonVersionNumber, envVersionNumber,
                System.currentTimeMillis());
        bindings.byBuildVersion.put(buildVersion, binding);
        persist(projectEnvKey, bindings);
        return binding;
    }

    /** One XML file's worth of content: every buildVersion binding recorded for one projectEnvKey. */
    private static class BindingsForKey implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, ConfigDeploymentBinding> byBuildVersion = new LinkedHashMap<>();
    }
}
