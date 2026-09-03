package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Action;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.IOException;

/**
 * A "find the plugin from any job page" discoverability link, contributed to every
 * {@link hudson.model.Job}'s sidebar by {@link ConfigTemplatesJobActionFactory}.
 *
 * <p>When the job has a {@link ConfigTemplatesJobProperty} configured with a non-blank
 * {@code projectKey}, this is a genuine job-specific deep link straight into that project's
 * Config Set — the structural mapping comes from that property, never invented here. When no
 * such property is configured (the default for any job that has not opted in), this falls back
 * to the plugin's generic root list page ({@link ConfigTemplatesRootAction}), exactly like the
 * existing {@code /manage/} entry does — so existing jobs that haven't set up the association
 * keep working unchanged.</p>
 *
 * <p><b>Destination when only {@code projectKey} is set (owner clarification, 2026-09-02):</b>
 * this links to the project OVERVIEW page ({@code /configTemplates/<projectKey>}, i.e.
 * {@link ProjectConfigPage}), NOT {@code /configTemplates/<projectKey>/common}. Rationale: the
 * common Config Set is the shared, project-wide layer — it is not itself job/environment
 * specific, so landing there does not fulfill "reload/manage THIS JOB's config" the way the
 * overview page does. {@link ProjectConfigPage} lists every existing env Config Set for the
 * project AND has the "New Env Config Set" creation input right on it (see that class's
 * {@code index.jelly}), so it is the actionable next step when a job is tied to a project but has
 * not (yet) picked/created a specific environment — never a dead end.</p>
 *
 * <p><b>Destination when {@code projectKey} + {@code environment} are both set, but that env
 * Config Set has no saved version yet:</b> still links straight to
 * {@code /configTemplates/<projectKey>/<environment>} ({@link EnvConfigSetPage}) — confirmed by
 * reading {@link ConfigSetPage#isExists()} and {@code EnvConfigSetPage/index.jelly}: a never-saved
 * env Config Set renders a small "does not exist yet" info banner ({@code %notExistYet}) but the
 * full editor (Monaco seeded with {@code "{}"}, content-type picker unlocked, base-chain editor,
 * Save button) still renders underneath it, ready for a first save — this is already a coherent,
 * actionable landing page (not a broken/empty screen), so no further UX change was needed here.</p>
 *
 * <p><b>Label</b> is kept character-for-character identical to
 * {@link ConfigTemplatesRootAction#getDisplayName()} ("Config Templates") in both cases, so this
 * is immediately recognizable as the same feature regardless of which page the user found it
 * from or whether the job-specific association is configured; only the destination/tooltip
 * ({@link #getDescription()}-style text via {@link #getUrlName()}'s target and this class's
 * javadoc) differs.</p>
 *
 * <p><b>{@link #getUrlName()} returns a plain, job-relative segment</b> ({@code "configTemplates"},
 * no leading {@code "/"}), per the {@code hudson.model.Action#getUrlName()} javadoc
 * (https://javadoc.jenkins.io/hudson/model/Action.html): "if this method returns 'xyz', and if the
 * parent object (that this action is associated with) is bound to /foo/bar/zot, then this action
 * object will be exposed to /foo/bar/zot/xyz" — since the parent here is the {@link
 * hudson.model.Job} itself (bound to {@code /job/<name>}), this action is exposed at {@code
 * /job/<name>/configTemplates}, exactly like the built-in "Configure" sidebar entry
 * ({@code /job/<name>/configure}) works. This makes the sidebar link itself job-scoped, matching
 * every other Jenkins job action, instead of an external link into the plugin's own
 * {@code /configTemplates/...} URL space.</p>
 *
 * <p>The actual destination (generic root list / project overview / specific env page) still
 * lives under {@code /configTemplates/...} — that real work is not duplicated here. Instead,
 * {@link #doIndex(StaplerRequest, StaplerResponse)} is Stapler's conventional "handle a request landing exactly on
 * this object's own URL" entry point (dispatched for {@code /job/<name>/configTemplates} and
 * {@code /job/<name>/configTemplates/}), and it issues an HTTP redirect ({@code
 * StaplerResponse#sendRedirect2}) to whichever of the three destinations {@link
 * #resolveDestinationUrl()} computes — the exact same 3-case resolution logic this class always
 * had, just now driving a redirect target instead of a precomputed link href.</p>
 */
public class ConfigTemplatesJobAction implements Action {

    /** {@code null} means "not configured for this job" — the generic root-list fallback. */
    private final ConfigTemplatesJobProperty property;

    public ConfigTemplatesJobAction() {
        this(null);
    }

    public ConfigTemplatesJobAction(ConfigTemplatesJobProperty property) {
        this.property = property;
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

    /**
     * Tooltip-style description surfaced by the sidebar link, distinguishing the job-specific
     * deep link from the generic browse-everything fallback while the visible label itself stays
     * identical in both cases.
     */
    public String getDescription() {
        String projectKey = property == null ? null : property.getProjectKey();
        if (projectKey == null || projectKey.isEmpty()) {
            return "Browse all Config Templates";
        }
        String environment = property.getEnvironment();
        if (environment == null || environment.isEmpty()) {
            return "Open this job's Config Templates project (pick or create an environment)";
        }
        return "Open this job's Config Templates";
    }

    @Override
    public String getUrlName() {
        // Plain relative segment (no leading "/"): Stapler exposes this action at
        // <job-url>/configTemplates, e.g. /job/<name>/configTemplates — see this class's javadoc.
        return "configTemplates";
    }

    /**
     * Stapler's conventional handler for a request landing exactly on this action's own URL
     * ({@code /job/<name>/configTemplates}, and {@code /job/<name>/configTemplates/}) — redirects
     * to whichever real destination page {@link #resolveDestinationUrl()} computes. Permission is
     * re-checked here (mirroring every other doXxx entry point in this plugin, e.g. {@code
     * ConfigSetPage#doSubmitSave}) rather than relying solely on {@link
     * ConfigTemplatesJobActionFactory}'s factory-level gate, since that gate only controls whether
     * the sidebar *link* is rendered, not whether this URL is reachable.
     *
     * <p>The redirect target is built as {@code req.getContextPath() + resolveDestinationUrl()},
     * NOT the raw {@link #resolveDestinationUrl()} string alone: unlike {@code
     * Action#getUrlName()} (where Stapler itself resolves a leading {@code "/"} against the
     * webapp's context path per that method's javadoc), {@link StaplerResponse#sendRedirect2}
     * issues a plain HTTP redirect with no such context-path translation, so a Jenkins instance
     * not deployed at the servlet-container root (e.g. {@code /jenkins/...}, as this plugin's own
     * {@code JenkinsRule} tests run under) would otherwise be redirected to the wrong host-root
     * path instead of {@code <contextPath>/configTemplates/...}.</p>
     */
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.sendRedirect2(req.getContextPath() + resolveDestinationUrl());
    }

    /**
     * The same 3-case resolution logic this class always used to compute {@code getUrlName()}'s
     * link href, now producing a redirect target instead — see this class's javadoc for the
     * destination rationale in each case.
     */
    private String resolveDestinationUrl() {
        String projectKey = property == null ? null : property.getProjectKey();
        if (projectKey == null || projectKey.isEmpty()) {
            // Not configured at all: genuinely "nothing set up, browse everything".
            return "/" + ConfigTemplatesRootAction.URL_NAME;
        }
        String environment = property.getEnvironment();
        if (environment == null || environment.isEmpty()) {
            // Project set, no environment yet: land on the actionable project OVERVIEW page
            // (lists env Config Sets + "New Env Config Set" input), not the project-wide common
            // page — see this class's javadoc for why.
            return "/" + ConfigTemplatesRootAction.URL_NAME + "/" + projectKey;
        }
        return "/" + ConfigTemplatesRootAction.URL_NAME + "/" + projectKey + "/" + environment;
    }
}
