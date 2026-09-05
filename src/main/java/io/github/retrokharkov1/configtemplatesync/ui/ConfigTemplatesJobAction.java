package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Action;
import hudson.model.Job;
import hudson.util.HttpResponses;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.List;

/**
 * A "find the plugin from any job page" discoverability link, contributed to every
 * {@link hudson.model.Job}'s sidebar by {@link ConfigTemplatesJobActionFactory}.
 *
 * <p>Unlike the plugin's global {@code /configTemplates/...} pages, this action renders its OWN
 * page (FR-76) — it never issues an HTTP redirect. That page is the single place a job's
 * {@link ConfigTemplatesJobProperty} association is viewed AND edited, presenting one of three
 * content states depending on that property's current values:</p>
 * <ul>
 *   <li>no property (or a blank {@code projectKey}) — "not yet associated", with an editable
 *       {@code projectKey} field and a Save action;</li>
 *   <li>{@code projectKey} set, {@code environment} blank — the association form plus a list of
 *       every existing env Config Set for that project (mirrors {@link ProjectConfigPage});</li>
 *   <li>{@code projectKey} AND {@code environment} both set — same as above, with the job's chosen
 *       environment highlighted and its job-scoped per-environment page (FR-77, task T5)
 *       prominent.</li>
 * </ul>
 *
 * <p><b>The property is never cached.</b> This class holds the owning {@link Job} itself, not a
 * {@link ConfigTemplatesJobProperty} snapshot, and re-reads {@code job.getProperty(...)} on every
 * call via {@link #getProperty()} — a cached reference would go stale the moment
 * {@link #doSaveAssociation(String, String)}/{@link #doAssociateEnvironment(String)} (or any other
 * code path) saves a new property onto the job, and FR-77's per-environment page explicitly
 * requires resolving against the job's CURRENT association at request time, not one captured when
 * this action was constructed.</p>
 *
 * <p><b>Label</b> is kept character-for-character identical to
 * {@link ConfigTemplatesRootAction#getDisplayName()} ("Config Templates"), so this is immediately
 * recognizable as the same feature regardless of whether the job-specific association is
 * configured.</p>
 *
 * <p><b>{@link #getUrlName()} returns a plain, job-relative segment</b> ({@code "configTemplates"},
 * no leading {@code "/"}), per the {@code hudson.model.Action#getUrlName()} javadoc
 * (https://javadoc.jenkins.io/hudson/model/Action.html): "if this method returns 'xyz', and if the
 * parent object (that this action is associated with) is bound to /foo/bar/zot, then this action
 * object will be exposed to /foo/bar/zot/xyz" — since the parent here is the {@link
 * hudson.model.Job} itself (bound to {@code /job/<name>}), this action is exposed at {@code
 * /job/<name>/configTemplates}, exactly like the built-in "Configure" sidebar entry
 * ({@code /job/<name>/configure}) works.</p>
 *
 * <p>Rendering itself (the FR-76 three-state page) is {@code ConfigTemplatesJobAction/index.jelly}
 * — a separate task (T4), not part of this class. The per-environment
 * {@code /job/<name>/configTemplates/<environment>} dispatch (FR-77, {@code getDynamic(String)})
 * is also a separate task (T5), deliberately not added here.</p>
 */
public class ConfigTemplatesJobAction implements Action {

    private final Job<?, ?> job;

    public ConfigTemplatesJobAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    public String getIconFileName() {
        // Same icon as ConfigTemplatesRootAction, for visual consistency between the two entry points.
        return "symbol-document-text-outline-plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Config Templates";
    }

    @Override
    public String getUrlName() {
        // Plain relative segment (no leading "/"): Stapler exposes this action at
        // <job-url>/configTemplates, e.g. /job/<name>/configTemplates — see this class's javadoc.
        return "configTemplates";
    }

    /** The job this action was contributed to — read fresh, never cached. Exposed for the Jelly view. */
    public Job<?, ?> getJob() {
        return job;
    }

    /**
     * The job's current {@link ConfigTemplatesJobProperty}, or {@code null} if it has none. Always
     * re-reads {@code job.getProperty(...)} rather than returning a cached field — see this class's
     * javadoc for why a cached reference would be unsafe here.
     */
    public ConfigTemplatesJobProperty getProperty() {
        return job.getProperty(ConfigTemplatesJobProperty.class);
    }

    /** {@code true} once a non-blank {@code projectKey} has been associated with this job. */
    public boolean hasAssociation() {
        ConfigTemplatesJobProperty property = getProperty();
        return property != null && !isBlank(property.getProjectKey());
    }

    /** The current {@code projectKey}, or {@code ""} if this job has no association. */
    public String getProjectKey() {
        ConfigTemplatesJobProperty property = getProperty();
        return property == null ? "" : property.getProjectKey();
    }

    /** The current {@code environment}, or {@code ""} if none has been picked yet. */
    public String getEnvironment() {
        ConfigTemplatesJobProperty property = getProperty();
        return property == null ? "" : property.getEnvironment();
    }

    /**
     * Every existing env Config Set for the currently-associated project, or an empty list when
     * this job has no association — the FR-76 project-only/both-set states' env list, mirroring
     * {@link ProjectConfigPage#getEnvConfigSets()}.
     */
    public List<ConfigSet> getEnvConfigSets() {
        String projectKey = getProjectKey();
        if (isBlank(projectKey)) {
            return List.of();
        }
        return new ConfigSetRepository().listEnv(projectKey);
    }

    /**
     * Saves the association form (FR-76's editable {@code projectKey}/{@code environment} fields)
     * as a new {@link ConfigTemplatesJobProperty} on this job.
     *
     * <p><b>Permission:</b> {@link Jenkins#ADMINISTER} is checked first (this whole page is
     * admin-only, same as every other entry point in this plugin), and {@link Job#CONFIGURE} on the
     * specific target job is ALSO checked (owner decision, design §6 risk 1): today
     * {@code Jenkins.ADMINISTER} already implies {@code Job.CONFIGURE}, so this changes no observable
     * behavior — the point is to make the intent ("this handler mutates one specific job's config,
     * so it must hold that job's own CONFIGURE permission too") explicit in code, so nobody deletes
     * this check later as "redundant" if the outer {@code ADMINISTER} gate is ever relaxed.</p>
     */
    @RequirePOST
    public HttpResponse doSaveAssociation(@QueryParameter String projectKey,
                                           @QueryParameter String environment) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        job.addProperty(new ConfigTemplatesJobProperty(projectKey, environment));
        return HttpResponses.redirectToDot(); // FR-76: reload of this same page shows the saved values
    }

    /**
     * The FR-76 project-only state's one-click "associate this job with {@code <environment>}"
     * action: keeps the job's current {@code projectKey} and only updates {@code environment}.
     *
     * <p>Same permission model as {@link #doSaveAssociation(String, String)} — see that method's
     * javadoc.</p>
     */
    @RequirePOST
    public HttpResponse doAssociateEnvironment(@QueryParameter String environment) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        job.addProperty(new ConfigTemplatesJobProperty(getProjectKey(), environment));
        return HttpResponses.redirectToDot();
    }


    /**
     * Dispatches the trailing {@code <environment>} path segment of
     * {@code /job/<name>/configTemplates/<environment>} to the job-scoped env screen (FR-77).
     *
     * <p>A catch-all {@code getDynamic(String)} rather than a named getter, for the same reason
     * {@link ConfigTemplatesRootAction} and {@link ProjectConfigPage} already use one: Jenkins core's
     * post-SECURITY-595 Stapler getter-routing filter does not route arbitrary named getters.</p>
     *
     * <p>The environment is resolved against the job's CURRENT association, read fresh — never
     * against a value cached at construction time. A job with no association still gets a rendered
     * page (never a dead end): the wrapper is built with a blank project key and its view explains
     * the state instead of silently editing the wrong Config Set.</p>
     */
    public Object getDynamic(String environment) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new JobScopedEnvConfigSetPage(job, getProjectKey(), environment, new ConfigSetRepository());
    }
    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
