package io.github.retrokharkov1.configtemplatesync.ui;

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
import io.github.retrokharkov1.configtemplatesync.merge.JsonPaths;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.model.PinMode;
import io.github.retrokharkov1.configtemplatesync.model.SecretPlaceholder;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared save/activate logic for the global (FR-30/31/32/33) and env-level (FR-34/35/37/39) admin
 * edit pages — both are the same {@link ConfigSet} Java type (§3 Entities), distinguished only by
 * {@link ConfigSetRole}, so both pages share this one implementation rather than duplicating the
 * save/validate/activate contract.
 *
 * <p><b>Save contract (tech-lead decision, 2026-08-27):</b> classic Stapler structured form POST,
 * redirecting back to this same edit page on success. <b>Activate contract:</b> a {@code doXxx} AJAX
 * endpoint returning a JSON object, updating the version-history list in place without a page
 * reload.</p>
 *
 * <p><b>JSON syntax validation (FR-33):</b> always re-checked here server-side — the Monaco editor's
 * client-side {@code jsonDefaults} diagnostics give live inline-marker feedback while typing, but a
 * save is never trusted on client-side validation alone. An invalid submission is rejected via
 * {@link Failure}, which Stapler renders as a plain, clear error page (the client-side inline
 * marker + banner in the wireframe is the primary UX for catching this before submit; the
 * server-side path here is the non-bypassable backstop).</p>
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

    public ConfigSet getConfigSet() {
        return repository.find(projectKey, getRole(), getEnvironment());
    }

    public boolean isExists() {
        return getConfigSet() != null;
    }

    public List<io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion> getVersions() {
        ConfigSet cs = getConfigSet();
        return cs == null ? List.of() : cs.getVersions();
    }

    public io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion getActiveVersion() {
        ConfigSet cs = getConfigSet();
        return cs == null ? null : cs.getActiveVersion();
    }

    /** Empty-content seed for a brand-new Config Set's editor (FR-1). */
    public String getEditorSeedJson() {
        io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion active = getActiveVersion();
        return active == null ? "{}" : active.getContentJson();
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

    @RequirePOST
    public void doSave(StaplerRequest req, StaplerResponse rsp,
                        @QueryParameter String content,
                        @QueryParameter String note,
                        @QueryParameter(fixEmpty = true) String activate,
                        @QueryParameter(fixEmpty = true) String baseChainJson) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        validateJsonSyntaxOrFail(content);
        List<BaseConfigReference> baseChain = parseBaseChainOrFail(baseChainJson);

        ConfigSet configSet = getConfigSet();
        if (configSet == null) {
            configSet = new ConfigSet(projectKey, getRole(), getEnvironment(), getDisplayNameSeed());
        }
        String author = currentAuthor();
        int newVersion;
        try {
            newVersion = configSet.addVersion(content, note, author, System.currentTimeMillis(), baseChain);
        } catch (IllegalArgumentException e) {
            // FR-14/OQ-1: structural secret-placeholder rejection, and (FR-53) a non-empty baseChain
            // on a COMMON-role Config Set, both surface here as a clear save error.
            throw new Failure("Save blocked: " + e.getMessage());
        }
        if (activate != null) {
            configSet.activate(newVersion);
        }
        repository.save(configSet);
        rsp.sendRedirect2(".");
    }

    /**
     * Parses the base-chain editor's draft JSON array (FR-51/FR-55) into
     * {@link BaseConfigReference}s, or returns an empty list if the field is absent/blank — the
     * global (common) page's Jelly never emits {@code baseChainJson} at all, so this is a
     * no-behavior-change path there. Throws {@link Failure} (never a raw {@link RuntimeException})
     * on malformed input, matching this page's existing save-time validation convention.
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
                PinMode mode = PinMode.valueOf(row.get("pinMode").getAsString());
                int pinnedVersion = row.has("pinnedVersionNumber") ? row.get("pinnedVersionNumber").getAsInt() : 0;
                result.add(new BaseConfigReference(rowProjectKey, mode, pinnedVersion));
            }
            return result;
        } catch (RuntimeException e) {
            // JsonSyntaxException, IllegalStateException, NullPointerException, IllegalArgumentException.
            throw new Failure("Save blocked: malformed base chain — " + e.getMessage());
        }
    }

    /**
     * Non-throwing sibling of {@link #parseBaseChainOrFail} for endpoints that must never throw and
     * instead report a structured {@code ok:false} (e.g. {@link EnvConfigSetPage}'s live merge
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
     * AJAX (Stapler JS-proxy): flips the active pointer without a page reload (FR-3/FR-6/OQ-6).
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
     * for the equivalent renames, and {@link EnvConfigSetPage#doComputeMerge(String)} for
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
     * argument shape ({@link EnvConfigSetPage#jsPreviewMerge}) proven to bind reliably through this
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
        if (configSet == null || configSet.getVersion(version) == null) {
            result.put("ok", false);
            result.put("error", "No such version: " + version);
            return result;
        }
        configSet.activate(version);
        repository.save(configSet);
        result.put("ok", true);
        result.put("active", version);
        return result;
    }

    /**
     * AJAX: registers (or re-points) a secrets-manifest entry for this Config Set — a dotted path
     * bound to a Jenkins credential ID (FR-12/13, OQ-1). This is the ONLY way a secret path's real
     * value is ever referenced; the picker itself is a credential-ID select, never free text, so it
     * structurally cannot hold a real secret value (OQ-1 resolution).
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
        // OQ-1 follow-up: the picker only ever offers real, currently-existing credential IDs, but
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
            // FR-12 retroactive-manifest-change gap: declaring an EXISTING path secret for the first
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
            configSet = new ConfigSet(projectKey, getRole(), getEnvironment(), getDisplayNameSeed());
        }
        configSet.putSecretManifestEntry(path, credentialId);
        repository.save(configSet);
        result.put("ok", true);
        return result;
    }

    /**
     * AJAX: removes (unbinds) a secrets-manifest entry — the CRUD-completion counterpart to
     * {@link #doRegisterSecret} (OQ-1 follow-up, 2026-09-01). A pure manifest-metadata mutation,
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
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(active.getContentJson());
        } catch (JsonSyntaxException e) {
            // an already-stored version is never expected to be invalid JSON, but if it somehow is,
            // there is nothing structured to inspect here — do not block the manifest update on that.
            return null;
        }
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement leaf = JsonPaths.get(root, path);
        if (leaf == null) {
            // absent leaf: nothing stored at this path yet, nothing to reject.
            return null;
        }
        String asString = leaf.isJsonPrimitive() && leaf.getAsJsonPrimitive().isString()
                ? leaf.getAsString()
                : null;
        if (asString != null && asString.equals(SecretPlaceholder.VALUE)) {
            return null;
        }
        return "Cannot mark '" + path + "' as a secret path: the currently-active version (v"
                + active.getVersionNumber() + ") already stores a real value there. Replace that value "
                + "with the '" + SecretPlaceholder.VALUE + "' placeholder and save a new version first, "
                + "then bind the credential.";
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
     * AJAX (Stapler JS-proxy): generates a copy-paste-ready JSON template (FR-15, FR-40/41, FR-44)
     * from the CURRENTLY ACTIVE version(s) of the relevant Config Set(s) — never an unsaved/
     * in-progress editor buffer (FR-16). Shared, non-abstract on this base class (both scopes share
     * identical permission-check/envelope/no-active-version-guard boilerplate); delegates the
     * scope-specific computation to the abstract {@link #computeTemplate()} hook, implemented
     * differently by {@link CommonConfigSetPage} (FR-15a) and {@link EnvConfigSetPage} (FR-15b) —
     * mirroring the existing pattern where {@code getCommonConfigSet}/{@code previewMerge} live only
     * on {@link EnvConfigSetPage}, not this shared base, because the two scopes genuinely compute
     * differently.
     *
     * <p>Named {@code doRenderTemplate} (URL segment {@code renderTemplate}), deliberately NOT
     * {@code doGenerateTemplate}, to avoid colliding with the
     * {@code @JavaScriptMethod(name = "generateTemplate")} sibling below — see
     * {@link #doActivateVersion(int)}'s javadoc for the full root-cause chain behind why that
     * collision matters. {@code renderTemplate} and {@code generateTemplate} share no substring
     * beyond "template," matching the deliberate word-choice divergence already used for the other
     * three pairs (FR-44).</p>
     *
     * <p><b>{@code @StaplerDispatchable} required:</b> unlike its siblings ({@code
     * doActivateVersion}, {@code doDiffVersions}, {@code doRegisterSecret}), this method has no
     * {@code @QueryParameter}/{@code StaplerRequest} parameter to serve as Stapler's post-2.138.4/
     * 2.154 "intended for routing" signal (see
     * https://www.jenkins.io/doc/developer/handling-requests/actions/) — its zero-argument
     * signature is a deliberate FR-16 guarantee (see {@link #jsGenerateTemplate()}'s javadoc), not
     * an oversight, so the explicit {@code @StaplerDispatchable} opt-in is required instead of
     * relying on an incidental parameter annotation.</p>
     */
    @jenkins.security.stapler.StaplerDispatchable
    public JSONObject doRenderTemplate() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl();
    }

    /**
     * JS-proxy-facing sibling of {@link #doRenderTemplate()}; exposed as
     * {@code proxy.generateTemplate(...)}. See {@link #doActivateVersion(int)} javadoc for the
     * naming-collision root cause this pair avoids.
     *
     * <p><b>Deliberately takes NO parameters</b> — the single strongest guarantee against
     * accidentally reading a draft/unsaved buffer (FR-16): contrast directly with
     * {@link EnvConfigSetPage#jsPreviewMerge}, whose entire purpose is to accept the caller's
     * current, possibly-unsaved {@code overlayJson} text. There is no argument through which a
     * draft could reach this method even by a future accidental edit — the method body has no such
     * parameter to read from in the first place.</p>
     */
    @JavaScriptMethod(name = "generateTemplate")
    public JSONObject jsGenerateTemplate() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl();
    }

    private JSONObject renderTemplateImpl() {
        JSONObject result = new JSONObject();
        if (getActiveVersionForTemplate() == null) {
            // FR-40/41's "no active version" guard, re-asserted server-side (defense in depth —
            // never trust the client-side disabled-button state alone).
            result.put("ok", false);
            result.put("error", "No active version yet — nothing to template.");
            return result;
        }
        com.google.gson.JsonObject template = computeTemplate();
        result.put("ok", true);
        result.put("template", net.sf.json.JSONObject.fromObject(template.toString()));
        return result;
    }

    /** FR-15a on {@link CommonConfigSetPage} / FR-15b on {@link EnvConfigSetPage} — see each override's javadoc. */
    abstract com.google.gson.JsonObject computeTemplate();

    /** The version whose absence blocks generation (FR-40/41's disabled-button guard). */
    abstract ConfigSetVersion getActiveVersionForTemplate();

    /**
     * Jelly-visible (must be public, see the class-level visibility note above): whether the
     * "Generate Template" action should render enabled (FR-40/41 — disabled with a "no active
     * version yet" label when there is nothing to template).
     */
    public final boolean isTemplateAvailable() {
        return getActiveVersionForTemplate() != null;
    }

    /**
     * Credential IDs available for the secrets-manifest picker (OQ-1): a real, existing Jenkins
     * credential, resolved from the standard credential store — structurally never a place a real
     * secret VALUE could be typed, only a reference by ID (FR-12/13, NFR-3). Scoped to the whole
     * controller (system-level credentials) since this admin screen is itself admin-only.
     */
    public List<String> getAvailableCredentialIds() {
        return CredentialsProvider
                .lookupCredentials(StandardCredentials.class, Jenkins.get(), ACL.SYSTEM, Collections.emptyList())
                .stream()
                .map(c -> c.getId())
                .collect(Collectors.toList());
    }

    static void validateJsonSyntaxOrFail(String content) {
        try {
            JsonElement parsed = JsonParser.parseString(content == null ? "" : content);
            if (!parsed.isJsonObject()) {
                throw new Failure("Save blocked: invalid JSON — content must be a JSON object");
            }
        } catch (JsonSyntaxException e) {
            throw new Failure("Save blocked: invalid JSON — " + e.getMessage());
        }
    }

    private static String currentAuthor() {
        User current = User.current();
        return current == null ? "anonymous" : current.getId();
    }
}
