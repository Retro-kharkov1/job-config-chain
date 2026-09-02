package io.github.retrokharkov1.configtemplatesync.persistence;

import hudson.XmlFile;
import hudson.util.XStream2;
import io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding;
import io.github.retrokharkov1.configtemplatesync.model.ResolvedBaseVersion;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists {@link ConfigDeploymentBinding}s as a single append/update-in-place list under
 * {@code $JENKINS_HOME/config-template-sync/deployment-bindings.xml} (NFR-1/NFR-2). One binding
 * exists per (projectKey, environment, buildVersion) tuple; a repeat substitution for the same
 * tuple updates it in place rather than duplicating it (FR-23). No pruning/retention policy is
 * applied (OQ-4, resolved 2026-08-27: no pruning in v1).
 */
public class ConfigDeploymentBindingRepository {

    private static final String SUBDIRECTORY = "config-template-sync";
    private static final String FILE_NAME = "deployment-bindings.xml";

    private final File file;

    public ConfigDeploymentBindingRepository() {
        this(new File(Jenkins.get().getRootDir(), SUBDIRECTORY));
    }

    public ConfigDeploymentBindingRepository(File baseDir) {
        if (!baseDir.exists() && !baseDir.mkdirs() && !baseDir.exists()) {
            throw new UncheckedIOException(new IOException("Could not create " + baseDir));
        }
        this.file = new File(baseDir, FILE_NAME);
    }

    public synchronized ConfigDeploymentBinding find(String projectKey, String environment, String buildVersion) {
        for (ConfigDeploymentBinding binding : loadAll()) {
            if (binding.matches(projectKey, environment, buildVersion)) {
                return binding;
            }
        }
        return null;
    }

    /** Creates or updates (never duplicates) the binding for this (projectKey, environment, buildVersion). */
    public synchronized void save(String projectKey, String environment, String buildVersion,
                                   List<ResolvedBaseVersion> resolvedBaseChain, int envVersionNumber,
                                   long deployedAtUtcEpochMillis) {
        List<ConfigDeploymentBinding> all = loadAll();
        ConfigDeploymentBinding existing = null;
        for (ConfigDeploymentBinding binding : all) {
            if (binding.matches(projectKey, environment, buildVersion)) {
                existing = binding;
                break;
            }
        }
        if (existing != null) {
            existing.update(resolvedBaseChain, envVersionNumber, deployedAtUtcEpochMillis);
        } else {
            all.add(new ConfigDeploymentBinding(projectKey, environment, buildVersion,
                    resolvedBaseChain, envVersionNumber, deployedAtUtcEpochMillis));
        }
        writeAll(all);
    }

    @SuppressWarnings("unchecked")
    private List<ConfigDeploymentBinding> loadAll() {
        XmlFile xmlFile = xmlFile();
        if (!xmlFile.exists()) {
            return new ArrayList<>();
        }
        try {
            return (List<ConfigDeploymentBinding>) xmlFile.read();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load deployment bindings", e);
        }
    }

    private void writeAll(List<ConfigDeploymentBinding> all) {
        try {
            xmlFile().write(all);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save deployment bindings", e);
        }
    }

    private XmlFile xmlFile() {
        return new XmlFile(new XStream2(), file);
    }
}
