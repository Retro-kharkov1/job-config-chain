package io.jenkins.plugins.jobconfigchain.ui;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Action;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.PinMode;
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import javax.script.Compilable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves both the "Config Chains" job sidebar link/discoverability (unchanged from the old
 * association-only design) and the new job-scoped content model (tech-lead design contract,
 * 2026-09-09): a job holds its OWN {@link JobConfigTemplateProperty} directly, rendered at
 * {@code /job/&lt;name&gt;/configChains}, replacing the old association-to-a-separate-ConfigSet
 * flow entirely.
 */
@WithJenkins
public class ConfigTemplatesJobActionTest {

    private JenkinsRule jenkins;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

    @Test
    public void jobAction_isContributedToEveryJobAndMatchesTheManagementLinkEntry() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action");

        List<? extends Action> actions = project.getAllActions();
        ConfigTemplatesJobAction jobAction = actions.stream()
                .filter(ConfigTemplatesJobAction.class::isInstance)
                .map(ConfigTemplatesJobAction.class::cast)
                .findFirst()
                .orElse(null);

        assertTrue(jobAction != null, "every Job must be contributed a ConfigTemplatesJobAction sidebar link");
        assertEquals(new ConfigTemplatesRootAction().getDisplayName(), jobAction.getDisplayName(), "label must match the ManagementLink entry character-for-character");
        assertEquals(new ConfigTemplatesRootAction().getIconFileName(), jobAction.getIconFileName(), "icon must match the ManagementLink entry");

        // Guards the exact defect reported 2026-09-18: the value had been written as one
        // dash-joined token ("symbol-<name>-plugin-<plugin>"), which Jenkins cannot resolve at
        // all — core's Functions#extractPluginNameFromIconSrc scans for a separate
        // whitespace-delimited "plugin-" word, so the plugin name came back empty and both menu
        // entries rendered the missing-symbol placeholder. Asserting the shape (not just that the
        // two sites agree) is the point: the previous assertion above passed happily while BOTH
        // sites were equally broken.
        String icon = jobAction.getIconFileName();
        assertTrue(icon.startsWith("symbol-"), "icon must name a symbol: " + icon);
        assertTrue(icon.contains(" plugin-"), "icon must carry the owning plugin as a separate \"plugin-\" word, "
                        + "not dash-joined onto the symbol name: " + icon);
        assertEquals("configChains", jobAction.getUrlName(), "target must be a plain job-relative segment, exposed at "
                        + "/job/<name>/configChains just like /job/<name>/configure");
    }

    @Test
    public void jobPage_rendersTheConfigTemplatesSidebarLink() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action-2");

        HtmlPage page = jenkins.createWebClient().getPage(project);
        assertTrue(page.asNormalizedText().contains("Config Chains"), "the job's page must render the 'Config Chains' sidebar link");
    }

    @Test
    public void jobAction_exposesTheJobItself_notACachedProperty() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action-3");
        ConfigTemplatesJobAction jobAction = findJobAction(project);

        assertEquals(project, jobAction.getJob(), "the action must expose the owning job so callers (and the Jelly view) can "
                        + "always read the CURRENT property, never a stale snapshot");
        assertFalse(jobAction.isExists(), "a freshly created job must have no property yet");

        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{}", "seed", "alice", 1L, java.util.Collections.emptyList(), null);
        project.addProperty(property);

        assertTrue(jobAction.isExists(), "isExists()/getVersions() must reflect the property added AFTER this action "
                        + "instance was constructed — proves the property is read fresh, not cached");
        assertEquals(1, jobAction.getVersions().size());
    }

    // ---- Bug fix regression guard (2026-09-28) -----------------------------------------------
    //
    // Live-browser QA found that a job's very first successful Save left the page's "does not
    // exist yet" banner stale (still shown) until a full page reload — the in-place-update save
    // handler applied the returned version list, but never told the client the property had just
    // started existing. This proves the root-cause fix at the source: jsSave's JSON response now
    // carries an "exists" field (mirroring ConfigSetPage#jsSave's identically-named field), which
    // the client acts on via applyConfigSetNowExists — see the dedicated functional-JS test below
    // (jobPage_applyConfigSetNowExists_hidesTheNotExistYetBanner) for that half of the fix.

    @Test
    public void jsSave_onAJobsFirstSave_reportsExistsTrueInTheResponse() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-first-save-exists-check");
        ConfigTemplatesJobAction jobAction = findJobAction(project);
        assertFalse(jobAction.isExists(), "must start unsaved for this test to be meaningful");

        JSONObject result = jobAction.jsSave("{\"content\":\"{}\",\"note\":\"first\",\"activate\":false}");

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("exists"), "the very first successful save must report that "
                + "the property now exists, so the client can hide the \"does not exist yet\" "
                + "banner without a page reload");
        assertTrue(findJobAction(project).isExists(), "and the property must actually exist "
                + "server-side, confirming this isn't a client-only flag");
    }

    // ---- State 1: nothing configured ----

    @Test
    public void nothingConfigured_allFourBlocksRenderEmpty_noError() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("state1-nothing-configured");
        ConfigTemplatesJobAction action = findJobAction(project);

        assertFalse(action.isExists());
        assertTrue(action.getVersions().isEmpty());
        assertNull(action.getActiveVersion());
        assertTrue(action.getSecretsManifestForDisplay().isEmpty());
        // Owner requirement (2026-09-12): "Generate Template" must always be clickable, even before
        // anything has ever been saved — it now generates from the current, unsaved editor draft
        // instead of requiring an active version (see ConfigTemplatesJobActionGenerateTemplateTest).
        assertTrue(action.isTemplateAvailable(), "Generate Template must never be gated on an active version existing");
        assertEquals("{}", action.getEditorSeedJson());

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(wc.getContextPath() + "job/" + project.getName() + "/configChains/");
        assertEquals(200, page.getWebResponse().getStatusCode());
    }

    @Test
    public void jobPage_savesResultViaNativeNotificationBar_notInlineBanner() throws Exception {
        // Owner requirement (2026-09-14): the old shared #saveBanner div (SharedBlocks/
        // editorBlock.jelly) and its showSaveBanner JS helper are gone — save-result feedback now
        // goes through Jenkins core's native window.notificationBar toast, the same one "Apply" uses
        // on /job/&lt;name&gt;/configure. HtmlUnit's Rhino-based JS engine can't reliably render live
        // notificationBar DOM state, so this asserts on the served script content instead (reliably
        // testable) rather than trying to observe a live toast.
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action-notification");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(wc.getContextPath() + "job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        // CSP migration: this logic now lives in the external index.js, not inline.
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("window.notificationBar.show("), "save success/error must go through the native notificationBar toast");
        assertFalse(html.contains("id=\"saveBanner\""), "the old shared inline save banner element must be removed, not just unused");
        assertFalse(js.contains("function showSaveBanner"), "the old showSaveBanner helper must be gone");
    }

    @Test
    public void jobPage_neverSaved_notExistYetBannerCarriesAnId() throws Exception {
        // Bug fix regression guard (2026-09-28): mirrors CommonConfigSetPage's identical fix -
        // see jobPage_applyConfigSetNowExists_hidesTheNotExistYetBanner for the functional half.
        FreeStyleProject project = jenkins.createFreeStyleProject("job-exists-fix-markup-check");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(wc.getContextPath() + "job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();

        assertTrue(html.contains("id=\"notExistYetBanner\""), "the \"does not exist yet\" banner "
                + "must carry an id so the first Save's in-place-update response can hide it");
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("function applyConfigSetNowExists"), "the reveal function must be "
                + "defined in the external index.js (CSP migration - no inline handlers)");
        assertTrue(js.contains("if (r.exists) { applyConfigSetNowExists(); }"), "saveClicked's "
                + "success handler must actually call the reveal function when the server reports "
                + "the property now exists");
    }

    @Test
    public void jobPage_confirmByNameGateFix_isShippedInThisPagesOwnCopy() throws Exception {
        // Bug fix regression guard (2026-09-28): see ConfirmDialogMessageTest#confirmByNameDialog_
        // okButtonStaysDisabledUntilTheTypedNameMatches for the live-DOM functional proof of the
        // actual gating logic. This page carries a byte-identical duplicate of the same
        // confirmByNameBlock code — this test proves THIS page's copy actually shipped the fix too.
        FreeStyleProject project = jenkins.createFreeStyleProject("job-confirm-gate-fix-check");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(wc.getContextPath() + "job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String js = fetchExternalScripts(wc, html);

        assertTrue(js.contains("function enforceNameMatchOnOpenDialog"), "the dialog-gate wiring function must ship in this page's own index.js copy");
        assertTrue(js.contains("function findDialogOkButton"), "the OK-button lookup must ship in this page's own index.js copy");
        assertTrue(js.contains("function wireDialogNameInput"), "the per-keystroke gate must ship in this page's own index.js copy");
        assertTrue(js.contains("enforceNameMatchOnOpenDialog(opts.expectedName, s.cancel);"), "confirmByName must actually call the gate, not just define it unreferenced");
    }

    @Test
    public void generateTemplate_fromUnsavedDraft_noActiveVersion_producesSensibleTemplate() throws Exception {
        // Owner requirement (2026-09-12): a never-saved job (no JobConfigTemplateProperty at all)
        // must still be able to generate a template straight from the CURRENT, unsaved editor draft
        // — the same overlayJson/baseChainJson/standaloneContentType inputs doComputeMerge already
        // accepts, never requiring an active version to exist first.
        FreeStyleProject project = jenkins.createFreeStyleProject("generate-from-draft-job");
        ConfigTemplatesJobAction action = findJobAction(project);
        assertFalse(action.isExists(), "this job must never have been saved for this test to be meaningful");

        String overlayJson = "{\"database\":{\"host\":\"db.internal\"}}";
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL renderUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/renderTemplate");
        WebRequest renderRequest = new WebRequest(renderUrl, HttpMethod.POST);
        renderRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", overlayJson),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]")
        ));
        Page result = wc.getPage(renderRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"), "generating from an unsaved draft with no active version must succeed");
        assertEquals("JSON", json.getString("contentType"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        assertEquals("#{database.host}#", template.getJSONObject("database").getString("host"));
    }

    @Test
    public void generateTemplate_fromUnsavedDraft_emptyOverlayEmptyChain_producesEmptyObjectNotError()
            throws Exception {
        // Degenerate case: brand-new job, empty override, empty base chain — must still produce a
        // sensible (non-error) template, matching whatever previewMergeImpl already does for the
        // same empty-chain/empty-overlay input (an empty object, no substitutions to tokenize).
        FreeStyleProject project = jenkins.createFreeStyleProject("generate-from-draft-empty-job");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL renderUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/renderTemplate");
        WebRequest renderRequest = new WebRequest(renderUrl, HttpMethod.POST);
        renderRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", "{}"),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]")
        ));
        Page result = wc.getPage(renderRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"), "an empty draft/chain must resolve, not error out");
        assertEquals("JSON", json.getString("contentType"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        assertTrue(template.isEmpty(), "an empty override against an empty chain must template to an empty object");
    }

    // ---- State 2: base-chain rows + optional own override ----

    @Test
    public void baseChainAndOverride_mergePreviewFoldsBaseThenAppliesOverlay() throws Exception {
        // Rewritten (2026-09-14 full removal of ConfigSetRole.ENV/EnvConfigSetPage): this test used
        // to cross-check the job page's merge preview against EnvConfigSetPage's own
        // jsPreviewMerge(...) as an oracle. That class is now deleted in full (not merely
        // unreachable), so there is no second implementation left to cross-check against — this
        // asserts the job page's own merge/overlay numbers directly instead (self-contained, not a
        // silent coverage drop: the RFC 7396 fold-then-overlay behavior is still exercised end to
        // end, just without a second oracle implementation to compare to).
        String projectKey = "state2-common";
        seedCommon(projectKey, "{\"a\":1,\"b\":1}", "seed");

        String baseChainJson = "[{\"projectKey\":\"" + projectKey + "\",\"pinMode\":\"ACTIVE\"}]";
        String overlayJson = "{\"b\":2}";

        FreeStyleProject project = jenkins.createFreeStyleProject("state2-job");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();

        URL computeMergeUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/computeMerge");
        WebRequest computeMergeRequest = new WebRequest(computeMergeUrl, HttpMethod.POST);
        computeMergeRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", overlayJson),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page jobResult = wc.getPage(computeMergeRequest);
        JSONObject jobMerge = JSONObject.fromObject(jobResult.getWebResponse().getContentAsString());
        assertTrue(jobMerge.getBoolean("ok"), "job merge preview must succeed for a resolvable chain");

        JSONObject merged = JSONObject.fromObject(jobMerge.getString("merged"));
        assertEquals(1, merged.getInt("a"), "base's own 'a' must survive the fold (overlay never touches it)");
        assertEquals(2, merged.getInt("b"), "overlay's 'b' must win over the base chain's own 'b' (RFC 7396 overlay-wins)");

        JSONObject mergedBases = JSONObject.fromObject(jobMerge.getString("mergedBases"));
        assertEquals(1, mergedBases.getInt("b"), "mergedBases must reflect the base chain BEFORE the overlay is applied");
    }

    // ---- State 3: override-only, zero chain, content used verbatim ----

    @Test
    public void overrideOnly_zeroChain_contentUsedVerbatim() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("state3-job");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();

        String overlayJson = "{\"solo\":true}";
        URL computeMergeUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/computeMerge");
        WebRequest computeMergeRequest = new WebRequest(computeMergeUrl, HttpMethod.POST);
        computeMergeRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", overlayJson),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]")
        ));
        Page result = wc.getPage(computeMergeRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());

        assertTrue(json.getBoolean("ok"), "an empty chain must resolve, not error out");
        assertEquals("JSON", json.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(json.getString("merged"));
        assertTrue(merged.getBoolean("solo"), "with zero bases, the override content is used verbatim as the merged result");
        JSONObject mergedBases = JSONObject.fromObject(json.getString("mergedBases"));
        assertTrue(mergedBases.isEmpty(), "zero bases fold to an empty object");
    }

    // ---- Cross-chain content-type-consistency rejection ----

    @Test
    public void crossChainTypeMismatch_isRejectedAtSave_mirroringEnvConfigSetPageCoverage() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet jsonCommon = new ConfigSet("mismatch-json", ConfigSetRole.COMMON, null, "JSON Common", ContentType.JSON);
        int jv = jsonCommon.addVersion("{\"a\":1}", "seed", "seed-author", 1L);
        jsonCommon.activate(jv);
        repository.save(jsonCommon);

        ConfigSet xmlCommon = new ConfigSet("mismatch-xml", ConfigSetRole.COMMON, null, "XML Common", ContentType.XML);
        int xv = xmlCommon.addVersion("<root/>", "seed", "seed-author", 1L);
        xmlCommon.activate(xv);
        repository.save(xmlCommon);

        FreeStyleProject project = jenkins.createFreeStyleProject("cross-chain-type-mismatch");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        String baseChainJson = "[{\"projectKey\":\"mismatch-json\",\"pinMode\":\"ACTIVE\"},"
                + "{\"projectKey\":\"mismatch-xml\",\"pinMode\":\"ACTIVE\"}]";

        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "should be rejected"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page result = wc.getPage(request);

        assertFalse(result.getWebResponse().getStatusCode() == 200, "a mismatched-type base chain must be rejected, not accepted with 200 OK");
        assertNull(project.getProperty(JobConfigTemplateProperty.class), "no property must have been persisted");

        // Extended (tech-lead test-coverage migration decision, 2026-09-14 follow-up pass, rule 7):
        // this test already proved rejection but not the exact FR-61 message text — the dead
        // ConfigTemplatesUiTest#doSave_envPage_rejectsMixedContentTypeBaseChainWithExactWireframeMessage
        // asserted it at the (now-404ing) global env route; assert it here at the job route instead.
        String body = result.getWebResponse().getContentAsString();
        assertTrue(body.contains("Save blocked: mismatched content types in base chain — "
                        + "mismatch-json (JSON), mismatch-xml (XML) must all share one content type."), "exact wireframe message shape (FR-61) must also render at the job route: " + body);
    }

    // ---- Version history: append-only, Activate reloads baseChain+content, no explicitlyStandalone ----

    @Test
    public void versionHistory_isAppendOnly_activateReloadsBaseChainAndContent_noExplicitlyStandaloneField()
            throws Exception {
        seedCommon("history-common", "{\"a\":1}", "seed");

        FreeStyleProject project = jenkins.createFreeStyleProject("history-job");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();

        String baseChainJson = "[{\"projectKey\":\"history-common\",\"pinMode\":\"ACTIVE\"}]";
        int v1 = saveViaJsProxyLikeCall(project, wc, "{\"x\":1}", "v1", baseChainJson);
        int v2 = saveViaJsProxyLikeCall(project, wc, "{\"x\":2}", "v2", "[]");

        JobConfigTemplateProperty property = project.getProperty(JobConfigTemplateProperty.class);
        assertNotNull(property);
        assertEquals(2, property.getVersions().size(), "append-only: both versions must remain in history");
        assertEquals(v1, property.getVersion(v1).getVersionNumber());
        assertEquals(v2, property.getVersion(v2).getVersionNumber());
        assertEquals("{\"x\":1}", property.getVersion(v1).getContentJson());
        assertEquals("{\"x\":2}", property.getVersion(v2).getContentJson());
        assertEquals(1, property.getVersion(v1).getBaseChain().size());
        assertTrue(property.getVersion(v2).getBaseChain().isEmpty(), "v2 was saved with an explicitly empty chain");

        // Activate v1 (the one WITH a base chain) via the classic endpoint and confirm the response
        // shape carries activatedBaseChain/activatedContent/activatedContentType but explicitly
        // never activatedExplicitlyStandalone.
        ConfigTemplatesJobAction action = findJobAction(project);
        JSONObject activated = action.doActivateVersion(v1);
        assertTrue(activated.getBoolean("ok"));
        assertEquals("{\"x\":1}", activated.getString("activatedContent"));
        assertEquals(1, activated.getJSONArray("activatedBaseChain").size());
        assertEquals("JSON", activated.getString("activatedContentType"));
        assertFalse(activated.has("activatedExplicitlyStandalone"), "JobConfigTemplateProperty/JobConfigTemplateVersion has no explicitlyStandalone "
                        + "concept — the activate response must never carry that field");

        // Version-history rows must also never carry an explicitlyStandalone field.
        JSONArray versionsJson = activated.getJSONArray("versions");
        for (int i = 0; i < versionsJson.size(); i++) {
            assertFalse(versionsJson.getJSONObject(i).has("explicitlyStandalone"), "version-history rows must never carry explicitlyStandalone for a job");
        }
    }

    // ---- Migrated from ConfigTemplatesUiTest (tech-lead test-coverage migration decision,
    // 2026-09-14 follow-up pass): genuine, currently-uncovered, still-live business logic that used
    // to be exercised against the now-404ing global env route (ProjectConfigPage.getDynamic was
    // deleted); rewritten here against /job/<name>/configChains instead. ------------------------

    private void seedRealCredential(String id) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, id, "test credential seeded for UI test",
                hudson.util.Secret.fromString("dummy-value")));
        SystemCredentialsProvider.getInstance().save();
    }

    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?:\\s[^>]*)?>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXTERNAL_SCRIPT_SRC =
            Pattern.compile("<script src=[\"']([^\"']+)[\"']");

    /**
     * CSP migration (see https://www.jenkins.io/doc/developer/security/csp/): every
     * plugin-authored {@code <script>} on this page is now external ({@code <script src="...">},
     * loaded via {@code h.getViewResource}) rather than inline. Mirrors
     * ConfigTemplatesUiTest's own identically-named helper — fetches and concatenates every such
     * external script's content (skipping the vendored Monaco loader under {@code /monaco/}, which
     * is not plugin-authored and uses ES2015+ syntax the same Nashorn engine used here cannot parse
     * either).
     */
    private String fetchExternalScripts(JenkinsRule.WebClient wc, String html) throws Exception {
        Matcher m = EXTERNAL_SCRIPT_SRC.matcher(html);
        StringBuilder combined = new StringBuilder();
        while (m.find()) {
            String src = m.group(1);
            if (src.contains("/monaco/")) {
                continue; // vendored AMD loader — not plugin-authored, not in scope for this guard
            }
            if (!src.contains("jobconfigchain")) {
                // Not one of THIS plugin's own <script src="${h.getViewResource(it, 'index.js')}">
                // resources (whose resolved path always contains this plugin's package segment,
                // e.g. .../io/jenkins/plugins/jobconfigchain/ui/.../index.js) — every rendered
                // Jenkins page also loads core's own external bundles (behavior.js/prototype.js
                // etc. via <script src="...">), and gluing an unrelated multi-hundred-KB core
                // bundle onto our own script text before parsing produced spurious "invalid JS"
                // failures with no real defect behind them. Only this plugin's own script is in
                // scope for this guard.
                continue;
            }
            URL url = new URL(new URL(wc.getContextPath()), src);
            // Force UTF-8 rather than trusting the server-declared/default charset: static .js
            // resources served this way don't reliably get an explicit charset in their
            // Content-Type, and this plugin's own JS source is UTF-8 (e.g. the em dash in
            // baseChainVersionLabel), so the untyped overload silently mojibake'd it.
            combined.append(wc.getPage(url).getWebResponse()
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).append('\n');
        }
        return combined.toString();
    }

    private static final Pattern EXTERNAL_STYLESHEET_HREF =
            Pattern.compile("<link rel=[\"']stylesheet[\"'] href=[\"']([^\"']+)[\"']");

    /**
     * CSP migration counterpart to {@link #fetchExternalScripts} for this page's own inline
     * &lt;style&gt;, which moved to an external index.css (skipping the vendored Monaco
     * editor.main.css, which is not plugin-authored).
     */
    private String fetchExternalStylesheets(JenkinsRule.WebClient wc, String html) throws Exception {
        Matcher m = EXTERNAL_STYLESHEET_HREF.matcher(html);
        StringBuilder combined = new StringBuilder();
        while (m.find()) {
            String href = m.group(1);
            if (href.contains("/monaco/")) {
                continue;
            }
            URL url = new URL(new URL(wc.getContextPath()), href);
            combined.append(wc.getPage(url).getWebResponse().getContentAsString()).append('\n');
        }
        return combined.toString();
    }

    /**
     * Minimal DOM/AMD stub for evaluating the external index.js (CSP migration — this script now
     * reads its seed data via document.getElementById('ctsyncJobSeed').dataset.* at top-level
     * script-evaluation time, rather than the server interpolating literal values straight into the
     * script text — so unlike the pre-migration harness, getElementById must special-case that one
     * id and hand back a working dataset; every other id falls back to the same generic
     * addEventListener-capable stub the pre-migration harness already used. Seed values are computed
     * with the engine's own JSON.stringify rather than hand-escaped Java string literals, so the
     * double-JSON-encoding index.js expects (see that file's own "Seed data" comment) is always
     * correct regardless of how the encoding scheme evolves.
     */
    private static final String JOB_SEED_STUB_HARNESS =
            "var __ctsyncSeedDataset = { "
                    + "editorSeed: JSON.stringify('{}'), "
                    + "availableProjectKeys: JSON.stringify('[]'), "
                    + "baseChainSeed: JSON.stringify('[]'), "
                    + "commonVersionCatalog: JSON.stringify(JSON.stringify("
                    + "{versionsByProject:{}, typeByProject:{}})), "
                    + "contentTypeValue: 'JSON', rootUrl: '', monacoBase: '', "
                    + "emptyVersions: '', emptySecrets: '', emptyBasechain: '' };"
                    // Bug fix (2026-09-28): elements are now memoized by id, one object per id for
                    // the life of this engine instance, instead of a fresh throwaway object per
                    // getElementById() call — the throwaway form made it impossible for a test to
                    // observe a mutation a script-under-test made to "the same" element (e.g.
                    // applyConfigSetNowExists() setting style.display), since the test's own
                    // getElementById() call would always hand back a different object. Existing
                    // tests above this one never depended on distinct object identity per call, so
                    // this is a strictly more capable stand-in, not a behavior change for them.
                    + "var __ctsyncElements = {};"
                    + "var document = { getElementById: function(id) {"
                    + "  if (id === 'ctsyncJobSeed') { return { dataset: __ctsyncSeedDataset }; }"
                    + "  if (!__ctsyncElements[id]) {"
                    + "    __ctsyncElements[id] = { addEventListener: function(){}, style:{}, "
                    + "      classList:{add:function(){},remove:function(){}} };"
                    + "  }"
                    + "  return __ctsyncElements[id];"
                    + "}, "
                    + "querySelectorAll: function() { return []; }, "
                    + "querySelector: function() { return null; } };"
                    + "var require = function(){}; require.config = function(){};"
                    + "var monaco = undefined;"
                    + "function makeStaplerProxy() { return {}; }";

    /** Mirrors ConfigTemplatesUiTest's own identically-named real-JS-engine syntax guard. */
    private void assertAllInlineScriptsAreSyntacticallyValidJs(String pageLabel, JenkinsRule.WebClient wc,
                                                                String html) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine, "Nashorn JS engine must be resolvable on the test classpath "
                + "(org.openjdk.nashorn:nashorn-core test dependency)");
        Compilable compilable = (Compilable) engine;

        List<String> blocks = new ArrayList<>();
        Matcher matcher = INLINE_SCRIPT.matcher(html);
        while (matcher.find()) {
            String js = matcher.group(1);
            if (js != null && !js.trim().isEmpty()) {
                blocks.add(js);
            }
        }
        String external = fetchExternalScripts(wc, html);
        if (!external.trim().isEmpty()) {
            blocks.add(external);
        }

        int nonEmptyBlockCount = 0;
        List<String> failures = new ArrayList<>();
        for (String js : blocks) {
            nonEmptyBlockCount++;
            try {
                compilable.compile(js);
            } catch (ScriptException e) {
                failures.add("<script> block on " + pageLabel + " is not valid JS: " + e.getMessage());
            }
        }
        assertTrue(nonEmptyBlockCount > 0, "expected at least one non-empty <script> block (inline or external) to check on "
                + pageLabel);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    public void jobPage_doAddSecret_persistsMappingOnTheJobPropertyItself() throws Exception {
        seedRealCredential("job-secret-real-cred");
        FreeStyleProject project = jenkins.createFreeStyleProject("job-add-secret");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}",
                "seed", "seed-author", 1L, java.util.Collections.emptyList(), null);
        project.addProperty(property);

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL registerUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/registerSecret");
        WebRequest registerRequest = new WebRequest(registerUrl, HttpMethod.POST);
        registerRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("path", "database.password"),
                new org.htmlunit.util.NameValuePair("credentialId", "job-secret-real-cred")
        ));
        Page result = wc.getPage(registerRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));

        JobConfigTemplateProperty reloaded = project.getProperty(JobConfigTemplateProperty.class);
        assertEquals("job-secret-real-cred", reloaded.getSecretsManifest().get("database.password"));
    }

    @Test
    public void jobPage_doUnbindSecret_removesMappingOnTheJobPropertyItself() throws Exception {
        seedRealCredential("job-unbind-real-cred");
        FreeStyleProject project = jenkins.createFreeStyleProject("job-unbind-secret");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}",
                "seed", "seed-author", 1L, java.util.Collections.emptyList(), null);
        property.putSecretManifestEntry("database.password", "job-unbind-real-cred");
        project.addProperty(property);

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL unbindUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/unbindSecret");
        WebRequest unbindRequest = new WebRequest(unbindUrl, HttpMethod.POST);
        unbindRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("path", "database.password")
        ));
        Page result = wc.getPage(unbindRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));

        JobConfigTemplateProperty reloaded = project.getProperty(JobConfigTemplateProperty.class);
        assertTrue(reloaded.getSecretsManifest().isEmpty());
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withRemoveSecretCode() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-remove-secret-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(fetchExternalScripts(wc, html).contains("function removeSecret"), "removeSecret is now defined in the external index.js, not inline (CSP migration)");
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (remove-secret JS)", wc, html);
    }

    @Test
    public void jobPage_rendersASecretCredentialPickerNotFreeText() throws Exception {
        seedRealCredential("job-picker-real-cred");
        FreeStyleProject project = jenkins.createFreeStyleProject("job-secret-picker");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"secretCredentialId\""), "job page must render a real <select> credential picker (OQ-1), not free text");
        assertTrue(html.contains("job-picker-real-cred"), "job page's credential picker must list actually-registered credentials");
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-inline-scripts-valid");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "job Config Templates page", wc, page.getWebResponse().getContentAsString());
    }

    @Test
    public void jobPage_survivesPrettyPrintedMultilineJsonSeedContentWithoutBreakingTheInlineScript()
            throws Exception {
        String multilineJson = "{\n  \"database\": {\n    \"host\": \"db.internal\",\n"
                + "    \"note\": \"has a \\\"quoted\\\" word and a </script> look-alike\"\n  }\n}";
        FreeStyleProject project = jenkins.createFreeStyleProject("job-multiline-seed");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion(multilineJson, "seed", "seed-author", 1L,
                java.util.Collections.emptyList(), null);
        property.activate(v);
        project.addProperty(property);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "job Config Templates page (multiline JSON seed)", wc, page.getWebResponse().getContentAsString());
    }

    @Test
    public void jobPage_rendersGenerateTemplateButtonAboveThreePanelTable() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-generate-template-layout");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"generateTemplateBtn\""), "job page must render a page-level Generate Template button (FR-41)");
        assertTrue(html.contains("id=\"mergeLayout\""), "job page must render the merge layout grid with its own id, replaced on click (FR-73)");
        assertTrue(html.contains("id=\"generatedTemplatePanel\""), "job page must render the full-width generated-template panel container");
        assertTrue(html.contains("id=\"backTo3PanelBtn\""), "job page must render the Back-to-3-panel-view affordance");
    }

    @Test
    public void jobPage_generateTemplateButtonNeverDisabled() throws Exception {
        // Owner requirement (2026-09-12): the job page's Generate Template button must ALWAYS be
        // clickable — renamed off "Common" (a job has no COMMON-active-version concept to reference).
        FreeStyleProject project = jenkins.createFreeStyleProject("job-generate-template-never-disabled");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        Matcher matcher = Pattern.compile("<button[^>]*id=\"generateTemplateBtn\"[^>]*>").matcher(html);
        assertTrue(matcher.find(), "must find the Generate Template button in the rendered HTML");
        assertFalse(matcher.group().contains("disabled"), "Generate Template must never render disabled, even before this job has ever saved");
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withGenerateTemplateCode() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-generate-template-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("showGeneratedTemplateView"));
        assertTrue(js.contains("backTo3PanelView"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (generate-template JS)", wc, html);
    }

    @Test
    public void jobPage_rendersExplicitBackToEditingButtonInCompareBanner() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-compare-banner");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"backToEditingBtn\""), "job compare banner must render an explicit 'Back to editing' button (FR-45a)");
        // CSP migration: no inline onclick attribute any more — backToEditingBtn is wired to
        // switchToEditMode() in the external index.js instead; see that file's wiring block.
        assertTrue(fetchExternalScripts(wc, html).contains("on('backToEditingBtn', 'click', switchToEditMode)"), "'Back to editing' must be wired to switchToEditMode() (FR-45b)");
        assertTrue(html.contains("id=\"loadComparedBtn\""), "'Load into editor' must remain present and distinct (FR-45c)");
    }

    @Test
    public void jobPage_activateButtonDisabledOnTheAlreadyActiveRow() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-activate-disabled-row");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{\"a\":1}", "v1", "seed-author", 1L, java.util.Collections.emptyList(), null);
        int v2 = property.addVersion("{\"a\":2}", "v2", "seed-author", 2L, java.util.Collections.emptyList(), null);
        property.activate(v2);
        project.addProperty(property);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();

        Pattern activeRow = Pattern.compile("id=\"historyRow-" + v2 + "\"[\\s\\S]*?</tr>");
        Matcher matcher = activeRow.matcher(html);
        assertTrue(matcher.find(), "must find the active version's history row in the rendered HTML");
        String rowHtml = matcher.group();
        assertTrue(rowHtml.contains("disabled=\"disabled\""), "the already-active row's Activate button must render disabled (FR-47)");
        assertTrue(rowHtml.contains("Already the active version"), "the disabled Activate button must explain why");
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withBusyDisableGuardCode() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-busy-disable-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("document.getElementById('saveBtn').disabled = true"), "prepareSubmit must disable both save buttons (FR-46)");
        assertTrue(js.contains("function setActivateButtonsDisabled"), "activateVersion must disable every Activate button, not just the clicked one (FR-46)");
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (busy-disable-guard JS)", wc, html);
    }

    @Test
    public void jobPage_rendersBaseChainEditorMarkup() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-base-chain-markup");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"baseChainRowsTable\""), "job page must render the base-chain editor rows table");
        assertTrue(html.contains("id=\"addBaseChainRowBtn\""), "job page must render the Add-base row control");
        assertFalse(html.contains("id=\"explicitlyStandaloneCheckbox\""), "job page has no explicitlyStandalone concept — the checkbox must never render");
        assertTrue(html.contains("id=\"mergedBasesEditor\""), "job page must render the merged-bases pane (FR-73)");
        assertTrue(html.contains("id=\"discardAllBtn\""), "job page must render the Discard-all-changes button (FR-74)");
        assertTrue(html.contains("id=\"baseChainField\""), "job page must render the hidden baseChainJson field");
        // CSP migration: the seed value now rides on #ctsyncJobSeed's data-available-project-keys
        // attribute, read via JSON.parse(...) in the external index.js — no longer a bare
        // __availableProjectKeys token in the HTML itself.
        assertTrue(html.contains("data-available-project-keys="), "job page must expose the available common project keys as row-picker seed data");
    }

    @Test
    public void jobPage_versionHistoryRendersBaseChainMarkerPerVersion() throws Exception {
        seedCommon("job-history-marker-common", "{\"a\":1}", "seed");
        FreeStyleProject project = jenkins.createFreeStyleProject("job-history-marker");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion("{}", "seed", "seed-author", 1L, java.util.Arrays.asList(
                BaseConfigReference.active("job-history-marker-common"),
                BaseConfigReference.pinned("job-history-marker-common", 1)), null);
        property.activate(v);
        project.addProperty(property);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("2 bases"), "version-history row must render a [N bases] marker (FR-58)");
        assertTrue(html.contains("(v1)"), "expandable detail must include the PINNED entry's resolved version number");
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withBaseChainEditorCode() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-base-chain-editor-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("function addBaseChainRow"));
        assertTrue(js.contains("function toggleBaseChainRowExpanded"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (base-chain editor JS)", wc, html);
    }

    @Test
    public void jobPage_doComputeMerge_multiBaseChainFoldsAllBasesBeforeOverlay() throws Exception {
        seedCommon("job-multi-base-a", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet teamB = new ConfigSet("job-multi-base-b", ConfigSetRole.COMMON, null, "Team B Common", ContentType.JSON);
        int bV = teamB.addVersion("{\"b\":2}", "seed", "seed-author", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        String baseChainJson = "[{\"projectKey\":\"job-multi-base-a\",\"pinMode\":\"ACTIVE\"},"
                + "{\"projectKey\":\"job-multi-base-b\",\"pinMode\":\"ACTIVE\"}]";

        FreeStyleProject project = jenkins.createFreeStyleProject("job-multi-base-merge");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL computeMergeUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/computeMerge");
        WebRequest computeMergeRequest = new WebRequest(computeMergeUrl, HttpMethod.POST);
        computeMergeRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", "{}"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page result = wc.getPage(computeMergeRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals("JSON", json.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(json.getString("merged"));
        assertEquals(1, merged.getInt("a"));
        assertEquals(2, merged.getInt("b"));
        assertEquals(2, json.getJSONArray("perReference").size(), "perReference must carry one entry per resolved chain member (FR-57)");

        JSONObject row1 = json.getJSONArray("perReference").getJSONObject(0);
        JSONObject row2 = json.getJSONArray("perReference").getJSONObject(1);
        JSONObject row1Cumulative = JSONObject.fromObject(row1.getString("cumulativeJson"));
        assertEquals(1, row1Cumulative.getInt("a"));
        assertFalse(row1Cumulative.containsKey("b"));
        JSONObject row2Cumulative = JSONObject.fromObject(row2.getString("cumulativeJson"));
        assertEquals(1, row2Cumulative.getInt("a"));
        assertEquals(2, row2Cumulative.getInt("b"));
        assertEquals(JSONObject.fromObject(json.getString("mergedBases")).toString(), row2Cumulative.toString(), "row N's cumulative must equal mergedBases (both are the fold of the full "
                        + "chain with no overlay applied)");
    }

    @Test
    public void jobPage_doComputeMerge_unresolvableChainReferenceReportsErrorWithoutThrowing() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-unresolvable-chain");
        String baseChainJson = "[{\"projectKey\":\"no-such-common-project\",\"pinMode\":\"ACTIVE\"}]";
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL computeMergeUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/computeMerge");
        WebRequest computeMergeRequest = new WebRequest(computeMergeUrl, HttpMethod.POST);
        computeMergeRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", "{}"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page result = wc.getPage(computeMergeRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse(json.getBoolean("ok"), "an unresolvable base-chain reference must be reported, not thrown as a server error");
        assertNotNull(json.getString("error"));
    }

    @Test
    public void jobPage_doSave_baseChainRowMissingPinModeDefaultsToActive() throws Exception {
        seedCommon("job-missing-pinmode-common", "{\"a\":1}", "seed");
        FreeStyleProject project = jenkins.createFreeStyleProject("job-missing-pinmode");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);

        String baseChainJson = "[{\"projectKey\":\"job-missing-pinmode-common\"}]"; // pinMode deliberately absent

        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "missing pinMode"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson),
                new org.htmlunit.util.NameValuePair("activate", "true")
        ));
        Page result = wc.getPage(request);
        assertEquals(200, result.getWebResponse().getStatusCode(), "a base-chain row with no pinMode must default to ACTIVE and save successfully");

        JobConfigTemplateProperty property = project.getProperty(JobConfigTemplateProperty.class);
        assertNotNull(property);
        assertEquals(PinMode.ACTIVE, property.getActiveVersion().getBaseChain().get(0).getPinMode());
    }

    @Test
    public void jobPage_sectionsAreFramedNotCollapsible_andRenderInTheNewOrder() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-sections-order");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();

        assertFalse(html.contains("<details"), "none of the four reorganized sections may render as a collapsible <details> accordion");
        assertTrue(html.contains("jenkins-section"), "each section must use Jenkins core's own framed-section convention");

        int versionHistoryIdx = html.indexOf("id=\"versionHistoryDetails\"");
        int secretsManifestIdx = html.indexOf("id=\"secretsManifestDetails\"");
        int baseChainIdx = html.indexOf("id=\"baseChainEditor\"");
        int editorIdx = html.indexOf("id=\"editorDetails\"");
        assertTrue(versionHistoryIdx >= 0 && secretsManifestIdx >= 0 && baseChainIdx >= 0 && editorIdx >= 0, "all four section markers must be present");
        assertTrue(versionHistoryIdx < secretsManifestIdx, "Version history must render before Secrets manifest");
        assertTrue(secretsManifestIdx < baseChainIdx, "Secrets manifest must render before the Base chain section");
        assertTrue(baseChainIdx < editorIdx, "Base chain must render before the Editor section");
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_afterSectionReorg() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-section-reorg-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "job Config Templates page (post section-reorg)", wc, page.getWebResponse().getContentAsString());
    }

    @Test
    public void jobPage_inlineScriptsContainReloadEditorStateFunction_andAreValidJs() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-reload-editor-state-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("function reloadEditorStateFromActivatedVersion"), "activateVersion must reload the editor state from the newly-activated version");
        assertTrue(js.contains("reloadEditorStateFromActivatedVersion(r)"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (activate-reload JS)", wc, html);
    }

    @Test
    public void jobPage_addBaseButtonUsesClearerWording() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-add-base-wording");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains(">&#10133; Add base config</button>") || html.contains("Add base config"), "the Add-base button must use the clearer 'Add base config' wording");
    }

    @Test
    public void jobPage_baseChainTable_constrainsSelectDropdownWidth() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-base-chain-select-width");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        // CSP migration: this rule now lives in the external index.css, not an inline <style>.
        assertTrue(fetchExternalStylesheets(wc, html).contains(".ctsync-basechain-table select.jenkins-select__input"), "the base-chain table must scope a compact max-width rule to its own selects");
    }

    @Test
    public void jobPage_rendersMergedBasesPaneAndDiscardButton() throws Exception {
        // SPLIT (rule 5): real half of the old envEditPage_rendersMergedBasesPaneAndDiscardButtonAndStandaloneCheckbox
        // — the explicitlyStandaloneCheckbox assertion is dropped (dead concept for jobs, rule 4).
        FreeStyleProject project = jenkins.createFreeStyleProject("job-merged-bases-pane");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"mergedBasesEditor\""), "job page must render the Merged-bases pane (FR-73)");
        assertTrue(html.contains("id=\"discardAllBtn\""), "job page must render the Discard-all-changes button (FR-74)");
        // CSP migration: both the class name (assigned at runtime) and the builder function now
        // live in the external index.css/index.js rather than inline.
        assertTrue(fetchExternalStylesheets(wc, html).contains("ctsync-basechain-row-toggle")
                        || fetchExternalScripts(wc, html).contains("buildBaseChainRowElement"), "the base-chain accordion toggle column must render per row (FR-71)");
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withDiscardCode() throws Exception {
        // SPLIT (rule 5): real half of envEditPage_inlineScriptsAreSyntacticallyValidJs_withStandaloneAndDiscardCode
        // — dropped the standalone-code half (onExplicitlyStandaloneChange has no job-route equivalent).
        FreeStyleProject project = jenkins.createFreeStyleProject("job-discard-code-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("function discardAllChangesClicked"));
        assertTrue(js.contains("function projectOptionsForRow"));
        assertTrue(js.contains("monaco.editor.createDiffEditor"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (discard JS)", wc, html);
    }

    @Test
    public void jobPage_rendersContentTypeRow_lockedOnceAnyVersionExists() throws Exception {
        // MERGE (rule 6): the old env-route pair (locked-standalone / locked-non-standalone)
        // collapses into this ONE job-route test — the job model has no standalone/non-standalone
        // split, only "a version exists" / "no version exists yet".
        FreeStyleProject project = jenkins.createFreeStyleProject("job-content-type-locked");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion("{}", "seed", "seed-author", 1L, java.util.Collections.emptyList(), "XML");
        property.activate(v);
        project.addProperty(property);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"contentTypeLockedDisplay\""));
        assertFalse(html.contains("id=\"contentTypeUnlockedGroup\""));
    }

    @Test
    public void jobPage_rendersContentTypeRow_unlockedWhenNoVersionExists() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-content-type-unlocked");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"contentTypeRow\""), "job page must render the FR-104 content-type row");
        assertTrue(html.contains("id=\"contentTypeUnlockedGroup\""), "no version yet must render the interactive (unlocked) radio group");
        assertTrue(html.contains("name=\"contentTypeRadio\""));
        assertFalse(html.contains("id=\"contentTypeLockedDisplay\""), "must not render the locked display before any version exists");
    }

    @Test
    public void jobPage_inlineScript_containsContentTypePickerFunctions_andIsValidJs() throws Exception {
        // Not explicitly named in the tech-lead's disposition list (2026-09-14 follow-up pass) but
        // migrated by extension of rule 7's own pattern: the old env-route
        // envEditPage_inlineScript_containsContentTypePickerFunctions_andIsValidJs also hit the
        // now-404 global route and is genuine, currently job-route-uncovered business logic.
        FreeStyleProject project = jenkins.createFreeStyleProject("job-content-type-picker-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("function onContentTypeChange"));
        assertTrue(js.contains("function applyContentTypeLocked"));
        assertTrue(js.contains("function updateContentTypeRowVisibility"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (content-type picker JS)", wc, html);
    }

    @Test
    public void jobPage_contentType_immutableAfterFirstSave() throws Exception {
        // Not explicitly named in the tech-lead's disposition list but migrated by extension —
        // content-type immutability-after-first-save is real, live, job-route-relevant business logic
        // (see that entry's own grounding note ahead of its disposition list); mirrors the deleted
        // env-route doSubmitSave_envContentType_immutableAfterFirstStandaloneSave, minus its
        // explicitlyStandalone setup (a job property has no such field).
        FreeStyleProject project = jenkins.createFreeStyleProject("job-content-type-immutable");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion("<root><a>1</a></root>", "seed", "seed-author", 1L,
                java.util.Collections.emptyList(), "XML");
        property.activate(v);
        project.addProperty(property);

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);

        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("content", "<root><a>2</a></root>"),
                new org.htmlunit.util.NameValuePair("note", "second save, attempted type change"),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]"),
                new org.htmlunit.util.NameValuePair("contentType", "YAML")
        ));
        wc.getPage(request);

        JobConfigTemplateProperty reloaded = project.getProperty(JobConfigTemplateProperty.class);
        assertEquals(ContentType.XML, reloaded.getContentType(), "a second version of an already-locked job property must never change its ContentType");
    }

    @Test
    public void jobPage_doComputeMerge_recomputesOnOverrideEdit_andReportsInvalidWithoutThrowing() throws Exception {
        // Not explicitly named in the tech-lead's disposition list but migrated by extension — mirrors
        // the deleted env-route doPreviewMerge_recomputesOnOverrideEdit_andReportsInvalidWithoutThrowing,
        // using an explicit baseChainJson (the job route has no FR-52 self-reference default to rely on).
        seedCommon("job-recompute-common", "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}", "seed");
        String baseChainJson = "[{\"projectKey\":\"job-recompute-common\",\"pinMode\":\"ACTIVE\"}]";

        FreeStyleProject project = jenkins.createFreeStyleProject("job-recompute-merge");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL computeMergeUrl = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/computeMerge");

        WebRequest validRequest = new WebRequest(computeMergeUrl, HttpMethod.POST);
        validRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", "{\"database\":{\"host\":null}}"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page valid = wc.getPage(validRequest);
        JSONObject validJson = JSONObject.fromObject(valid.getWebResponse().getContentAsString());
        assertTrue(validJson.getBoolean("ok"));
        assertEquals("JSON", validJson.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(validJson.getString("merged"));
        assertFalse(merged.getJSONObject("database").has("host"), "null in overlay must remove the key from the merged result (RFC 7396)");
        assertEquals(5432, merged.getJSONObject("database").getInt("port"));

        WebRequest invalidRequest = new WebRequest(computeMergeUrl, HttpMethod.POST);
        invalidRequest.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("overlayJson", "{ not valid"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page invalid = wc.getPage(invalidRequest);
        JSONObject invalidJson = JSONObject.fromObject(invalid.getWebResponse().getContentAsString());
        assertFalse(invalidJson.getBoolean("ok"), "transiently invalid override JSON must be reported, not thrown as a server error");
        assertNotNull(invalidJson.getString("error"));
    }

    @Test
    public void jobPage_baseChainRowExpanded_reorderLockstep_and_projectOptionsForRow_typeFilter_runInARealJsEngine()
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-basechain-js-engine");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();

        // CSP migration: the functions under test now live in the external index.js, not inline —
        // fetch it instead of extracting an inline <script> body (see fetchExternalScripts).
        String allScripts = fetchExternalScripts(wc, html);

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        engine.eval(JOB_SEED_STUB_HARNESS + allScripts);

        engine.eval("baseChainRows = [{projectKey:'a',pinMode:'ACTIVE',pinnedVersionNumber:0},"
                + "{projectKey:'b',pinMode:'ACTIVE',pinnedVersionNumber:0}];"
                + "baseChainRowExpanded = [false, true];"
                + "renderBaseChainRows = function() {};"
                + "recomputeMerge = function() {};"
                + "moveBaseChainRow(1, -1);");
        Object expandedAfterMove = engine.eval("baseChainRowExpanded[0]");
        assertEquals(Boolean.TRUE, expandedAfterMove, "moving the expanded row up must carry its expand state with it");

        engine.eval("availableProjectKeys = ['json-proj', 'xml-proj'];"
                + "commonVersionCatalog = { typeByProject: { 'json-proj': 'JSON', 'xml-proj': 'XML' } };"
                + "baseChainRows = [{projectKey:'json-proj'}, {projectKey:'xml-proj'}];");
        Object row0Options = engine.eval("projectOptionsForRow(0).length");
        assertEquals(2.0, ((Number) row0Options).doubleValue(), 0.001,
                "row #1 (index 0) must list every available project, unfiltered");
        Object row1Json = engine.eval("JSON.stringify(projectOptionsForRow(1))");
        assertEquals("[\"json-proj\"]", row1Json, "row #2+ must be filtered to row #1's resolved ContentType — 'xml-proj' does not "
                + "match 'json-proj's JSON type");
    }

    @Test
    public void jobPage_pinnedVersionLabel_isTruncatedSoLongNotesCannotPushTheRowActionsOut()
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-basechain-version-label");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();

        // CSP migration: the functions under test now live in the external index.js, not inline —
        // fetch it instead of extracting an inline <script> body (see fetchExternalScripts).
        String allScripts = fetchExternalScripts(wc, html);

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        engine.eval(JOB_SEED_STUB_HARNESS + allScripts);

        // A note short enough to read in the select must come back byte-for-byte. The info icon's
        // visibility is derived from exactly this equality, so an over-eager truncation here would
        // also paint an icon that reveals nothing.
        Object shortLabel = engine.eval("baseChainVersionLabel({version: 3, note: 'tidy up'})");
        assertEquals("v3 — tidy up", shortLabel);
        assertEquals(shortLabel, engine.eval("truncateVersionLabel(baseChainVersionLabel("
                        + "{version: 3, note: 'tidy up'}))"), "a label that already fits must be returned unchanged");

        // Owner report 2026-09-21: an unbounded note widened the select, widened the column, and
        // pushed the row's action buttons out of the table.
        engine.eval("var longLabel = baseChainVersionLabel({version: 12, "
                + "note: 'switched the payment gateway sandbox endpoint and raised every retry budget'});");
        Object truncated = engine.eval("truncateVersionLabel(longLabel)");
        assertEquals(40, ((String) truncated).length(), "a long label must be capped so the select cannot grow without bound");
        assertTrue(((String) truncated).endsWith("…"), "a truncated label must end in an ellipsis so the elision is visible: " + truncated);
        assertTrue(((String) truncated).startsWith("v12 — "), "the truncated label must keep the version prefix, which is the part that "
                        + "actually identifies the row: " + truncated);
        assertEquals(Boolean.TRUE, engine.eval("longLabel !== truncateVersionLabel(longLabel)"), "truncation must not mutate the source label - the untruncated text is what "
                        + "the info tooltip shows");
    }

    /**
     * Bug fix regression guard (2026-09-28): runs the ACTUAL shipped {@code applyConfigSetNowExists}
     * function (index.js's client-side reaction to jsSave's new {@code exists} field — see
     * {@link #jsSave_onAJobsFirstSave_reportsExistsTrueInTheResponse} for the server-side half of
     * this fix) in a real JS engine against the job page's own DOM stub, rather than only asserting
     * the source text contains the right call. {@link #JOB_SEED_STUB_HARNESS} now memoizes elements
     * by id specifically so this test can observe the SAME banner object the function under test
     * mutates.
     */
    @Test
    public void jobPage_applyConfigSetNowExists_hidesTheNotExistYetBanner() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-exists-banner-check");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configChains/");
        String html = page.getWebResponse().getContentAsString();
        String allScripts = fetchExternalScripts(wc, html);

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        engine.eval(JOB_SEED_STUB_HARNESS + allScripts);

        Object beforeDisplay = engine.eval("document.getElementById('notExistYetBanner').style.display");
        assertTrue(beforeDisplay == null || "".equals(String.valueOf(beforeDisplay))
                        || "undefined".equals(String.valueOf(beforeDisplay)),
                "banner must start unhidden in this stub for the assertion below to be meaningful: " + beforeDisplay);

        engine.eval("applyConfigSetNowExists();");

        Object afterDisplay = engine.eval("document.getElementById('notExistYetBanner').style.display");
        assertEquals("none", afterDisplay, "the very first successful save must hide the stale "
                + "\"does not exist yet\" banner in place, without a page reload");
    }

    private int saveViaJsProxyLikeCall(FreeStyleProject project, JenkinsRule.WebClient wc, String content,
                                        String note, String baseChainJson) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        JSONObject payload = new JSONObject();
        payload.put("content", content);
        payload.put("note", note);
        payload.put("activate", false);
        payload.put("baseChainJson", baseChainJson);
        payload.put("contentType", "JSON");

        // doSubmitSave (classic, form-encoded) is the equivalent, URL-addressable sibling this
        // class's own tests exercise directly, mirroring ConfigSetPageValidateSyntaxTest's approach.
        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configChains/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("content", content),
                new org.htmlunit.util.NameValuePair("note", note),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson),
                new org.htmlunit.util.NameValuePair("contentType", "JSON")
        ));
        wc.getOptions().setRedirectEnabled(false);
        wc.getPage(request);

        JobConfigTemplateProperty property = project.getProperty(JobConfigTemplateProperty.class);
        assertNotNull(property, "save must have persisted a JobConfigTemplateProperty");
        int max = 0;
        for (var v : property.getVersions()) {
            max = Math.max(max, v.getVersionNumber());
        }
        return max;
    }

    private void seedCommon(String projectKey, String contentJson, String note) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet configSet = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, projectKey + " Common", ContentType.JSON);
        int v = configSet.addVersion(contentJson, note, "seed-author", 1L);
        configSet.activate(v);
        repository.save(configSet);
    }

    private static ConfigTemplatesJobAction findJobAction(FreeStyleProject project) {
        List<? extends Action> actions = project.getAllActions();
        return actions.stream()
                .filter(ConfigTemplatesJobAction.class::isInstance)
                .map(ConfigTemplatesJobAction.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
