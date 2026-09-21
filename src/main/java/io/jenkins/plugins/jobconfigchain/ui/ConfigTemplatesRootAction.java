package io.jenkins.plugins.jobconfigchain.ui;

import hudson.Extension;
import hudson.model.ManagementLink;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import net.sf.json.JSONObject;
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
    /**
     * Resolved {@link SharedBlocks} class for {@code st:include class="${it.sharedBlocksClass}"},
     * mirroring {@link ConfigTemplatesJobAction#getSharedBlocksClass()} - see that method for why
     * the class object is bound rather than a literal String.
     */
    public Class<SharedBlocks> getSharedBlocksClass() {
        return SharedBlocks.class;
    }


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
        // Format is "symbol-<name> plugin-<plugin-short-name>", SPACE-separated, not one
        // dash-joined token: core's lib/layout/icon.jelly hands the value to
        // Functions#extractPluginNameFromIconSrc, which looks for a separate whitespace-delimited
        // word starting with "plugin-". A dash-joined value yields an empty plugin name, the
        // symbol is then looked up in core's own set under a name that does not exist there, and
        // Jenkins renders its missing-symbol placeholder — the cross the owner reported on
        // 2026-09-18. Verified against a shipped plugin doing the same thing: credentials uses
        // "symbol-credentials plugin-credentials". Requires ionicons-api as a real runtime
        // dependency (declared in pom.xml), otherwise the symbol still cannot be resolved.
        return "symbol-layers-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return Messages.ConfigTemplates_DisplayName();
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @Override
    public String getDescription() {
        return Messages.ConfigTemplatesRootAction_Description();
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

    // ---- Deleted Config Sets --------------------------------------------------------------------
    // A withdrawn Config Set keeps its file, its history and its name. It disappears from the live
    // inventory above - and from the job base-chain picker, and from every resolver - but an
    // administrator can still see it here, put it back, or destroy it for good.

    /** The withdrawn Config Sets, revealed by the "show deleted" toggle. */
    public List<ConfigSet> getDeletedConfigSets() {
        return repository.listDeletedCommon();
    }

    /**
     * The project keys currently occupied by a withdrawn Config Set, as a ready-to-embed JS string
     * literal. Used only to warn before navigating to a name that cannot be created - the real
     * refusal is server-side, in the save path, because this page creates nothing by itself.
     */
    public String getDeletedProjectKeysJsonForScript() {
        net.sf.json.JSONArray keys = new net.sf.json.JSONArray();
        for (ConfigSet deleted : getDeletedConfigSets()) {
            keys.add(deleted.getProjectKey());
        }
        return ConfigSetPage.toJsScriptStringLiteral(keys.toString());
    }

    /**
     * The Config Set named by a {@code ?deleted=} / {@code ?restored=} / {@code ?purged=} parameter
     * after a lifecycle action redirected here, or {@code null}.
     *
     * <p>Rendered server-side rather than as a toast because the action navigates away from the
     * page that would have shown the toast. It is validated against the repository rather than
     * echoed, so a hand-typed parameter renders nothing: the deleted and restored banners require
     * the record to actually be in that state, and the purged one requires it to be gone.</p>
     */
    public String getFlashDeletedKey() {
        String key = flashParameter("deleted");
        ConfigSet record = key == null ? null : repository.findCommonIncludingDeleted(key);
        return record != null && record.isDeleted() ? key : null;
    }

    public String getFlashRestoredKey() {
        String key = flashParameter("restored");
        ConfigSet record = key == null ? null : repository.findCommonIncludingDeleted(key);
        return record != null && !record.isDeleted() ? key : null;
    }

    public String getFlashPurgedKey() {
        String key = flashParameter("purged");
        return key != null && repository.findCommonIncludingDeleted(key) == null ? key : null;
    }

    private static String flashParameter(String name) {
        org.kohsuke.stapler.StaplerRequest2 request = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (request == null) {
            return null;
        }
        String value = request.getParameter(name);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /**
     * Restores a withdrawn Config Set. No typed-name confirmation: it is fully reversible - the
     * worst outcome is withdrawing it again - and gating a reversible action behind the same
     * ceremony as an irreversible one teaches operators to type through both without reading.
     */
    @JavaScriptMethod(name = "restoreConfigSet")
    public JSONObject jsRestoreConfigSet(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String key = readProjectKey(payloadJson);
        if (key == null) {
            return lifecycleError("NOT_FOUND", "No Config Set named.");
        }
        return new CommonConfigSetPage(key, repository).jsRestoreConfigSet();
    }

    /** What purging the named Config Set would destroy, and whether anything still references it. */
    @JavaScriptMethod(name = "purgePreflight")
    public JSONObject jsPurgePreflight(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String key = readProjectKey(payloadJson);
        if (key == null) {
            return lifecycleError("NOT_FOUND", "No Config Set named.");
        }
        return new CommonConfigSetPage(key, repository).jsPurgePreflight();
    }

    /** Destroys a withdrawn Config Set, after the same typed-name confirmation as the delete. */
    @JavaScriptMethod(name = "purgeConfigSet")
    public JSONObject jsPurgeConfigSet(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String key = readProjectKey(payloadJson);
        if (key == null) {
            return lifecycleError("NOT_FOUND", "No Config Set named.");
        }
        return new CommonConfigSetPage(key, repository).jsPurgeConfigSet(payloadJson);
    }

    private static String readProjectKey(String payloadJson) {
        com.google.gson.JsonObject payload = ConfigSetPage.parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("projectKey")
                || !payload.get("projectKey").isJsonPrimitive()) {
            return null;
        }
        String key = payload.get("projectKey").getAsString();
        return key.trim().isEmpty() ? null : key;
    }

    private static JSONObject lifecycleError(String code, String message) {
        JSONObject result = new JSONObject();
        result.put("ok", false);
        result.put("errorCode", code);
        result.put("error", message);
        return result;
    }
}
