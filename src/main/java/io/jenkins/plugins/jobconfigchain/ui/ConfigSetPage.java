package io.jenkins.plugins.jobconfigchain.ui;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import hudson.model.Failure;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormat;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.PinMode;
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared save/activate logic for the global (see admin-ui.md's "Global level" section) and env-level
 * (requirement history, see removal-candidates.md) admin
 * edit pages — both are the same {@link ConfigSet} Java type (see overview.md's entity model), distinguished only by
 * {@link ConfigSetRole}, so both pages share this one implementation rather than duplicating the
 * save/validate/activate contract.
 *
 * <p><b>Save contract (superseded 2026-09-02, owner-approved in-place-update pass):</b> the original
 * tech-lead decision (2026-08-27) was a classic Stapler structured form POST, redirecting back to
 * this same edit page on success. That is now replaced by the SAME AJAX/JS-proxy shape already used
 * by {@link #doActivateVersion(int)}/{@link #jsActivate} etc.: {@link #jsSave} updates the
 * version-history table (and, on a Common page's very first save, the content-type picker's
 * locked/unlocked display) in place, with zero page reload. {@link #doSubmitSave} is kept alongside
 * it purely as the classic, URL-addressable sibling — no Jelly {@code &lt;form&gt;} posts to it
 * anymore, but it remains this class's own {@code JenkinsRule} tests' way of exercising
 * {@link #saveImpl}'s validation/save logic directly, matching every other action pair on this
 * class. <b>Activate/addSecret/removeSecret contract:</b> already-AJAX {@code doXxx} endpoints
 * returning a JSON object; as of this same pass their JS callers also update the affected DOM
 * region in place (version-history table / secrets-manifest table) instead of the
 * {@code location.reload()} they previously (and needlessly) called after an already-successful
 * AJAX round trip.</p>
 *
 * <p><b>JSON syntax validation:</b> always re-checked here server-side — the Monaco editor's
 * client-side {@code jsonDefaults} diagnostics give live inline-marker feedback while typing, but a
 * save is never trusted on client-side validation alone. {@link #saveImpl} never itself throws
 * {@link Failure} out to a caller — it catches its own callees' {@link Failure}s once, internally,
 * and converts them into a structured {@code SaveOutcome}; {@link #doSubmitSave} re-throws that as a
 * {@link Failure} (Stapler renders it as a plain, clear error page — the classic path's only
 * consumer today is this class's own tests), while {@link #jsSave} surfaces it as an
 * {@code {ok:false, error:...}} response surfaced via {@code window.notificationBar} (Jenkins core's
 * native toast, replacing the earlier inline {@code #saveBanner} div, 2026-09-14) as a sticky error
 * notification (the client-side inline marker + toast is the primary UX for catching this before
 * submit; the server-side path here is the non-bypassable backstop).</p>
 */
// NOTE: this class MUST be public. Jelly/JEXL's bean-property reflection (${it.exists},
// ${it.versions}, etc.) invokes these public methods via plain java.lang.reflect.Method#invoke
// without setAccessible(true) — a public method whose *declaring* class is not itself public
// throws IllegalAccessException there and is silently treated as an absent/null property (unlike
// Stapler's own doXxx web-method dispatch, which does call setAccessible(true) and therefore
// worked fine even when this class was package-private — that asymmetry is exactly what made this
// bug look like a persistence problem during Milestone-2 UI test-writing, not a visibility one).
public abstract class ConfigSetPage {

    final String projectKey;
    final ConfigSetRepository repository;

    ConfigSetPage(String projectKey, ConfigSetRepository repository) {
        this.projectKey = projectKey;
        this.repository = repository;
    }

    abstract ConfigSetRole getRole();

    abstract String getEnvironment();

    abstract String getDisplayNameSeed();

    public String getProjectKey() {
        return projectKey;
    }

    /**
     * This plugin's own Jenkins short-name, for the Monaco editor asset URLs in {@code index.jelly}
     * ({@code ${it.pluginShortName}} — see {@link PluginShortName} for why this is resolved here
     * instead of hardcoded).
     */
    public String getPluginShortName() {
        return PluginShortName.get();
    }

    public ConfigSet getConfigSet() {
        return repository.find(projectKey, getRole(), getEnvironment());
    }

    public boolean isExists() {
        return getConfigSet() != null;
    }

    public List<io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion> getVersions() {
        ConfigSet cs = getConfigSet();
        return cs == null ? List.of() : cs.getVersions();
    }

    public io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion getActiveVersion() {
        ConfigSet cs = getConfigSet();
        return cs == null ? null : cs.getActiveVersion();
    }

    /**
     * The current secrets manifest, or an empty map for a Config Set that does not exist yet —
     * identically-named counterpart to {@link ConfigTemplatesJobAction#getSecretsManifestForDisplay()}
     * so {@code _shared/secretsManifestBlock.jelly} never needs to reach through a host-specific
     * {@code configSet}/{@code property} accessor to read it.
     */
    public Map<String, String> getSecretsManifestForDisplay() {
        ConfigSet cs = getConfigSet();
        return cs == null ? Collections.emptyMap() : cs.getSecretsManifest();
    }

    /** Empty-content seed for a brand-new Config Set's editor. */
    public String getEditorSeedJson() {
        io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion active = getActiveVersion();
        return active == null ? "{}" : active.getContentJson();
    }

    /**
     * Jelly-visible: the committed {@link ContentType} once a first version exists, or {@code JSON}
     * (the absent-picker default, see multi-format-content.md's "Content type model" section) before
     * then — drives the content-type picker's initial radio
     * selection AND the Monaco editor's initial {@code language}.
     */
    public String getContentTypeValue() {
        ConfigSet cs = getConfigSet();
        return cs == null ? ContentType.JSON.name() : cs.getContentType().name();
    }

    /**
     * Jelly-visible: whether the content-type picker should render as the disabled/read-only
     * "committed value + lock icon" display (see multi-format-content.md's immutability rule) —
     * true once a first version exists.
     */
    public final boolean isContentTypeLocked() {
        return getConfigSet() != null;
    }

    /**
     * Jelly-visible: which radio should render pre-selected in the still-unlocked
     * {@code #contentTypeRow} picker (see multi-format-content.md's "'Choose content type at
     * creation' moved earlier in the flow" section). Normally {@link #getContentTypeValue()}'s own
     * JSON default, but honors an
     * incoming {@code ?contentType=JSON|XML|YAML} query parameter carried over from the root list
     * page's "New Config Set" picker, when present and well-formed.
     *
     * <p>Deliberately never consulted once {@link #isExists()} is {@code true} — an existing
     * Config Set's committed, already-locked {@link ContentType} is authoritative and this method
     * simply defers to {@link #getContentTypeValue()} in that case, so an incoming query parameter
     * on a bookmarked/already-created project's URL is silently ignored rather than ever
     * influencing a locked type.</p>
     */
    public final String getPreselectedContentTypeValue() {
        if (isExists()) {
            return getContentTypeValue();
        }
        StaplerRequest req = Stapler.getCurrentRequest();
        String requested = req == null ? null : req.getParameter("contentType");
        if (requested != null) {
            try {
                return ContentType.valueOf(requested).name();
            } catch (IllegalArgumentException e) {
                // Unknown/malformed incoming value — fall back to the JSON default below
                // rather than surfacing an error for what is purely a smarter-default convenience.
            }
        }
        return ContentType.JSON.name();
    }

    /**
     * {@link #getEditorSeedJson()}, pre-encoded into a complete, ready-to-embed JS string literal
     * (quotes included) for the Monaco-editor seed {@code <script>} block. See
     * {@link #toJsScriptStringLiteral(String)} for why this must never be built via
     * {@code h.jsStringEscape} + hand-written surrounding quotes.
     */
    public final String getEditorSeedJsonForScript() {
        return toJsScriptStringLiteral(getEditorSeedJson());
    }

    /**
     * Encodes an arbitrary string as a complete JS string-literal expression (surrounding double
     * quotes included) that is safe to interpolate verbatim into an inline {@code <script>} block
     * via {@code <j:out value="${...}"/>} (bypassing Jelly's {@code escape-by-default} HTML/XML
     * escaping, which is the wrong escaping for a JS/script-text context and — for {@code &}/{@code
     * <}/{@code >} — would corrupt the content instead of protecting it, since browsers do not
     * HTML-entity-decode inline {@code <script>} body text).
     *
     * <p><b>Root-cause note:</b> {@code hudson.Functions#jsStringEscape} (the {@code h.jsStringEscape}
     * Jelly helper this replaces) only escapes {@code '}, {@code \}, and {@code "} — it does
     * <i>not</i> escape raw newlines/control characters. Pretty-printed JSON content (real {@code \n}
     * bytes) interpolated through it therefore produced a raw, unescaped line break inside a
     * single-line double-quoted JS string literal: an unterminated-string {@code SyntaxError} in
     * every real browser's JS engine (HtmlUnit's engine did not catch this in existing tests).</p>
     *
     * <p>Uses {@link Gson#toJson(Object)} on the raw {@link String} to produce a properly
     * JSON-escaped (hence JS-string-literal-safe) value — quotes, backslashes, and all control
     * characters including newlines are escaped — then additionally guards against the value
     * prematurely closing the surrounding {@code <script>} element by escaping any {@code </}
     * sequence (e.g. a stored JSON value literally containing {@code </script>}) to {@code <\/}.</p>
     */
    static String toJsScriptStringLiteral(String raw) {
        String jsonEncoded = new Gson().toJson(raw == null ? "" : raw);
        return jsonEncoded.replace("</", "<\\/");
    }

    /**
     * Immutable result of {@link #saveImpl}: never a thrown exception, so one implementation can
     * serve both the throwing classic path ({@link #doSubmitSave}) and the non-throwing AJAX path
     * ({@link #jsSave}).
     */
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

    /**
     * Shared save/validate/activate implementation behind both {@link #doSubmitSave} (classic,
     * re-throws as {@link Failure}) and {@link #jsSave} (AJAX, never throws) — see each caller's
     * javadoc for how the same outcome is surfaced differently. Every validation/parse failure this
     * method's callees signal via {@link Failure} (or {@link IllegalArgumentException}, wrapped the
     * same way {@code doSave} always did) is caught exactly once, here, and converted into a
     * {@link SaveOutcome#error}, so neither caller duplicates any of the actual save logic.
     */
    private SaveOutcome saveImpl(String content, String note, boolean activate, String baseChainJson,
                                  String contentTypeParam, boolean explicitlyStandalone) {
        try {
            ConfigSet configSet = getConfigSet();
            ContentType resolvedContentType;
            if (configSet == null) {
                // Only meaningful on first save (see multi-format-content.md's "Content type model"
                // section). Historically only ever supplied by the
                // COMMON page's client (the env page's type is normally always resolved from the base
                // chain, never chosen). An ENV-page
                // exception exists: when the env Config Set's first version is explicitlyStandalone with no
                // base chain to resolve a type from, the env client now also supplies this same field
                // from its own content-type picker — validated here identically to Common's. Default
                // to JSON when absent.
                resolvedContentType = (contentTypeParam == null || contentTypeParam.trim().isEmpty())
                        ? ContentType.JSON : ContentType.valueOf(contentTypeParam);
            } else {
                // Immutable after first version — the field simply has no effect from here on,
                // exactly mirroring projectKey's own existing immutability. Never re-read from the
                // request.
                resolvedContentType = configSet.getContentType();
            }

            List<BaseConfigReference> baseChain = parseBaseChainOrFail(baseChainJson);

            validateSyntaxOrFail(content, resolvedContentType);

            if (configSet == null) {
                configSet = new ConfigSet(projectKey, getRole(), getEnvironment(), getDisplayNameSeed(),
                        resolvedContentType);
            }
            String author = currentAuthor();
            int newVersion;
            try {
                newVersion = configSet.addVersion(content, note, author, System.currentTimeMillis(), baseChain,
                        explicitlyStandalone);
            } catch (IllegalArgumentException e) {
                // Every ConfigSet.addVersion validation failure — including the
                // "explicitlyStandalone on COMMON" and "explicitlyStandalone=true with
                // non-empty baseChain" checks (see base-chains.md) — surfaces through this SAME catch, no new branch needed.
                throw new Failure(Messages.ConfigSetPage_SaveBlocked(e.getMessage()));
            }
            if (activate) {
                configSet.activate(newVersion);
            }
            repository.save(configSet);
            return SaveOutcome.ok(newVersion);
        } catch (Failure f) {
            return SaveOutcome.error(f.getMessage());
        }
    }

    /**
     * Classic Stapler structured form POST — the URL-addressable, always-Failure-throwing sibling of
     * {@link #jsSave}. As of the 2026-09-02 in-place-update pass, no Jelly {@code &lt;form&gt;} on
     * either edit page posts here anymore (both pages' Save/Save &amp; Activate buttons call
     * {@link #jsSave} directly, since the rest of this page already requires JavaScript for Monaco
     * regardless — a no-JS fallback has no real audience here). This method is kept anyway, purely as
     * the same "classic sibling kept alongside its JS-proxy pair" shape as every other action on this
     * class (see {@link #doActivateVersion(int)}), and because it is what this class's own
     * {@code JenkinsRule} tests use to exercise {@link #saveImpl}'s validation/save logic directly,
     * matching how those tests already exercise {@code activateVersion}/{@code registerSecret}/etc.
     *
     * <p>Named {@code doSubmitSave} (URL segment {@code submitSave}), deliberately NOT {@code doSave},
     * to avoid colliding with the {@code @JavaScriptMethod(name = "save")} sibling below — see
     * {@link #doActivateVersion(int)}'s javadoc for the full root-cause chain behind why that
     * collision matters.</p>
     */
    @RequirePOST
    public void doSubmitSave(StaplerResponse rsp,
                              @QueryParameter String content,
                              @QueryParameter String note,
                              @QueryParameter(fixEmpty = true) String activate,
                              @QueryParameter(fixEmpty = true) String baseChainJson,
                              @QueryParameter(fixEmpty = true) String contentType,
                              @QueryParameter(fixEmpty = true) String explicitlyStandalone) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        SaveOutcome outcome = saveImpl(content, note, activate != null, baseChainJson, contentType,
                explicitlyStandalone != null);
        if (!outcome.ok) {
            throw new Failure(outcome.error);
        }
        rsp.sendRedirect2(".");
    }

    /**
     * JS-proxy-facing sibling of {@link #doSubmitSave}; exposed as {@code proxy.save(...)} — the
     * endpoint actually driving both pages' Save/Save &amp; Activate buttons since the 2026-09-02
     * in-place-update pass. On success, updates the version-history table (via the returned
     * {@code versions} array — see {@link #versionsAsJsonArray()}) and, on a Common page's very first
     * save, the content-type picker's locked/unlocked display (via {@code contentTypeLocked}/
     * {@code contentTypeValue}) — all without a page reload. See {@link #doActivateVersion(int)}
     * javadoc for the naming-collision root cause this pair avoids, and {@link #saveImpl} for the
     * shared, never-throwing validation/save logic both siblings delegate to.
     */
    @JavaScriptMethod(name = "save")
    public JSONObject jsSave(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
        boolean explicitlyStandalone = payload.has("explicitlyStandalone")
                && !payload.get("explicitlyStandalone").isJsonNull()
                && payload.get("explicitlyStandalone").getAsBoolean();

        SaveOutcome outcome = saveImpl(content, note, activate, baseChainJson, contentType, explicitlyStandalone);
        JSONObject result = new JSONObject();
        if (!outcome.ok) {
            result.put("ok", false);
            result.put("error", outcome.error);
            return result;
        }
        result.put("ok", true);
        result.put("newVersionNumber", outcome.newVersionNumber);
        result.put("activeVersionNumber", getConfigSet() == null ? 0 : getConfigSet().getActiveVersionNumber());
        result.put("contentTypeValue", getContentTypeValue());
        result.put("contentTypeLocked", isContentTypeLocked());
        result.put("versions", versionsAsJsonArray());
        return result;
    }

    /**
     * The current version-history list, JSON-serialized for the client's in-place table re-render —
     * shared by {@link #jsSave} (may append a new version) and {@link #activateImpl} (flips which
     * version is active) so neither caller needs a page reload to reflect its effect on the
     * version-history table.
     */
    private net.sf.json.JSONArray versionsAsJsonArray() {
        net.sf.json.JSONArray array = new net.sf.json.JSONArray();
        ConfigSet configSet = getConfigSet();
        int activeVersionNumber = configSet == null ? 0 : configSet.getActiveVersionNumber();
        for (ConfigSetVersion v : getVersions()) {
            JSONObject row = new JSONObject();
            row.put("versionNumber", v.getVersionNumber());
            row.put("timestampEpochMillis", v.getTimestampEpochMillis());
            row.put("author", v.getAuthor());
            row.put("note", v.getNote());
            row.put("active", v.getVersionNumber() == activeVersionNumber);
            row.put("explicitlyStandalone", v.isExplicitlyStandalone());
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

    /**
     * The current secrets-manifest, JSON-serialized for the client's in-place table re-render —
     * shared by {@link #addSecretImpl} and {@link #removeSecretImpl} so neither caller needs a page
     * reload to reflect its effect on the secrets-manifest table.
     */
    private static net.sf.json.JSONArray secretsManifestAsJsonArray(ConfigSet configSet) {
        net.sf.json.JSONArray array = new net.sf.json.JSONArray();
        for (Map.Entry<String, String> entry : configSet.getSecretsManifest().entrySet()) {
            JSONObject row = new JSONObject();
            row.put("path", entry.getKey());
            row.put("credentialId", entry.getValue());
            array.add(row);
        }
        return array;
    }

    /**
     * See multi-format-content.md's "Cross-chain type consistency" section: resolves every referenced COMMON Config Set in {@code chain} and compares its
     * {@link ContentType} against every other member. Fail-loud, naming every conflicting project +
     * type, per the wireframe's exact message shape: {@code Save blocked: mismatched content types in
     * base chain — <project> (<TYPE>), <project> (<TYPE>) must all share one content type.}
     */
    private void checkChainTypeConsistencyOrFail(List<BaseConfigReference> chain) {
        List<String> mismatchParts = new ArrayList<>();
        ContentType expected = null;
        boolean allMatch = true;
        for (BaseConfigReference ref : chain) {
            ConfigSet base = repository.findCommon(ref.getProjectKey());
            if (base == null) {
                // Not this method's concern — BaseChainResolver-driven resolution failures (missing
                // project/version) are reported by the existing preview/save flow separately; this
                // check only compares types across chain members that DO resolve.
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

    /**
     * Parses the base-chain editor's draft JSON array (see base-chains.md) into
     * {@link BaseConfigReference}s, or returns an empty list if the field is absent/blank — the
     * global (common) page's Jelly never emits {@code baseChainJson} at all, so this is a
     * no-behavior-change path there. Throws {@link Failure} (never a raw {@link RuntimeException})
     * on malformed input, matching this page's existing save-time validation convention.
     *
     * <p><b>Defensive {@code pinMode} default (2026-09-02, secondary-bug investigation):</b> the
     * base-chain editor's own row-creation JS ({@code addBaseChainRow} in
     * {@code EnvConfigSetPage/index.jelly}) already unconditionally seeds every newly-added row with
     * {@code pinMode: 'ACTIVE'} before it is ever serialized — confirmed by direct code inspection,
     * so a row added via the "Add" button and never touched again already round-trips correctly and
     * this path was NOT reproducible through the normal UI flow. A missing/blank {@code pinMode} is
     * still defended here regardless (rather than left to throw a raw {@link NullPointerException}
     * from {@link PinMode#valueOf}), matching the same ACTIVE default the client already applies, so
     * any other caller of this shared parse path (e.g. a hand-crafted request, or a future UI change
     * that stops setting the field) degrades to the same sensible default instead of a confusing
     * server error.</p>
     */
    static List<BaseConfigReference> parseBaseChainOrFail(String baseChainJson) {
        if (baseChainJson == null || baseChainJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JsonArray array = JsonParser.parseString(baseChainJson).getAsJsonArray();
            List<BaseConfigReference> result = new ArrayList<>();
            for (JsonElement el : array) {
                JsonObject row = el.getAsJsonObject();
                String rowProjectKey = row.get("projectKey").getAsString();
                PinMode mode = (row.has("pinMode") && !row.get("pinMode").isJsonNull())
                        ? PinMode.valueOf(row.get("pinMode").getAsString())
                        : PinMode.ACTIVE;
                int pinnedVersion = row.has("pinnedVersionNumber") ? row.get("pinnedVersionNumber").getAsInt() : 0;
                result.add(new BaseConfigReference(rowProjectKey, mode, pinnedVersion));
            }
            return result;
        } catch (RuntimeException e) {
            // JsonSyntaxException, IllegalStateException, NullPointerException, IllegalArgumentException.
            throw new Failure(Messages.ConfigSetPage_SaveBlockedMalformedBaseChain(e.getMessage()));
        }
    }

    /**
     * Non-throwing sibling of {@link #parseBaseChainOrFail} for endpoints that must never throw and
     * instead report a structured {@code ok:false} (e.g. EnvConfigSetPage's live merge
     * preview) — reuses the single parsing implementation above rather than duplicating the
     * JSON-array-walk logic, catching the {@link Failure} it throws on malformed input.
     *
     * @return the parsed chain, or {@code null} if {@code baseChainJson} was malformed.
     */
    static List<BaseConfigReference> tryParseBaseChain(String baseChainJson) {
        try {
            return parseBaseChainOrFail(baseChainJson);
        } catch (Failure e) {
            return null;
        }
    }

    /**
     * AJAX (Stapler JS-proxy): flips the active pointer without a page reload — a pure metadata flip,
     * never triggering a live deploy (see user-flows.md's operator rollback flows).
     *
     * <p><b>Full root-cause chain (2026-08-30/31 QA passes), confirmed via a real-browser
     * Playwright session with captured network traffic, not just reading Stapler source:</b></p>
     * <ol>
     * <li><b>Missing {@code @JavaScriptMethod}.</b> A {@code doXxx} method is reachable via classic
     * Stapler URL dispatch (hence curl-testing it "worked") without any special annotation, but the
     * {@code <st:bind var="proxy" value="${it}"/>} JS-proxy stub is built by a <i>separate</i>
     * dispatcher ({@code org.kohsuke.stapler.MetaClass#buildDispatchers}, which only iterates
     * {@code node.methods.annotated(JavaScriptMethod.class)}). Without the annotation,
     * {@code proxy.activate} etc. are simply absent from the generated proxy object, hence
     * {@code "proxy.activate is not a function"} despite the server route working fine.</li>
     * <li><b>{@code do}-prefix name leak.</b> With no {@code name()} override, {@code
     * @JavaScriptMethod} exposes the function under the annotated method's literal Java name
     * ({@code proxy.doActivate}), not the shorter name ({@code proxy.activate}) the existing
     * {@code index.jelly} JS was written against — fixed via {@code name = "activate"}.</li>
     * <li><b>{@code @QueryParameter} is incompatible with the {@code @JavaScriptMethod} JS-proxy
     * dispatcher's argument binding</b> — keeping it makes classic {@code doXxx} URL dispatch work
     * (curl / {@code JenkinsRule} tests) but the JS-proxy call binds every argument to its type's
     * zero-value/{@code null}; removing it instead 500s the classic dispatch (Stapler requires an
     * explicit binding annotation for query-string parameters). The two dispatch shapes are
     * therefore split into a classic method and a JS-proxy method, sharing one private
     * implementation.</li>
     * <li><b>THE decisive root cause — a URL-segment naming collision, not a binding-mechanism bug
     * at all:</b> the classic {@code do}-prefix convention maps a method literally named
     * {@code doActivate} to URL segment {@code activate} — the EXACT SAME segment the
     * {@code @JavaScriptMethod(name = "activate")}-annotated sibling is exposed under. Both
     * dispatchers therefore compete for the identical {@code .../activate} URL on the same bound
     * object, and the classic {@code do}-prefix dispatcher wins, silently intercepting the
     * JS-proxy's POST-with-JSON-body request, binding its missing/absent query parameters to
     * defaults ({@code 0}/{@code null}), and returning a plausible-looking but WRONG response — the
     * JS-proxy method is never even invoked. Confirmed by adding a temporary diagnostic that echoed
     * the JS-proxy method's actual received parameter and observing the response never contained it
     * — the classic method's own error text came back instead. This is also why
     * {@code EnvConfigSetPage}'s {@code previewMerge} looked like it "worked" in earlier manual
     * checks: the classic {@code doPreviewMerge(@QueryParameter String overlayJson)} was silently
     * absorbing the call with {@code overlayJson=null}, defaulting to {@code "{}"}, and returning a
     * plausible {@code ok:true} merge of the common config alone — never actually reflecting the
     * live override content the browser sent. <b>Fix:</b> the classic {@code doXxx} methods are
     * renamed so their {@code do}-prefix-derived URL segment no longer collides with any
     * {@code @JavaScriptMethod} name on the same object ({@code doActivate} →
     * {@link #doActivateVersion(int)}, URL {@code activateVersion}; see the sibling methods below
     * for the equivalent renames, and EnvConfigSetPage#doComputeMerge(String) for
     * {@code previewMerge}'s).</li>
     * </ol>
     */
    public JSONObject doActivateVersion(@QueryParameter int version) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return activateImpl(version);
    }

    /**
     * JS-proxy-facing sibling of {@link #doActivateVersion(int)} — see that method's javadoc for the
     * full root-cause chain. Exposed as {@code proxy.activate(...)}; takes a single JSON-string
     * payload ({@code {"version": N}}) rather than a plain {@code int} parameter, matching the one
     * argument shape (EnvConfigSetPage#jsPreviewMerge) proven to bind reliably through this
     * Jenkins/Stapler version's JS-proxy dispatcher.
     */
    @JavaScriptMethod(name = "activate")
    public JSONObject jsActivate(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
        ConfigSet configSet = getConfigSet();
        ConfigSetVersion activated = configSet == null ? null : configSet.getVersion(version);
        if (activated == null) {
            result.put("ok", false);
            result.put("error", "No such version: " + version);
            return result;
        }
        configSet.activate(version);
        repository.save(configSet);
        result.put("ok", true);
        result.put("active", version);
        // In-place update (2026-09-02): the client re-renders the version-history table's
        // Active-badge/button-disabled-state from this instead of location.reload()-ing the whole
        // page — see #versionsAsJsonArray's javadoc.
        result.put("versions", versionsAsJsonArray());
        // Real bug fix (2026-09-04, live manual review of EnvConfigSetPage): activating a version
        // DIFFERENT from whatever a caller's editor currently has loaded must not leave that editor
        // showing a stale draft that no longer represents the newly-active version's real content —
        // EnvConfigSetPage's client reloads its base-chain editor rows/explicitlyStandalone
        // flag/Env-override content from these fields after a successful activate (see that page's
        // own reloadEditorStateFromActivatedVersion JS function). Generic on this shared base class
        // (not env-specific) since every ConfigSetVersion carries this same shape regardless of
        // role; these are harmless additional fields for CommonConfigSetPage's own activateVersion
        // JS, which does not read them.
        result.put("activatedContent", activated.getContentJson());
        result.put("activatedExplicitlyStandalone", activated.isExplicitlyStandalone());
        net.sf.json.JSONArray activatedBaseChain = new net.sf.json.JSONArray();
        for (BaseConfigReference ref : activated.getBaseChain()) {
            JSONObject refRow = new JSONObject();
            refRow.put("projectKey", ref.getProjectKey());
            refRow.put("pinMode", ref.getPinMode().name());
            refRow.put("pinnedVersionNumber", ref.getPinnedVersionNumber());
            activatedBaseChain.add(refRow);
        }
        result.put("activatedBaseChain", activatedBaseChain);
        result.put("activatedContentType", configSet.getContentType().name());
        return result;
    }

    /**
     * AJAX: registers (or re-points) a secrets-manifest entry for this Config Set — a dotted path
     * bound to a Jenkins credential ID (see config-sets-and-versioning.md's "Secrets" section). This
     * is the ONLY way a secret path's real
     * value is ever referenced; the picker itself is a credential-ID select, never free text, so it
     * structurally cannot hold a real secret value (structural prevention, see
     * config-sets-and-versioning.md).
     *
     * <p>Named {@code doRegisterSecret} (URL segment {@code registerSecret}), deliberately NOT
     * {@code doAddSecret}, to avoid colliding with the {@code @JavaScriptMethod(name = "addSecret")}
     * sibling below — see {@link #doActivateVersion(int)}'s javadoc for the full root-cause chain
     * behind why that collision matters and why the JS-facing sibling takes a single JSON string.</p>
     */
    public JSONObject doRegisterSecret(@QueryParameter String path, @QueryParameter String credentialId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return addSecretImpl(path, credentialId);
    }

    /**
     * JS-proxy-facing sibling of {@link #doRegisterSecret}; exposed as {@code proxy.addSecret(...)}.
     * See {@link #doActivateVersion(int)} javadoc.
     */
    @JavaScriptMethod(name = "addSecret")
    public JSONObject jsAddSecret(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
        // The picker only ever offers real, currently-existing credential IDs, but
        // this endpoint is also reachable directly (e.g. a hand-crafted request bypassing the
        // rendered <select>), so the existence check is re-asserted server-side rather than trusted
        // from the client. A stale/typo'd credential ID must never be stored — it would silently
        // break the actual secret-injection pipeline step later, far from where the mistake was made.
        if (!getAvailableCredentialIds().contains(credentialId)) {
            result.put("ok", false);
            result.put("error", "No such credential: " + credentialId
                    + " — pick one of the credentials currently registered in Jenkins");
            return result;
        }
        ConfigSet configSet = getConfigSet();
        if (configSet != null) {
            // Retroactive-manifest-change gap: declaring an EXISTING path secret for the first
            // time must not silently leave a real plain-text value sitting in the already-saved active
            // version's storage. Reject here rather than scrubbing it in the background — the admin
            // must explicitly replace the value with the placeholder and save a new version first.
            String realValueError = findRealValueAtPathInActiveVersion(configSet, path);
            if (realValueError != null) {
                result.put("ok", false);
                result.put("error", realValueError);
                return result;
            }
        } else {
            // This fallback path never had a content-type picker's input to read (secrets are
            // bound before any content is ever saved) — default to JSON, exactly doSave's own
            // absent-picker default; a subsequent doSave still governs the Config Set's real,
            // committed contentType once the first version is actually saved.
            configSet = new ConfigSet(projectKey, getRole(), getEnvironment(), getDisplayNameSeed(), ContentType.JSON);
        }
        configSet.putSecretManifestEntry(path, credentialId);
        repository.save(configSet);
        result.put("ok", true);
        // In-place update (2026-09-02): the client re-renders just the secrets-manifest table body
        // from this instead of location.reload()-ing the whole page.
        result.put("secretsManifest", secretsManifestAsJsonArray(configSet));
        return result;
    }

    /**
     * AJAX: removes (unbinds) a secrets-manifest entry — the CRUD-completion counterpart to
     * {@link #doRegisterSecret} (2026-09-01). A pure manifest-metadata mutation,
     * same persistence shape as add: mutate {@link ConfigSet#removeSecretManifestEntry(String)} in
     * place, then {@code repository.save(configSet)} — no new {@link ConfigSetVersion} is created,
     * exactly mirroring how {@link #addSecretImpl} persists a put (see that method's/{@link
     * ConfigSet#removeSecretManifestEntry}'s javadoc for why the manifest is not itself versioned).
     *
     * <p>Named {@code doUnbindSecret} (URL segment {@code unbindSecret}), deliberately NOT
     * {@code doRemoveSecret}, to avoid colliding with the
     * {@code @JavaScriptMethod(name = "removeSecret")} sibling below — see
     * {@link #doActivateVersion(int)}'s javadoc for the full root-cause chain behind why that
     * collision matters (same reasoning that named the add pair {@code doRegisterSecret}/
     * {@code addSecret} rather than {@code doAddSecret}/{@code addSecret}).</p>
     */
    public JSONObject doUnbindSecret(@QueryParameter String path) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return removeSecretImpl(path);
    }

    /**
     * JS-proxy-facing sibling of {@link #doUnbindSecret}; exposed as {@code proxy.removeSecret(...)}.
     * See {@link #doActivateVersion(int)} javadoc.
     */
    @JavaScriptMethod(name = "removeSecret")
    public JSONObject jsRemoveSecret(String payloadJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
        ConfigSet configSet = getConfigSet();
        if (configSet == null || !configSet.getSecretsManifest().containsKey(path)) {
            result.put("ok", false);
            result.put("error", "No such secret path bound: " + path);
            return result;
        }
        configSet.removeSecretManifestEntry(path);
        repository.save(configSet);
        result.put("ok", true);
        // In-place update (2026-09-02): see #addSecretImpl's equivalent note above.
        result.put("secretsManifest", secretsManifestAsJsonArray(configSet));
        return result;
    }

    /**
     * Returns a clear rejection message if the Config Set's currently-active version already stores
     * a real (non-placeholder) value at {@code path}, or {@code null} if it is safe to newly declare
     * that path secret (path absent from the active content, or already holding the placeholder).
     */
    private static String findRealValueAtPathInActiveVersion(ConfigSet configSet, String path) {
        ConfigSetVersion active = configSet.getActiveVersion();
        if (active == null) {
            return null;
        }
        TreeNode parsed;
        try {
            parsed = TreeFormats.forType(configSet.getContentType()).parse(active.getContentJson());
        } catch (RuntimeException e) {
            // an already-stored version is never expected to be invalid for its own declared type, but
            // if it somehow is, there is nothing structured to inspect here — do not block the
            // manifest update on that.
            return null;
        }
        if (!parsed.isObject()) {
            return null;
        }
        TreeNode leaf = TreePaths.get(parsed, path);
        if (leaf == null) {
            // absent leaf: nothing stored at this path yet, nothing to reject.
            return null;
        }
        String asString = !leaf.isObject() && !leaf.isNull() ? leaf.leafAsString() : null;
        if (asString != null && asString.equals(SecretPlaceholder.VALUE)) {
            return null;
        }
        return Messages.SecretPath_RejectedActiveValueHoldsRealValue(
                path, active.getVersionNumber(), SecretPlaceholder.VALUE);
    }

    /**
     * AJAX: fetches ONE historical version's raw {@code contentJson} so the client can diff it
     * against the current (unsaved) editor draft in {@code monaco.editor.createDiffEditor}, as the
     * "Compare" mode of the SAME editor region (wireframe `docs/design/wireframe-layout.md` —
     * Compare is a mode toggle, never a second editor). Never throws on a bad/missing version
     * number; returns a structured error instead so the client can surface it without a
     * page-level failure.
     *
     * <p><b>Interaction redesign (2026-09-01, owner report):</b> the prior checkbox-based
     * "select exactly two versions, then click Compare" flow was replaced with a simpler
     * click-to-compare interaction — there is no checkbox at all; clicking a version row directly
     * opens a diff of {@code draftBody} (the live, still-unsaved editor content) against that ONE
     * clicked version. This endpoint's signature therefore dropped from two explicit version
     * numbers to one — the "other side" of the diff is the client's own already-in-memory draft
     * buffer, which never needs a round-trip to the server at all.</p>
     *
     * <p>Named {@code doDiffVersions} (URL segment {@code diffVersions}), deliberately NOT
     * {@code doCompareVersions}, to avoid colliding with the
     * {@code @JavaScriptMethod(name = "compareVersions")} sibling below — see
     * {@link #doActivateVersion(int)}'s javadoc for the full root-cause chain.</p>
     */
    public JSONObject doDiffVersions(@QueryParameter int version) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return compareVersionsImpl(version);
    }

    /**
     * JS-proxy-facing sibling of {@link #doDiffVersions}; exposed as {@code proxy.compareVersions(...)}.
     * See {@link #doActivateVersion(int)} javadoc and {@link #doDiffVersions(int)}'s 2026-09-01
     * interaction-redesign note for why this only takes one version number now.
     */
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

    /**
     * Parses a JS-proxy call's single JSON-string payload argument into a {@link JsonObject}, or
     * {@code null} if it is missing/malformed — never throws, so callers can surface a clean
     * structured {@code ok:false} error instead of a 500.
     */
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

    private JSONObject compareVersionsImpl(int version) {
        JSONObject result = new JSONObject();
        ConfigSet configSet = getConfigSet();
        if (configSet == null) {
            result.put("ok", false);
            result.put("error", "No such Config Set");
            return result;
        }
        ConfigSetVersion v = configSet.getVersion(version);
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
     * Template-generation ({@code doRenderTemplate}/{@code jsGenerateTemplate}/
     * {@code isTemplateAvailable}) is DELIBERATELY NOT shared on this base class anymore (owner
     * requirement, 2026-09-12): {@link CommonConfigSetPage} keeps the original template-generation
     * "ACTIVE-version(s)-only, never a draft" contract (its own copy, unchanged behavior — see
     * pipeline-steps.md's "Template generation" section), while
     * EnvConfigSetPage now generates from the CALLER'S current, possibly-unsaved
     * override/base-chain draft — the same client-supplied inputs EnvConfigSetPage#doComputeMerge
     * already accepts — so the "Generate Template" button can render unconditionally enabled even
     * before any version has ever been saved. The two scopes' template-generation contracts are now
     * genuinely different (one draft-aware, one not), so forcing them through one shared
     * zero-argument method signature is no longer possible; each subclass owns its own
     * doRenderTemplate/jsGenerateTemplate/isTemplateAvailable trio instead, mirroring how
     * {@code getCommonConfigSet}/{@code previewMerge} already live only on EnvConfigSetPage.
     */

    /**
     * Credential IDs available for the secrets-manifest picker: a real, existing Jenkins
     * credential, resolved from the standard credential store — structurally never a place a real
     * secret VALUE could be typed, only a reference by ID (see config-sets-and-versioning.md's
     * "Secrets" section and nfr.md's "No real secret values at rest, ever" rule). Scoped to the whole
     * controller (system-level credentials) since this admin screen is itself admin-only.
     */
    public List<String> getAvailableCredentialIds() {
        return CredentialsProvider
                .lookupCredentials(StandardCredentials.class, Jenkins.get(), ACL.SYSTEM, Collections.emptyList())
                .stream()
                .map(c -> c.getId())
                .collect(Collectors.toList());
    }

    static void validateSyntaxOrFail(String content, ContentType type) {
        try {
            TreeNode parsed = TreeFormats.forType(type).parse(content == null ? "" : content);
            if (!parsed.isObject()) {
                throw new Failure(Messages.ConfigSetPage_SaveBlockedInvalidRoot(type));
            }
        } catch (RuntimeException e) {
            // JsonSyntaxException / XmlSyntaxException / YAMLException — one catch clause covers all
            // three, since every TreeFormat#parse implementation throws an unchecked RuntimeException
            // rather than three format-specific catch branches.
            if (e instanceof Failure) {
                throw (Failure) e;
            }
            throw new Failure(Messages.ConfigSetPage_SaveBlockedInvalidSyntax(type, e.getMessage()));
        }
    }

    /**
     * AJAX: syntax-validates arbitrary content against a declared {@link ContentType} — the YAML
     * "checking…" debounced round trip's server side (see multi-format-content.md's "Editor parity"
     * section), and reused for XML's own diagnostics
     * call (client-side {@code DOMParser} handles XML synchronously in-browser, but this endpoint is
     * format-agnostic so it works for any of the three without a second implementation).
     *
     * <p><b>Root-cause note (2026-09-02, real-browser Playwright regression):</b> this method was
     * originally named {@code doValidateContent} (URL segment {@code validateContent}) — which,
     * despite an earlier enumeration claiming otherwise, collides EXACTLY with the
     * {@code @JavaScriptMethod(name = "validateContent")} sibling below, the precise trap described
     * in {@link #doActivateVersion(int)}'s javadoc (a classic {@code do}-prefix method's derived URL
     * segment must never equal a {@code @JavaScriptMethod}'s bound {@code name}). The classic
     * dispatcher won the collision, silently absorbing every JS-proxy call with a {@code null}
     * {@code contentType} query parameter and throwing {@code NullPointerException: Name is null}
     * from {@link ContentType#valueOf(String)} — confirmed via a real-browser reproduction where the
     * YAML live-diagnostics "Checking YAML…" indicator never resolved. <b>Fix:</b> renamed to
     * {@code doCheckContentSyntax} (URL segment {@code checkContentSyntax}), distinct from every
     * other segment/name on this class and its subclasses (see the full side-by-side table in this
     * class's other {@code do*} javadocs / the git history for the audit) — the JS-proxy sibling's
     * bound name ({@code validateContent}) is unaffected, since {@code index.jelly} calls
     * {@code proxy.validateContent(...)}, never the classic method's Java name.</p>
     */
    public JSONObject doCheckContentSyntax(@QueryParameter String content, @QueryParameter String contentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return validateContentImpl(content, ContentType.valueOf(contentType));
    }

    /**
     * JS-proxy-facing sibling of {@link #doCheckContentSyntax}; exposed as {@code proxy.validateContent(...)}.
     * See {@link #doActivateVersion(int)} javadoc for the naming-collision root cause this pair avoids.
     */
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

    private JSONObject validateContentImpl(String content, ContentType type) {
        JSONObject result = new JSONObject();
        try {
            TreeNode parsed = TreeFormats.forType(type).parse(content == null ? "" : content);
            if (!parsed.isObject()) {
                result.put("ok", false);
                result.put("error", "content must have a top-level object/root element");
                return result;
            }
            result.put("ok", true);
            return result;
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * AJAX: the XML/YAML document-format round-trip (see multi-format-content.md's "Editor parity"
     * section) — parses then re-serializes {@code content}
     * in its declared {@link ContentType}'s canonical, pretty-printed form. Shares
     * {@link TreeFormats}'s single dispatch point with {@link #doValidateContent}; JSON never needs
     * this endpoint (Monaco's built-in JSON formatter already handles it client-side), but the
     * endpoint itself is format-agnostic so nothing here special-cases JSON out.
     *
     * <p><b>Root-cause note (2026-09-02):</b> same collision trap as {@link #doCheckContentSyntax},
     * originally named {@code doFormatContent} (URL segment {@code formatContent}) which collided
     * with the {@code @JavaScriptMethod(name = "formatContent")} sibling below — the classic
     * dispatcher silently absorbed the JS-proxy call, throwing {@code NullPointerException: Name is
     * null} from {@link ContentType#valueOf(String)} and breaking XML/YAML auto-format-on-load
     * (JSON's auto-format kept working only because it is Monaco's own built-in client-side action,
     * not server-dependent). <b>Fix:</b> renamed to {@code doReformatContent} (URL segment
     * {@code reformatContent}) — distinct from every other segment/name on this class and its
     * subclasses; the JS-proxy sibling's bound name ({@code formatContent}) is unaffected.</p>
     */
    public JSONObject doReformatContent(@QueryParameter String content, @QueryParameter String contentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return formatContentImpl(content, ContentType.valueOf(contentType));
    }

    /**
     * JS-proxy-facing sibling of {@link #doReformatContent}; exposed as {@code proxy.formatContent(...)}.
     * See {@link #doActivateVersion(int)} javadoc for the naming-collision root cause this pair avoids.
     */
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

    private static String currentAuthor() {
        User current = User.current();
        return current == null ? "anonymous" : current.getId();
    }
}
