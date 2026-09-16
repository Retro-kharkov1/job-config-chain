package io.jenkins.plugins.jobconfigchain.persistence;

import hudson.XmlFile;
import hudson.util.XStream2;
import io.jenkins.plugins.jobconfigchain.model.ConfigDeploymentBinding;
import io.jenkins.plugins.jobconfigchain.model.ResolvedBaseVersion;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists {@link ConfigDeploymentBinding}s as a single append/update-in-place list under
 * {@code $JENKINS_HOME/config-template-sync/deployment-bindings.xml} (see nfr.md's "No external
 * database of any kind" and "Not git-backed" rules). One binding
 * exists per {@code buildVersion} (a Run's own automatically-derived identity string, see
 * pipeline-steps.md's "Build-identity pinning" section); a repeat substitution under the same
 * identity updates it in place rather than duplicating it. Re-keyed to pure Run-identity
 * (tech-lead scoping decision, 2026-09-14, pipeline-steps.md §1) — no separate Config-Key/environment
 * dimension is needed, since every call now resolves per-Job by construction. No pruning/retention
 * policy is applied (see config-sets-and-versioning.md's "there is deliberately no pruning policy
 * in v1" rule).
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

    public synchronized ConfigDeploymentBinding find(String buildVersion) {
        for (ConfigDeploymentBinding binding : loadAll()) {
            if (binding.matches(buildVersion)) {
                return binding;
            }
        }
        return null;
    }

    /** Creates or updates (never duplicates) the binding for this buildVersion (Run-identity). */
    public synchronized void save(String buildVersion, List<ResolvedBaseVersion> resolvedBaseChain,
                                   int ownConfigVersionNumber, long deployedAtUtcEpochMillis) {
        List<ConfigDeploymentBinding> all = loadAll();
        ConfigDeploymentBinding existing = null;
        for (ConfigDeploymentBinding binding : all) {
            if (binding.matches(buildVersion)) {
                existing = binding;
                break;
            }
        }
        if (existing != null) {
            existing.update(resolvedBaseChain, ownConfigVersionNumber, deployedAtUtcEpochMillis);
        } else {
            all.add(new ConfigDeploymentBinding(buildVersion,
                    resolvedBaseChain, ownConfigVersionNumber, deployedAtUtcEpochMillis));
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
