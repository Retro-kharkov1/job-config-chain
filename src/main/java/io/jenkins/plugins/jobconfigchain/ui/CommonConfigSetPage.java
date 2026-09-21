package io.jenkins.plugins.jobconfigchain.ui;

import io.jenkins.plugins.jobconfigchain.merge.TemplateGenerator;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.bind.JavaScriptMethod;

/**
 * The global Config Set edit page (see admin-ui.md's "Global level" section), served directly at
 * {@code /configTemplates/<projectKey>/} — this IS the common Config Set editor, not a separate
 * page one URL segment deeper (owner decision, 2026-09-14: the {@code /common} URL segment and the
 * near-empty hub page that used to precede it are both retired).
 */
public class CommonConfigSetPage extends ConfigSetPage {

    CommonConfigSetPage(String projectKey, ConfigSetRepository repository) {
        super(projectKey, repository);
    }

    @Override
    ConfigSetRole getRole() {
        return ConfigSetRole.COMMON;
    }

    @Override
    String getEnvironment() {
        return null;
    }

    @Override
    String getDisplayNameSeed() {
        return projectKey + " (common)";
    }

    /**
     * The {@code -common} title suffix is retired (owner decision, 2026-09-14): this page is now
     * the ONLY page reachable under {@code /configTemplates/<projectKey>/}, so there is nothing
     * left to disambiguate from — mirrors {@link ConfigTemplatesJobAction#getDisplayName()} already
     * carrying no role suffix of its own.
     */
    public String getDisplayName() {
        return projectKey;
    }

    // ---- Template generation (see pipeline-steps.md's "Template generation" section and
    // admin-ui.md's "Template generation (UI action)" section) ----
    // Own copy, unchanged from the pre-2026-09-12 shared ConfigSetPage#doRenderTemplate/
    // #jsGenerateTemplate/#isTemplateAvailable — this page has no override/base-chain draft
    // concept at all (its own single editor IS the committed content), so "generate from the
    // current unsaved draft" (the new EnvConfigSetPage/ConfigTemplatesJobAction contract) has no
    // meaning here; the original "ACTIVE version(s) only, never a draft" guarantee is
    // deliberately kept as-is, including the disabled-button gating on {@link #isExists()}.

    /**
     * Jelly-visible: whether the "Generate Template" action should render enabled (disabled with a
     * "no active version yet" label when there is nothing to template).
     */
    public boolean isTemplateAvailable() {
        return !isDeleted() && getActiveVersion() != null;
    }

    @jenkins.security.stapler.StaplerDispatchable
    public JSONObject doRenderTemplate() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl();
    }

    @JavaScriptMethod(name = "generateTemplate")
    public JSONObject jsGenerateTemplate() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl();
    }

    private JSONObject renderTemplateImpl() {
        JSONObject result = new JSONObject();
        ConfigSetVersion active = getActiveVersion();
        if (active == null) {
            result.put("ok", false);
            result.put("error", "No active version yet — nothing to template.");
            return result;
        }
        ContentType type = getConfigSet().getContentType();
        TreeNode template = TemplateGenerator.fromContent(active.getContentJson(), type);
        result.put("ok", true);
        result.put("contentType", type.name());
        result.put("template", TreeFormats.forType(type).serialize(template));
        return result;
    }

    // ---- Deletion lifecycle ---------------------------------------------------------------------
    // Withdrawing a Config Set is reversible: the record keeps its whole history and an
    // administrator can restore it. Purging is not, and is therefore always a second, separate act
    // on something already withdrawn.
    //
    // These are @JavaScriptMethod only - no classic do* sibling, deliberately, breaking this
    // class's own pairing convention for two reasons. A do* method is GET-dispatchable unless it
    // carries @RequirePOST, and a GET-able delete is reachable by a browser prefetch, a link
    // scanner or a stale bookmark. And the collision trap documented on ConfigSetPage (a do* URL
    // segment equal to a @JavaScriptMethod name silently wins and nulls every argument) is avoided
    // outright rather than navigated. The tests call these methods directly in Java, which is the
    // real production path, so nothing is lost by the omission.

    /**
     * What withdrawing this Config Set would affect, without changing anything.
     *
     * <p>Runs before the confirmation prompt so that a refusal is explained up front rather than
     * after the operator has typed the name. Its answer is advisory: {@link #jsDeleteConfigSet}
     * re-scans, because a Job can start referencing the key in the seconds between the two calls
     * and a check whose result is carried over from an earlier request is not a check.</p>
     */
    @JavaScriptMethod(name = "deletePreflight")
    public JSONObject jsDeletePreflight() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JSONObject result = new JSONObject();
        if (!isExists()) {
            result.put("ok", false);
            result.put("errorCode", "NOT_FOUND");
            result.put("error", "No such Config Set: " + projectKey);
            return result;
        }
        return usageResult(ConfigSetUsageScanner.scan(projectKey), false);
    }

    /**
     * Withdraws this Config Set from service, keeping its entire history.
     *
     * @param payloadJson must carry {@code confirmName} matching this Config Set key exactly. The
     *        client checks it too, but that check is presentation: this endpoint is a real URL and
     *        is reachable without the page.
     */
    @JavaScriptMethod(name = "deleteConfigSet")
    public JSONObject jsDeleteConfigSet(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JSONObject nameCheck = requireConfirmedName(payloadJson);
        if (nameCheck != null) {
            return nameCheck;
        }
        if (!isExists()) {
            return simpleError("NOT_FOUND", isDeleted()
                    ? "This Config Set is already deleted."
                    : "No such Config Set: " + projectKey);
        }
        ConfigSetUsageScanner.Usage usage = ConfigSetUsageScanner.scan(projectKey);
        if (usage.blocksDelete()) {
            return usageResult(usage, true);
        }
        repository.softDeleteCommon(projectKey, currentAuthor(), System.currentTimeMillis());
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("projectKey", projectKey);
        return result;
    }

    /** Returns a withdrawn Config Set to service, exactly as it was. */
    @JavaScriptMethod(name = "restoreConfigSet")
    public JSONObject jsRestoreConfigSet() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (!isDeleted()) {
            return simpleError("NOT_DELETED", "This Config Set is not deleted.");
        }
        repository.restoreCommon(projectKey);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("projectKey", projectKey);
        return result;
    }

    /** What purging would destroy, and whether anything still references it. */
    @JavaScriptMethod(name = "purgePreflight")
    public JSONObject jsPurgePreflight() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (!isDeleted()) {
            return simpleError("NOT_DELETED", "Only a deleted Config Set can be purged.");
        }
        JSONObject result = usageResult(ConfigSetUsageScanner.scan(projectKey), false);
        result.put("blocked", ConfigSetUsageScanner.scan(projectKey).blocksPurge());
        result.put("versionCount", getVersions().size());
        return result;
    }

    /**
     * Destroys a withdrawn Config Set and its whole version history.
     *
     * <p>Refused while ANY Job version references it, not merely an active one: there is no restore
     * afterwards, so a rollback that would have worked must not be quietly made impossible.</p>
     */
    @JavaScriptMethod(name = "purgeConfigSet")
    public JSONObject jsPurgeConfigSet(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JSONObject nameCheck = requireConfirmedName(payloadJson);
        if (nameCheck != null) {
            return nameCheck;
        }
        if (!isDeleted()) {
            return simpleError("NOT_DELETED", "Only a deleted Config Set can be purged.");
        }
        ConfigSetUsageScanner.Usage usage = ConfigSetUsageScanner.scan(projectKey);
        if (usage.blocksPurge()) {
            return usageResult(usage, true);
        }
        repository.purgeCommon(projectKey);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("projectKey", projectKey);
        return result;
    }

    /** The {@code confirmName} field of a lifecycle payload, or {@code null} if absent. */
    private static String readConfirmName(String payloadJson) {
        com.google.gson.JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("confirmName")
                || !payload.get("confirmName").isJsonPrimitive()) {
            return null;
        }
        return payload.get("confirmName").getAsString();
    }

    /** {@code null} when the typed name matches; otherwise the refusal to return verbatim. */
    private JSONObject requireConfirmedName(String payloadJson) {
        String typed = readConfirmName(payloadJson);
        // Trimmed because a pasted name commonly carries whitespace, but otherwise exact and
        // case-sensitive: the storage key is a filename, so a name differing in case names a
        // different Config Set.
        if (typed == null || !typed.trim().equals(projectKey)) {
            return simpleError("NAME_MISMATCH",
                    "The typed name does not match " + projectKey + ". Nothing was changed.");
        }
        return null;
    }

    private JSONObject simpleError(String code, String message) {
        JSONObject result = new JSONObject();
        result.put("ok", false);
        result.put("errorCode", code);
        result.put("error", message);
        return result;
    }

    /** Renders a scan into the shape the dialog reads, optionally as a refusal. */
    private JSONObject usageResult(ConfigSetUsageScanner.Usage usage, boolean blocked) {
        JSONObject result = new JSONObject();
        result.put("ok", !blocked);
        if (blocked) {
            result.put("errorCode", "REFERENCED");
            result.put("error", "This Config Set is still in use.");
        }
        result.put("blocked", blocked);
        result.put("projectKey", projectKey);
        net.sf.json.JSONArray active = new net.sf.json.JSONArray();
        for (ConfigSetUsageScanner.JobUsage job : usage.getActiveJobs()) {
            active.add(jobUsageAsJson(job));
        }
        net.sf.json.JSONArray historical = new net.sf.json.JSONArray();
        for (ConfigSetUsageScanner.JobUsage job : usage.getHistoricalOnlyJobs()) {
            historical.add(jobUsageAsJson(job));
        }
        // Kept apart because they mean different things to the operator: one set of jobs breaks
        // now, the other only if somebody rolls back to an older version.
        result.put("activeJobs", active);
        result.put("historicalJobs", historical);
        return result;
    }

    private JSONObject jobUsageAsJson(ConfigSetUsageScanner.JobUsage job) {
        JSONObject entry = new JSONObject();
        entry.put("fullName", job.getFullName());
        entry.put("url", job.getUrl());
        net.sf.json.JSONArray versions = new net.sf.json.JSONArray();
        for (Integer versionNumber : job.getVersionNumbers()) {
            versions.add(versionNumber);
        }
        entry.put("versions", versions);
        return entry;
    }
}
