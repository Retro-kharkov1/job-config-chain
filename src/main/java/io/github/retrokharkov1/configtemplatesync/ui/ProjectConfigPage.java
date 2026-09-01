package io.github.retrokharkov1.configtemplatesync.ui;

import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;

import java.util.List;

/**
 * Groups one Config Project's common Config Set with its env Config Sets (FR-34/FR-35), exposed at
 * {@code /configTemplates/<projectKey>/}. Not itself persisted — a pure URL/navigation grouping
 * over the structural {@code projectKey} pairing already defined by the domain model (OQ-2), never
 * a second pairing mechanism.
 *
 * <p>Env Config Sets are dispatched via {@code getDynamic(String)} (see the note on
 * {@link ConfigTemplatesRootAction} for why a catch-all, not a named {@code getEnv(String)} getter,
 * is required here to avoid Jenkins core's post-SECURITY-595 Stapler getter-routing filter).</p>
 */
public class ProjectConfigPage {

    private final String projectKey;
    private final ConfigSetRepository repository;

    ProjectConfigPage(String projectKey, ConfigSetRepository repository) {
        this.projectKey = projectKey;
        this.repository = repository;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getDisplayName() {
        return projectKey;
    }

    /** Global common Config Set for this project (FR-30/FR-31). Created on first save if absent. */
    public CommonConfigSetPage getCommon() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new CommonConfigSetPage(projectKey, repository);
    }

    /** All env Config Sets for this project (FR-34 list view). */
    public List<ConfigSet> getEnvConfigSets() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return repository.listEnv(projectKey);
    }

    /** Stapler catch-all dispatch: {@code /configTemplates/<projectKey>/<environment>/} (FR-34). */
    public Object getDynamic(String environment) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new EnvConfigSetPage(projectKey, environment, repository);
    }
}
