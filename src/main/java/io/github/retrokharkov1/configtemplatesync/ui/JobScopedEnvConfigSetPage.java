package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Job;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;

/**
 * The job-scoped env Config Set screen at {@code /job/<name>/configTemplates/<environment>} (FR-77).
 *
 * <p>This class deliberately owns NO editing logic of its own. It is a thin wrapper that holds the
 * job context (for the banner and breadcrumb) plus a {@link #getDelegate() delegate} which is the
 * very same {@link EnvConfigSetPage} object {@link ProjectConfigPage#getDynamic(String)} builds for
 * the global URL, constructed from the same {@code (projectKey, environment, repository)} triple.
 * Its Jelly view renders that delegate's own {@code index.jelly} rather than duplicating it.</p>
 *
 * <p><b>Why that matters (FR-77a):</b> the same Config Set is now reachable from two URLs — the
 * global {@code /configTemplates/<projectKey>/<environment>} and this job-scoped one. Because both
 * resolve to an {@code EnvConfigSetPage} over the same repository key, there is exactly one save
 * path, one validation path and one persisted entity/version history regardless of which URL the
 * operator arrived through. Guaranteeing that by CONSTRUCTION rather than by convention is the
 * whole point: a forked, job-scoped copy of the editor would be a second way to write the same
 * data, and the two would drift.</p>
 *
 * <p>The global URL remains the canonical one; this screen exists so drilling into an environment
 * never throws the operator out of their job's context (FR-77).</p>
 */
public class JobScopedEnvConfigSetPage {

    private final Job<?, ?> job;
    private final String projectKey;
    private final String environment;
    private final EnvConfigSetPage delegate;

    JobScopedEnvConfigSetPage(Job<?, ?> job, String projectKey, String environment, ConfigSetRepository repository) {
        this.job = job;
        this.projectKey = projectKey;
        this.environment = environment;
        this.delegate = new EnvConfigSetPage(projectKey, environment, repository);
    }

    public Job<?, ?> getJob() {
        return job;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getEnvironment() {
        return environment;
    }

    /**
     * The shared editor this screen renders. Never a copy — see this class's javadoc.
     */
    public EnvConfigSetPage getDelegate() {
        return delegate;
    }

    /**
     * The canonical (global) URL for this same Config Set, shown to the operator so it is never a
     * secret that the data lives in one place reachable two ways (FR-77a's disclosure obligation).
     */
    public String getCanonicalUrl() {
        return Jenkins.get().getRootUrl() + "configTemplates/" + projectKey + "/" + environment + "/";
    }
}
