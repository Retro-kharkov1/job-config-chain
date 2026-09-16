package io.jenkins.plugins.jobconfigchain.ui;

import hudson.Extension;
import hudson.model.ManagementLink;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerProxy;

import java.util.List;

/**
 * The global "Config Templates" admin screen (see admin-ui.md's "Global level" section), reachable
 * at {@code /configTemplates},
 * modeled on Jenkins' own Manage Jenkins &#8594; Managed Files list/edit interaction shape (see
 * "UI reference groundings" in the requirements spec). Lists every common Config Set and dispatches
 * directly to that project's {@link CommonConfigSetPage} at {@code /configTemplates/<projectKey>/}
 * via Stapler's {@code getDynamic(String)} catch-all hook — {@code /configTemplates/<projectKey>/}
 * IS the common Config Set editor directly (owner decision, 2026-09-14, see
 * {@code admin-ui.md}'s "Config-Key hub page IS the common editor directly" section); there is no
 * separate near-empty hub page and no {@code /common} URL segment anymore.
 *
 * <p><b>Entry point: {@link ManagementLink}, not {@code RootAction} (owner decision, 2026-09-02):</b>
 * this used to implement {@code hudson.model.RootAction}, which Jenkins core surfaces as a
 * top-level icon in the global top-right navigation bar (populated from
 * {@code Jenkins#getActions()}, which core only ever populates from {@code RootAction}
 * extensions — see {@code jenkins.model.Jenkins#getActions()} javadoc: "Adding Action is
 * primarily useful for plugins to contribute an item to the navigation bar of the top page").
 * The owner wants this screen off that top-nav entirely and reachable only from
 * {@code /manage/} (Manage Jenkins) instead. {@link ManagementLink} is the extension point core
 * itself uses for every built-in Manage Jenkins tile; Jenkins' root object resolves a bare
 * {@code /<urlName>} URL token by trying both its {@code RootAction} extensions AND its
 * {@code ManagementLink} extensions (via {@code Jenkins#getManagementLinks()} /
 * {@code ManagementLink.all()}), so switching from one extension point to the other changes
 * only how the admin *navigates to* this screen — {@link #getUrlName()} is deliberately left at
 * {@code "configTemplates"} so {@code /configTemplates/<projectKey>/...} (and every existing
 * bookmark/link/test against it) resolves exactly as before, it is simply no longer echoed into
 * the top-nav icon bar.</p>
 *
 * <p><b>Category constraint (grounded against {@code hudson.model.ManagementLink.Category}
 * javadoc, https://javadoc.jenkins.io/hudson/model/ManagementLink.Category.html, current as of
 * this plugin's {@code jenkins.version} baseline and confirmed unchanged in the newest published
 * core javadoc at the time of writing): {@code Category} is a small, closed, non-extensible
 * enum — {@code CONFIGURATION}, {@code PLUGINS}, {@code SECURITY}, {@code STATUS},
 * {@code TOOLS}, {@code TROUBLESHOOTING}, {@code MISC}, {@code UNCATEGORIZED}. There is no
 * mechanism, at any Jenkins core version, for a plugin to register a brand-new named category
 * such as "Project Management" — this is a hard constraint of the extension point itself, not
 * something a {@code jenkins.version} bump in this plugin's {@code pom.xml} would unlock. Of the
 * fixed set, {@link ManagementLink.Category#TOOLS TOOLS} ("tools for administrators, ... as well
 * as specific stand-alone administrative features") is the closest semantic fit for a
 * stand-alone admin screen like this one; {@code CONFIGURATION} was the next-closest alternative
 * considered and rejected because it is scoped to Jenkins' own system configuration surface. This
 * is flagged back to the owner as a real constraint, not silently downgraded.</p>
 *
 * <p><b>Why {@code getDynamic}, not {@code getProject(String)}:</b> a named getter with a
 * {@code String} parameter is exactly the pattern Jenkins core's post-SECURITY-595 Stapler
 * routing-decision filter blocks by default for any class not already on its whitelist (confirmed
 * empirically: {@code getProject(String)} was rejected with a 404 and a
 * "add to the whitelist" warning during test-writing for this milestone) — that whitelist is an
 * admin-side runtime file (`stapler-whitelist.txt`), not something a plugin can ship pre-approved.
 * {@code getDynamic(String)} is Stapler's dedicated, explicitly-opted-into wildcard-dispatch
 * extension point (distinct from the implicit getter-name-matches-URL-token convention that filter
 * targets) and is unaffected by it — the standard pattern for "the remaining path segment is a
 * caller-supplied key, not a fixed property name" (e.g. `ItemGroup#getItem(String)`-style folder
 * navigation elsewhere in Jenkins core uses the same catch-all shape).</p>
 *
 * <p><b>Why {@link StaplerProxy} is still kept</b> despite {@link ManagementLink} already being a
 * full Stapler {@code Action}/model object on its own: {@code StaplerProxy#getTarget()} is used
 * here purely as a request-time {@code ADMINISTER} permission gate that fires on every request to
 * this screen (including the root list page itself, not just the {@code getDynamic} sub-paths) —
 * Stapler's {@code StaplerProxy} handling is generic and applies to any resolved object in a URL
 * dispatch chain regardless of its concrete extension-point type, so this continues to work
 * unchanged under {@link ManagementLink} exactly as it did under {@code RootAction}.
 * {@link #getRequiredPermission()} is additionally overridden below so core's own Manage Jenkins
 * tile-listing logic also hides the tile from non-admins, but that alone would not gate a direct
 * URL hit — the {@code StaplerProxy} check is what does that.</p>
 */
@Extension
public class ConfigTemplatesRootAction extends ManagementLink implements StaplerProxy {

    public static final String URL_NAME = "configTemplates";

    private final ConfigSetRepository repository;

    public ConfigTemplatesRootAction() {
        this(new ConfigSetRepository());
    }

    ConfigTemplatesRootAction(ConfigSetRepository repository) {
        this.repository = repository;
    }

    @Override
    public String getIconFileName() {
        // only shown to users who can see it; icon resolution mirrors Jenkins core's Managed Files link
        return "symbol-document-text-outline-plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Config Templates";
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @Override
    public String getDescription() {
        return "Manage per-project Config Sets and their environment overlays.";
    }

    /**
     * Closest available fit in the fixed, non-extensible {@link ManagementLink.Category} enum —
     * see the class javadoc's "Category constraint" note for why "Project Management" itself is
     * not achievable at any Jenkins core version.
     */
    @Override
    public ManagementLink.Category getCategory() {
        return ManagementLink.Category.TOOLS;
    }

    /** Hides this tile from the Manage Jenkins page for non-admins; the actual enforcement for a
     *  direct URL hit is {@link #getTarget()} below (generic to any Stapler-dispatched object). */
    @Override
    public hudson.security.Permission getRequiredPermission() {
        return Jenkins.ADMINISTER;
    }

    /** Permission gate: this whole section is admin-only, same as Manage Jenkins itself. */
    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    public List<io.jenkins.plugins.jobconfigchain.model.ConfigSet> getCommonConfigSets() {
        return repository.listAllCommon();
    }

    /**
     * Stapler catch-all dispatch: {@code /configTemplates/<projectKey>/...} (Config-Key grouping,
     * see overview.md's "Config Key" entity). Dispatches directly to {@link CommonConfigSetPage} —
     * {@code /configTemplates/<projectKey>/}
     * IS the common Config Set editor, not a separate landing page one segment up from it (owner
     * decision, 2026-09-14).
     */
    public Object getDynamic(String projectKey) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new CommonConfigSetPage(projectKey, repository);
    }

    ConfigSetRepository getRepository() {
        return repository;
    }
}
