package io.jenkins.plugins.jobconfigchain.ui;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import hudson.model.Action;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.jobconfigchain.merge.BaseChainResolver;
import io.jenkins.plugins.jobconfigchain.merge.EffectiveConfigResolver;
import io.jenkins.plugins.jobconfigchain.merge.TemplateGenerator;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormat;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.JobConfigTemplateVersion;
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The job-scoped Config Templates admin page at {@code /job/&lt;name&gt;/configTemplates}
 * (tech-lead design contract, 2026-09-09). Replaces the old association-only flow
 * ({@code doSaveAssociation}/{@code doAssociateEnvironment}/the per-environment sub-page) wholesale
 * — this page now holds a {@link Job}'s OWN Config Templates content directly, via
 * {@link JobConfigTemplateProperty}, rather than pointing at a separate global {@code ConfigSet}.
 *
 * <p><b>Shared surface with EnvConfigSetPage:</b> both host classes expose the SAME set of
 * Jelly-visible getter names and {@code @JavaScriptMethod} proxy names (duck-typed via Jelly/JEXL,
 * not a Java interface), so the four {@code _shared/*.jelly} fragments
 * ({@code versionHistoryBlock}/{@code secretsManifestBlock}/{@code baseChainBlock}/
 * {@code editorBlock}) render identically against either host. See each method's javadoc for its
 * EnvConfigSetPage/{@link ConfigSetPage} counterpart. This class never extends
 * {@link ConfigSetPage} — a {@link Job} is not a {@code (projectKey, role, environment)}-keyed
 * {@link ConfigSet}, so there is no real inheritance relationship here, only a deliberately
 * parallel method surface.</p>
 *
 * <p><b>Deliberately simpler than EnvConfigSetPage:</b> no {@code explicitlyStandalone}
 * concept exists here at all (see {@link io.jenkins.plugins.jobconfigchain.model.JobConfigTemplateVersion}'s
 * javadoc for why) — {@link #isExplicitlyStandaloneSupported()} always returns {@code false}, and
 * an empty base chain unambiguously means "no bases," never a synthesized self-referencing default.</p>
 */
public class ConfigTemplatesJobAction implements Action {

    private final Job<?, ?> job;
    private final ConfigSetRepository repository = new ConfigSetRepository();

    public ConfigTemplatesJobAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    public String getIconFileName() {
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
        return "Config Templates";
    }

    @Override
    public String getUrlName() {
        return "configTemplates";
    }

    /**
     * This plugin's own Jenkins short-name, for the Monaco editor asset URLs in {@code index.jelly}
     * ({@code ${it.pluginShortName}} — see {@link PluginShortName} for why this is resolved here
     * instead of hardcoded).
     */
    public String getPluginShortName() {
        return PluginShortName.get();
    }

    /** The job this action was contributed to — read fresh, never cached. Exposed for the Jelly view. */
    public Job<?, ?> getJob() {
        return job;
    }

    /**
     * Resolved {@link SharedBlocks} class, bound into the Jelly view's {@code st:include class="${it.sharedBlocksClass}"}
     * attribute instead of a literal string — avoids Commons BeanUtils' String→Class conversion,
     * which in the live Jenkins-plugin runtime resolves against the core WebAppClassLoader rather
     * than this plugin's own PluginClassLoader and can never find a plugin-defined class this way.
     */
    public Class<SharedBlocks> getSharedBlocksClass() {
        return SharedBlocks.class;
    }

    /**
     * This job's current {@link JobConfigTemplateProperty}, or {@code null} if it has none. Always
     * re-reads {@code job.getProperty(...)} rather than returning a cached field, mirroring
     * {@link ConfigSetPage#getConfigSet()}'s own "never cache" contract.
     */
    public JobConfigTemplateProperty getProperty() {
        return job.getProperty(JobConfigTemplateProperty.class);
    }

    /** Mirrors {@link ConfigSetPage#isExists()}. */
    public boolean isExists() {
        return getProperty() != null;
    }

    /** Mirrors {@link ConfigSetPage#getVersions()}. */
    public List<JobConfigTemplateVersion> getVersions() {
        JobConfigTemplateProperty property = getProperty();
        return property == null ? List.of() : property.getVersions();
    }

    /** Mirrors {@link ConfigSetPage#getActiveVersion()}. */
    public JobConfigTemplateVersion getActiveVersion() {
        JobConfigTemplateProperty property = getProperty();
        return property == null ? null : property.getActiveVersion();
    }

    /** Mirrors {@link ConfigSetPage#getEditorSeedJson()}. */
    public String getEditorSeedJson() {
        JobConfigTemplateVersion active = getActiveVersion();
        return active == null ? "{}" : active.getContentJson();
    }

    /** Mirrors {@link ConfigSetPage#getEditorSeedJsonForScript()}. */
    public String getEditorSeedJsonForScript() {
        return ConfigSetPage.toJsScriptStringLiteral(getEditorSeedJson());
    }

    /** Mirrors {@link ConfigSetPage#getContentTypeValue()}. */
    public String getContentTypeValue() {
        JobConfigTemplateProperty property = getProperty();
        ContentType type = property == null ? null : property.getContentType();
        return type == null ? ContentType.JSON.name() : type.name();
    }

    /** Mirrors {@link ConfigSetPage#isContentTypeLocked()}. */
    public boolean isContentTypeLocked() {
        JobConfigTemplateProperty property = getProperty();
        return property != null && property.getContentType() != null;
    }

    /**
     * Always {@code false} — see this class's javadoc for why a job version has no
     * {@code explicitlyStandalone} concept at all. Read by the shared fragments to skip every bit
     * of standalone-specific markup that only makes sense for EnvConfigSetPage.
     */
    public boolean isExplicitlyStandaloneSupported() {
        return false;
    }

    /**
     * Bug 6.2 fix (tech-lead contract, 2026-09-10): identically-named counterpart to
     * EnvConfigSetPage#getOwnScopeLabel() (which returns {@code "layer"}) — this host's own
     * Config Templates content is scoped to a {@link Job}, not a layered-override "layer," so the
     * shared {@code baseChainBlock.jelly}/{@code editorBlock.jelly} fragments' body text now
     * interpolates {@code ${it.ownScopeLabel}} instead of hardcoding either word.
     */
    public String getOwnScopeLabel() {
        return "job";
    }

    /** Mirrors EnvConfigSetPage#getAvailableCommonProjectKeys(). */
    public List<String> getAvailableCommonProjectKeys() {
        List<String> keys = new ArrayList<>();
        for (ConfigSet common : repository.listAllCommon()) {
            keys.add(common.getProjectKey());
        }
        return keys;
    }

    /** Mirrors EnvConfigSetPage#getAvailableCommonProjectKeysJsonForScript(). */
    public String getAvailableCommonProjectKeysJsonForScript() {
        JsonArray array = new JsonArray();
        for (String key : getAvailableCommonProjectKeys()) {
            array.add(key);
        }
        return ConfigSetPage.toJsScriptStringLiteral(array.toString());
    }

    /**
     * Mirrors EnvConfigSetPage#getBaseChainSeedJsonForScript() — seeded from the current
     * ACTIVE version's declared chain, or an empty array for a brand-new job (no default row, see
     * base-chains.md — a job has no paired project of its own to default to).
     */
    public String getBaseChainSeedJsonForScript() {
        JobConfigTemplateVersion active = getActiveVersion();
        List<BaseConfigReference> chain = active == null ? Collections.emptyList() : active.getBaseChain();
        JsonArray array = new JsonArray();
        for (BaseConfigReference ref : chain) {
            JsonObject row = new JsonObject();
            row.addProperty("projectKey", ref.getProjectKey());
            row.addProperty("pinMode", ref.getPinMode().name());
            row.addProperty("pinnedVersionNumber", ref.getPinnedVersionNumber());
            array.add(row);
        }
        return ConfigSetPage.toJsScriptStringLiteral(array.toString());
    }

    /** Mirrors EnvConfigSetPage#getCommonVersionCatalogJsonForScript(). */
    public String getCommonVersionCatalogJsonForScript() {
        JsonObject catalog = new JsonObject();
        JsonObject typesByProject = new JsonObject();
        for (ConfigSet common : repository.listAllCommon()) {
            JsonArray versions = new JsonArray();
            for (ConfigSetVersion v : common.getVersions()) {
                JsonObject versionEntry = new JsonObject();
                versionEntry.addProperty("version", v.getVersionNumber());
                versionEntry.addProperty("note", v.getNote());
                versionEntry.addProperty("timestampEpochMillis", v.getTimestampEpochMillis());
                versions.add(versionEntry);
            }
            catalog.add(common.getProjectKey(), versions);
            typesByProject.addProperty(common.getProjectKey(), common.getContentType().name());
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("versionsByProject", catalog);
        wrapper.add("typeByProject", typesByProject);
        return ConfigSetPage.toJsScriptStringLiteral(wrapper.toString());
    }

    /** Mirrors {@link ConfigSetPage#getAvailableCredentialIds()}. */
    public List<String> getAvailableCredentialIds() {
        return CredentialsProvider
                .lookupCredentials(StandardCredentials.class,
                        Jenkins.get(), ACL.SYSTEM, Collections.emptyList())
                .stream()
                .map(StandardCredentials::getId)
                .collect(Collectors.toList());
    }

    /**
     * Mirrors {@code it.configSet.secretsManifest} on EnvConfigSetPage — a single,
     * identically-named getter so {@code secretsManifestBlock.jelly} never needs to reach through a
     * host-specific {@code configSet}/{@code property} accessor.
     */
    public Map<String, String> getSecretsManifestForDisplay() {
        JobConfigTemplateProperty property = getProperty();
        return property == null ? Collections.emptyMap() : property.getSecretsManifest();
    }

    // ---- Template generation gating ----

    /**
     * Jelly-visible: the "Generate Template" action always renders enabled on this page now (owner
     * requirement, 2026-09-12) — there is always SOME editor/base-chain draft state to generate
     * from, even before this job has ever saved a version, since {@link #renderTemplateImpl} now
     * reads the caller's CURRENT, possibly-unsaved override/base-chain draft (the same
     * client-supplied inputs {@link #doComputeMerge}/{@link #jsPreviewMerge} already accept) instead
     * of requiring {@link #getActiveVersion()}. Kept as a real method (not removed outright) so
     * {@code editorBlock.jelly} and this identically-named counterpart on EnvConfigSetPage
     * stay mirrored.
     */
    public boolean isTemplateAvailable() {
        return true;
    }

    // ---- Save (mirrors ConfigSetPage#doSubmitSave/#jsSave/#saveImpl) ----

    private static final class SaveOutcome {
        final boolean ok;
        final String error;
        final int newVersionNumber;

        private SaveOutcome(boolean ok, String error, int newVersionNumber) {
            this.ok = ok;
            this.error = error;
            this.newVersionNumber = newVersionNumber;
        }

        static SaveOutcome ok(int newVersionNumber) {
            return new SaveOutcome(true, null, newVersionNumber);
        }

        static SaveOutcome error(String message) {
            return new SaveOutcome(false, message, 0);
        }
    }

    private SaveOutcome saveImpl(String content, String note, boolean activate, String baseChainJson,
                                  String contentTypeParam) {
        try {
            JobConfigTemplateProperty property = getProperty();
            boolean isNew = property == null;
            ContentType resolvedContentType;
            if (isNew || property.getContentType() == null) {
                resolvedContentType = (contentTypeParam == null || contentTypeParam.trim().isEmpty())
                        ? ContentType.JSON : ContentType.valueOf(contentTypeParam);
            } else {
                resolvedContentType = property.getContentType();
            }

            List<BaseConfigReference> baseChain = ConfigSetPage.parseBaseChainOrFail(baseChainJson);
            checkChainTypeConsistencyOrFail(baseChain);
            ConfigSetPage.validateSyntaxOrFail(content, resolvedContentType);

            if (isNew) {
                property = new JobConfigTemplateProperty();
            }
            String author = currentAuthor();
            int newVersion;
            try {
                newVersion = property.addVersion(content, note, author, System.currentTimeMillis(),
                        baseChain, resolvedContentType.name());
            } catch (IllegalArgumentException e) {
                throw new Failure(Messages.ConfigSetPage_SaveBlocked(e.getMessage()));
            }
            if (activate) {
                property.activate(newVersion);
            }
            if (isNew) {
                job.addProperty(property);
            }
            job.save();
            return SaveOutcome.ok(newVersion);
        } catch (Failure f) {
            return SaveOutcome.error(f.getMessage());
        } catch (IOException e) {
            return SaveOutcome.error("Failed to save job: " + e.getMessage());
        }
    }

    /**
     * Cross-chain type-consistency check (see multi-format-content.md's "Cross-chain type
     * consistency" section), mirroring
     * {@link ConfigSetPage}'s private {@code checkChainTypeConsistencyOrFail} — a job's base chain
     * may reference any number of COMMON Config Sets, which must all share one content type. No
     * "resolve empty chain to a default self-reference" branch here: an empty chain on a job version
     * has nothing to check.
     */
    private void checkChainTypeConsistencyOrFail(List<BaseConfigReference> chain) {
        List<String> mismatchParts = new ArrayList<>();
        ContentType expected = null;
        boolean allMatch = true;
        for (BaseConfigReference ref : chain) {
            ConfigSet base = repository.findCommon(ref.getProjectKey());
            if (base == null) {
                continue;
            }
            if (expected == null) {
                expected = base.getContentType();
            } else if (base.getContentType() != expected) {
                allMatch = false;
            }
            mismatchParts.add(base.getProjectKey() + " (" + base.getContentType() + ")");
        }
        if (expected == null || allMatch) {
            return;
        }
        throw new Failure(Messages.ConfigSetPage_SaveBlockedMismatchedTypes(String.join(", ", mismatchParts)));
    }

    @RequirePOST
    public void doSubmitSave(StaplerResponse rsp,
                              @QueryParameter String content,
                              @QueryParameter String note,
                              @QueryParameter(fixEmpty = true) String activate,
                              @QueryParameter(fixEmpty = true) String baseChainJson,
                              @QueryParameter(fixEmpty = true) String contentType) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        SaveOutcome outcome = saveImpl(content, note, activate != null, baseChainJson, contentType);
        if (!outcome.ok) {
            throw new Failure(outcome.error);
        }
        rsp.sendRedirect2(".");
    }

    @JavaScriptMethod(name = "save")
    public JSONObject jsSave(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("content") || !payload.has("note")) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("error", "Malformed request: expected {\"content\": <string>, \"note\": <string>, "
                    + "\"activate\": <boolean>, \"baseChainJson\": <string|null>, \"contentType\": <string|null>}");
            return result;
        }
        String content = payload.get("content").getAsString();
        String note = payload.get("note").getAsString();
        boolean activate = payload.has("activate") && !payload.get("activate").isJsonNull()
                && payload.get("activate").getAsBoolean();
        String baseChainJson = (payload.has("baseChainJson") && !payload.get("baseChainJson").isJsonNull())
                ? payload.get("baseChainJson").getAsString() : null;
        String contentType = (payload.has("contentType") && !payload.get("contentType").isJsonNull())
                ? payload.get("contentType").getAsString() : null;

        SaveOutcome outcome = saveImpl(content, note, activate, baseChainJson, contentType);
        JSONObject result = new JSONObject();
        if (!outcome.ok) {
            result.put("ok", false);
            result.put("error", outcome.error);
            return result;
        }
        JobConfigTemplateProperty property = getProperty();
        result.put("ok", true);
        result.put("newVersionNumber", outcome.newVersionNumber);
        result.put("activeVersionNumber", property == null ? 0 : property.getActiveVersionNumber());
        result.put("contentTypeValue", getContentTypeValue());
        result.put("contentTypeLocked", isContentTypeLocked());
        result.put("versions", versionsAsJsonArray());
        return result;
    }

    private net.sf.json.JSONArray versionsAsJsonArray() {
        net.sf.json.JSONArray array = new net.sf.json.JSONArray();
        JobConfigTemplateProperty property = getProperty();
        int activeVersionNumber = property == null ? 0 : property.getActiveVersionNumber();
        for (JobConfigTemplateVersion v : getVersions()) {
            JSONObject row = new JSONObject();
            row.put("versionNumber", v.getVersionNumber());
            row.put("timestampEpochMillis", v.getTimestampEpochMillis());
            row.put("author", v.getAuthor());
            row.put("note", v.getNote());
            row.put("active", v.getVersionNumber() == activeVersionNumber);
            // Deliberately NO "explicitlyStandalone" field — see this class's javadoc.
            net.sf.json.JSONArray baseChain = new net.sf.json.JSONArray();
            for (BaseConfigReference ref : v.getBaseChain()) {
                JSONObject refRow = new JSONObject();
                refRow.put("projectKey", ref.getProjectKey());
                refRow.put("pinMode", ref.getPinMode().name());
                refRow.put("pinnedVersionNumber", ref.getPinnedVersionNumber());
                baseChain.add(refRow);
            }
            row.put("baseChain", baseChain);
            array.add(row);
        }
        return array;
    }

    private static net.sf.json.JSONArray secretsManifestAsJsonArray(JobConfigTemplateProperty property) {
        net.sf.json.JSONArray array = new net.sf.json.JSONArray();
        for (Map.Entry<String, String> entry : property.getSecretsManifest().entrySet()) {
            JSONObject row = new JSONObject();
            row.put("path", entry.getKey());
            row.put("credentialId", entry.getValue());
            array.add(row);
        }
        return array;
    }

    // ---- Activate (mirrors ConfigSetPage#doActivateVersion/#jsActivate) ----

    public JSONObject doActivateVersion(@QueryParameter int version) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        return activateImpl(version);
    }

    @JavaScriptMethod(name = "activate")
    public JSONObject jsActivate(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("version")) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("error", "Malformed request: expected {\"version\": <number>}");
            return result;
        }
        return activateImpl(payload.get("version").getAsInt());
    }

    private JSONObject activateImpl(int version) {
        JSONObject result = new JSONObject();
        JobConfigTemplateProperty property = getProperty();
        JobConfigTemplateVersion activated = property == null ? null : property.getVersion(version);
        if (activated == null) {
            result.put("ok", false);
            result.put("error", "No such version: " + version);
            return result;
        }
        property.activate(version);
        try {
            job.save();
        } catch (IOException e) {
            result.put("ok", false);
            result.put("error", "Failed to save job: " + e.getMessage());
            return result;
        }
        result.put("ok", true);
        result.put("active", version);
        result.put("versions", versionsAsJsonArray());
        result.put("activatedContent", activated.getContentJson());
        // Deliberately NO "activatedExplicitlyStandalone" — see this class's javadoc.
        net.sf.json.JSONArray activatedBaseChain = new net.sf.json.JSONArray();
        for (BaseConfigReference ref : activated.getBaseChain()) {
            JSONObject refRow = new JSONObject();
            refRow.put("projectKey", ref.getProjectKey());
            refRow.put("pinMode", ref.getPinMode().name());
            refRow.put("pinnedVersionNumber", ref.getPinnedVersionNumber());
            activatedBaseChain.add(refRow);
        }
        result.put("activatedBaseChain", activatedBaseChain);
        result.put("activatedContentType", property.getContentType().name());
        return result;
    }

    // ---- Secrets manifest (mirrors ConfigSetPage#doRegisterSecret/#doUnbindSecret) ----

    public JSONObject doRegisterSecret(@QueryParameter String path, @QueryParameter String credentialId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        return addSecretImpl(path, credentialId);
    }

    @JavaScriptMethod(name = "addSecret")
    public JSONObject jsAddSecret(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("path") || !payload.has("credentialId")) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("error", "Malformed request: expected {\"path\": <string>, \"credentialId\": <string>}");
            return result;
        }
        return addSecretImpl(payload.get("path").getAsString(), payload.get("credentialId").getAsString());
    }

    private JSONObject addSecretImpl(String path, String credentialId) {
        JSONObject result = new JSONObject();
        if (path == null || path.trim().isEmpty() || credentialId == null || credentialId.trim().isEmpty()) {
            result.put("ok", false);
            result.put("error", "Both a dotted path and a credential ID are required");
            return result;
        }
        if (!getAvailableCredentialIds().contains(credentialId)) {
            result.put("ok", false);
            result.put("error", "No such credential: " + credentialId
                    + " — pick one of the credentials currently registered in Jenkins");
            return result;
        }
        JobConfigTemplateProperty property = getProperty();
        boolean isNew = property == null;
        if (isNew) {
            property = new JobConfigTemplateProperty();
        } else {
            String realValueError = findRealValueAtPathInActiveVersion(property, path);
            if (realValueError != null) {
                result.put("ok", false);
                result.put("error", realValueError);
                return result;
            }
        }
        property.putSecretManifestEntry(path, credentialId);
        try {
            if (isNew) {
                job.addProperty(property);
            }
            job.save();
        } catch (IOException e) {
            result.put("ok", false);
            result.put("error", "Failed to save job: " + e.getMessage());
            return result;
        }
        result.put("ok", true);
        result.put("secretsManifest", secretsManifestAsJsonArray(property));
        return result;
    }

    public JSONObject doUnbindSecret(@QueryParameter String path) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        return removeSecretImpl(path);
    }

    @JavaScriptMethod(name = "removeSecret")
    public JSONObject jsRemoveSecret(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        job.checkPermission(Job.CONFIGURE);
        JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("path")) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("error", "Malformed request: expected {\"path\": <string>}");
            return result;
        }
        return removeSecretImpl(payload.get("path").getAsString());
    }

    private JSONObject removeSecretImpl(String path) {
        JSONObject result = new JSONObject();
        if (path == null || path.trim().isEmpty()) {
            result.put("ok", false);
            result.put("error", "A dotted path is required");
            return result;
        }
        JobConfigTemplateProperty property = getProperty();
        if (property == null || !property.getSecretsManifest().containsKey(path)) {
            result.put("ok", false);
            result.put("error", "No such secret path bound: " + path);
            return result;
        }
        property.removeSecretManifestEntry(path);
        try {
            job.save();
        } catch (IOException e) {
            result.put("ok", false);
            result.put("error", "Failed to save job: " + e.getMessage());
            return result;
        }
        result.put("ok", true);
        result.put("secretsManifest", secretsManifestAsJsonArray(property));
        return result;
    }

    private static String findRealValueAtPathInActiveVersion(JobConfigTemplateProperty property, String path) {
        JobConfigTemplateVersion active = property.getActiveVersion();
        if (active == null) {
            return null;
        }
        TreeNode parsed;
        try {
            parsed = TreeFormats.forType(property.getContentType()).parse(active.getContentJson());
        } catch (RuntimeException e) {
            return null;
        }
        if (!parsed.isObject()) {
            return null;
        }
        TreeNode leaf = TreePaths.get(parsed, path);
        if (leaf == null) {
            return null;
        }
        String asString = !leaf.isObject() && !leaf.isNull() ? leaf.leafAsString() : null;
        if (asString != null && asString.equals(SecretPlaceholder.VALUE)) {
            return null;
        }
        return "Cannot mark '" + path + "' as a secret path: the currently-active version (v"
                + active.getVersionNumber() + ") already stores a real value there. Replace that value "
                + "with the '" + SecretPlaceholder.VALUE
                + "' placeholder and save a new version first, then bind the credential.";
    }

    // ---- Compare (mirrors ConfigSetPage#doDiffVersions/#jsCompareVersions) ----

    public JSONObject doDiffVersions(@QueryParameter int version) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return compareVersionsImpl(version);
    }

    @JavaScriptMethod(name = "compareVersions")
    public JSONObject jsCompareVersions(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("version")) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("error", "Malformed request: expected {\"version\": <number>}");
            return result;
        }
        return compareVersionsImpl(payload.get("version").getAsInt());
    }

    private JSONObject compareVersionsImpl(int version) {
        JSONObject result = new JSONObject();
        JobConfigTemplateProperty property = getProperty();
        if (property == null) {
            result.put("ok", false);
            result.put("error", "No such Config Set");
            return result;
        }
        JobConfigTemplateVersion v = property.getVersion(version);
        if (v == null) {
            result.put("ok", false);
            result.put("error", "No such version: " + version);
            return result;
        }
        result.put("ok", true);
        result.put("version", version);
        result.put("content", v.getContentJson());
        return result;
    }

    /**
     * Immutable result of {@link #resolveChain} — the shared "parse the draft base-chain JSON,
     * resolve every entry against the repository, and settle on one {@link ContentType}" logic,
     * factored out so neither {@link #previewMergeImpl} nor {@link #renderTemplateImpl} (2026-09-12:
     * now itself a client-draft-driven computation, mirroring {@link #previewMergeImpl}) re-implements
     * chain resolution a second time. Mirrors EnvConfigSetPage's identically-shaped private
     * helper of the same name/purpose.
     */
    private static final class ChainResolution {
        final boolean ok;
        final String error;
        final ContentType type;
        final TreeFormat format;
        final List<BaseChainResolver.ResolvedReference> resolved;

        private ChainResolution(boolean ok, String error, ContentType type, TreeFormat format,
                                 List<BaseChainResolver.ResolvedReference> resolved) {
            this.ok = ok;
            this.error = error;
            this.type = type;
            this.format = format;
            this.resolved = resolved;
        }

        static ChainResolution error(String message) {
            return new ChainResolution(false, message, null, null, null);
        }

        static ChainResolution ok(ContentType type, TreeFormat format,
                                   List<BaseChainResolver.ResolvedReference> resolved) {
            return new ChainResolution(true, null, type, format, resolved);
        }
    }

    /**
     * SIMPLER than EnvConfigSetPage#resolveChain — an empty chain just resolves to
     * {@code {}}, no {@code explicitlyStandalone}/self-reference branch (there is nothing to
     * default an empty job chain to). {@code standaloneContentType} is still accepted, as the
     * pre-first-save empty-chain content-type picker's fallback, exactly like
     * EnvConfigSetPage's equivalent parameter.
     */
    private ChainResolution resolveChain(String baseChainJson, String standaloneContentType) {
        List<BaseConfigReference> chain = ConfigSetPage.tryParseBaseChain(baseChainJson);
        if (chain == null) {
            return ChainResolution.error("Malformed base chain");
        }
        // No backward-compatibility default substitution here (see base-chains.md) — an empty job
        // chain stays empty and resolves to {}.

        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, chain);
        Set<ContentType> distinctTypes = EnumSet.noneOf(ContentType.class);
        List<String> typeReport = new ArrayList<>();
        for (BaseChainResolver.ResolvedReference r : resolved) {
            if (r.configSet == null || r.version == null) {
                return ChainResolution.error("Cannot resolve base chain entry '" + r.reference.getProjectKey()
                        + "' (" + r.reference.getPinMode() + ") — "
                        + (r.configSet == null ? "no such common Config Set" : "no such version"));
            }
            distinctTypes.add(r.configSet.getContentType());
            typeReport.add(r.reference.getProjectKey() + " (" + r.configSet.getContentType() + ")");
        }
        if (distinctTypes.size() > 1) {
            return ChainResolution.error("Mismatched content types in base chain — " + String.join(", ", typeReport)
                    + " must all share one content type.");
        }
        ContentType type = !distinctTypes.isEmpty()
                ? distinctTypes.iterator().next()
                : parseContentTypeOrDefault(standaloneContentType, ContentType.JSON);
        return ChainResolution.ok(type, TreeFormats.forType(type), resolved);
    }

    // ---- Template generation ----

    /**
     * AJAX (Stapler JS-proxy): generates a copy-paste-ready template from the CALLER'S CURRENT,
     * possibly-unsaved override/base-chain draft — the exact same client-supplied inputs
     * {@link #doComputeMerge}/{@link #jsPreviewMerge} already accept (owner requirement, 2026-09-12:
     * "Generate Template" must work before this job has ever saved a version). Reuses
     * {@link #resolveChain} rather than a second, divergent chain-resolution implementation.
     */
    @jenkins.security.stapler.StaplerDispatchable
    public JSONObject doRenderTemplate(@QueryParameter String overlayJson, @QueryParameter String baseChainJson,
                                        @QueryParameter(fixEmpty = true) String standaloneContentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl(overlayJson, baseChainJson, standaloneContentType);
    }

    @JavaScriptMethod(name = "generateTemplate")
    public JSONObject jsGenerateTemplate(String overlayJson, String baseChainJson, String standaloneContentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl(overlayJson, baseChainJson, standaloneContentType);
    }

    private JSONObject renderTemplateImpl(String overlayRaw, String baseChainJson, String standaloneContentType) {
        JSONObject result = new JSONObject();

        ChainResolution cr = resolveChain(baseChainJson, standaloneContentType);
        if (!cr.ok) {
            result.put("ok", false);
            result.put("error", cr.error);
            return result;
        }
        ContentType type = cr.type;
        TreeFormat format = cr.format;

        TreeNode overlay;
        try {
            overlay = format.parse(overlayRaw == null ? format.serialize(format.emptyObject()) : overlayRaw);
            if (!overlay.isObject()) {
                result.put("ok", false);
                result.put("error", "Override content must be a " + type + " object/root element");
                return result;
            }
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }

        List<TreeNode> baseContents = new ArrayList<>();
        for (BaseChainResolver.ResolvedReference r : cr.resolved) {
            baseContents.add(format.parse(r.version.getContentJson()));
        }
        try {
            TreeNode effective = EffectiveConfigResolver.resolveChain(type, baseContents, overlayRaw);
            TreeNode template = TemplateGenerator.fromEffective(effective, type);
            result.put("ok", true);
            result.put("contentType", type.name());
            result.put("template", format.serialize(template));
            return result;
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    // ---- Live merge preview (mirrors EnvConfigSetPage#doComputeMerge/#jsPreviewMerge) ----

    public JSONObject doComputeMerge(@QueryParameter String overlayJson, @QueryParameter String baseChainJson,
                                      @QueryParameter(fixEmpty = true) String standaloneContentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return previewMergeImpl(overlayJson, baseChainJson, standaloneContentType);
    }

    @JavaScriptMethod(name = "previewMerge")
    public JSONObject jsPreviewMerge(String overlayJson, String baseChainJson, String standaloneContentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return previewMergeImpl(overlayJson, baseChainJson, standaloneContentType);
    }

    private JSONObject previewMergeImpl(String overlayRaw, String baseChainJson, String standaloneContentType) {
        JSONObject result = new JSONObject();

        ChainResolution cr = resolveChain(baseChainJson, standaloneContentType);
        if (!cr.ok) {
            result.put("ok", false);
            result.put("error", cr.error);
            return result;
        }
        ContentType type = cr.type;
        TreeFormat format = cr.format;
        List<BaseChainResolver.ResolvedReference> resolved = cr.resolved;

        TreeNode overlay;
        try {
            overlay = format.parse(overlayRaw == null ? format.serialize(format.emptyObject()) : overlayRaw);
            if (!overlay.isObject()) {
                result.put("ok", false);
                result.put("error", "Override content must be a " + type + " object/root element");
                return result;
            }
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }

        List<TreeNode> baseContents = new ArrayList<>();
        JsonArray perReference = new JsonArray();
        for (BaseChainResolver.ResolvedReference r : resolved) {
            TreeNode baseNode = format.parse(r.version.getContentJson());
            baseContents.add(baseNode);
            JsonObject entry = new JsonObject();
            entry.addProperty("projectKey", r.reference.getProjectKey());
            entry.addProperty("pinMode", r.reference.getPinMode().name());
            entry.addProperty("resolvedVersionNumber", r.version.getVersionNumber());
            entry.addProperty("contentJson", format.serialize(baseNode));
            // Same cumulative-fold field as EnvConfigSetPage's perReference entries — same
            // EffectiveConfigResolver.resolveChain call, never a second implementation.
            TreeNode cumulative = EffectiveConfigResolver.resolveChain(type, baseContents, null);
            entry.addProperty("cumulativeJson", format.serialize(cumulative));
            perReference.add(entry);
        }

        try {
            TreeNode mergedBasesOnly = EffectiveConfigResolver.resolveChain(type, baseContents, null);
            TreeNode merged = EffectiveConfigResolver.resolveChain(type, baseContents, overlayRaw);
            result.put("ok", true);
            result.put("contentType", type.name());
            result.put("mergedBases", format.serialize(mergedBasesOnly));
            result.put("merged", format.serialize(merged));
            result.put("perReference", net.sf.json.JSONArray.fromObject(perReference.toString()));
            return result;
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private static ContentType parseContentTypeOrDefault(String raw, ContentType fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return ContentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ---- Syntax check / reformat (reuses ConfigSetPage's shared static helpers directly) ----

    public JSONObject doCheckContentSyntax(@QueryParameter String content, @QueryParameter String contentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return validateContentImpl(content, ContentType.valueOf(contentType));
    }

    @JavaScriptMethod(name = "validateContent")
    public JSONObject jsValidateContent(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("content") || !payload.has("contentType")) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("error", "Malformed request: expected {\"content\": <string>, \"contentType\": <JSON|XML|YAML>}");
            return result;
        }
        return validateContentImpl(payload.get("content").getAsString(),
                ContentType.valueOf(payload.get("contentType").getAsString()));
    }

    /**
     * Reuses {@link ConfigSetPage#validateSyntaxOrFail(String, ContentType)} directly — the single
     * source of truth for "what counts as valid content" — rather than re-implementing a second,
     * independent parse+root-check.
     */
    private JSONObject validateContentImpl(String content, ContentType type) {
        JSONObject result = new JSONObject();
        try {
            ConfigSetPage.validateSyntaxOrFail(content, type);
            result.put("ok", true);
            return result;
        } catch (Failure f) {
            result.put("ok", false);
            result.put("error", f.getMessage());
            return result;
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    public JSONObject doReformatContent(@QueryParameter String content, @QueryParameter String contentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return formatContentImpl(content, ContentType.valueOf(contentType));
    }

    @JavaScriptMethod(name = "formatContent")
    public JSONObject jsFormatContent(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JsonObject payload = parseJsPayloadObject(payloadJson);
        if (payload == null || !payload.has("content") || !payload.has("contentType")) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("error", "Malformed request: expected {\"content\": <string>, \"contentType\": <JSON|XML|YAML>}");
            return result;
        }
        return formatContentImpl(payload.get("content").getAsString(),
                ContentType.valueOf(payload.get("contentType").getAsString()));
    }

    /**
     * Reuses {@link TreeFormats} directly — the same parse/serialize round trip
     * {@code ConfigSetPage}'s own (private) reformat helper performs; there is no separate,
     * divergent formatting rule anywhere in this codebase to accidentally duplicate.
     */
    private JSONObject formatContentImpl(String content, ContentType type) {
        JSONObject result = new JSONObject();
        try {
            TreeFormat format = TreeFormats.forType(type);
            TreeNode parsed = format.parse(content == null ? "" : content);
            result.put("ok", true);
            result.put("formatted", format.serialize(parsed));
            return result;
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private static JsonObject parseJsPayloadObject(String payloadJson) {
        if (payloadJson == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(payloadJson);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private static String currentAuthor() {
        User current = User.current();
        return current == null ? "anonymous" : current.getId();
    }
}
