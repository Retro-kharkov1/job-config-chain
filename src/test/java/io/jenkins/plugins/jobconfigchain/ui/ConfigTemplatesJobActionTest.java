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
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import javax.script.Compilable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves both the "Config Templates" job sidebar link/discoverability (unchanged from the old
 * association-only design) and the new job-scoped content model (tech-lead design contract,
 * 2026-09-09): a job holds its OWN {@link JobConfigTemplateProperty} directly, rendered at
 * {@code /job/&lt;name&gt;/configTemplates}, replacing the old association-to-a-separate-ConfigSet
 * flow entirely.
 */
public class ConfigTemplatesJobActionTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void jobAction_isContributedToEveryJobAndMatchesTheManagementLinkEntry() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action");

        List<? extends Action> actions = project.getAllActions();
        ConfigTemplatesJobAction jobAction = actions.stream()
                .filter(ConfigTemplatesJobAction.class::isInstance)
                .map(ConfigTemplatesJobAction.class::cast)
                .findFirst()
                .orElse(null);

        assertTrue("every Job must be contributed a ConfigTemplatesJobAction sidebar link",
                jobAction != null);
        assertEquals("label must match the ManagementLink entry character-for-character",
                new ConfigTemplatesRootAction().getDisplayName(), jobAction.getDisplayName());
        assertEquals("icon must match the ManagementLink entry",
                new ConfigTemplatesRootAction().getIconFileName(), jobAction.getIconFileName());

        // Guards the exact defect reported 2026-09-18: the value had been written as one
        // dash-joined token ("symbol-<name>-plugin-<plugin>"), which Jenkins cannot resolve at
        // all — core's Functions#extractPluginNameFromIconSrc scans for a separate
        // whitespace-delimited "plugin-" word, so the plugin name came back empty and both menu
        // entries rendered the missing-symbol placeholder. Asserting the shape (not just that the
        // two sites agree) is the point: the previous assertion above passed happily while BOTH
        // sites were equally broken.
        String icon = jobAction.getIconFileName();
        assertTrue("icon must name a symbol: " + icon, icon.startsWith("symbol-"));
        assertTrue("icon must carry the owning plugin as a separate \"plugin-\" word, "
                        + "not dash-joined onto the symbol name: " + icon,
                icon.contains(" plugin-"));
        assertEquals("target must be a plain job-relative segment, exposed at "
                        + "/job/<name>/configTemplates just like /job/<name>/configure",
                "configTemplates", jobAction.getUrlName());
    }

    @Test
    public void jobPage_rendersTheConfigTemplatesSidebarLink() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action-2");

        HtmlPage page = jenkins.createWebClient().getPage(project);
        assertTrue("the job's page must render the 'Config Templates' sidebar link",
                page.asNormalizedText().contains("Config Templates"));
    }

    @Test
    public void jobAction_exposesTheJobItself_notACachedProperty() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action-3");
        ConfigTemplatesJobAction jobAction = findJobAction(project);

        assertEquals("the action must expose the owning job so callers (and the Jelly view) can "
                        + "always read the CURRENT property, never a stale snapshot",
                project, jobAction.getJob());
        assertFalse("a freshly created job must have no property yet", jobAction.isExists());

        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{}", "seed", "alice", 1L, java.util.Collections.emptyList(), null);
        project.addProperty(property);

        assertTrue("isExists()/getVersions() must reflect the property added AFTER this action "
                        + "instance was constructed — proves the property is read fresh, not cached",
                jobAction.isExists());
        assertEquals(1, jobAction.getVersions().size());
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
        assertTrue("Generate Template must never be gated on an active version existing",
                action.isTemplateAvailable());
        assertEquals("{}", action.getEditorSeedJson());

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(wc.getContextPath() + "job/" + project.getName() + "/configTemplates/");
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
        Page page = wc.getPage(wc.getContextPath() + "job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("save success/error must go through the native notificationBar toast",
                html.contains("window.notificationBar.show("));
        assertFalse("the old shared inline save banner element must be removed, not just unused",
                html.contains("id=\"saveBanner\""));
        assertFalse("the old showSaveBanner helper must be gone",
                html.contains("function showSaveBanner"));
    }

    @Test
    public void generateTemplate_fromUnsavedDraft_noActiveVersion_producesSensibleTemplate() throws Exception {
        // Owner requirement (2026-09-12): a never-saved job (no JobConfigTemplateProperty at all)
        // must still be able to generate a template straight from the CURRENT, unsaved editor draft
        // — the same overlayJson/baseChainJson/standaloneContentType inputs doComputeMerge already
        // accepts, never requiring an active version to exist first.
        FreeStyleProject project = jenkins.createFreeStyleProject("generate-from-draft-job");
        ConfigTemplatesJobAction action = findJobAction(project);
        assertFalse("this job must never have been saved for this test to be meaningful",
                action.isExists());

        String overlayJson = "{\"database\":{\"host\":\"db.internal\"}}";
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/renderTemplate?overlayJson=" + URLEncoder.encode(overlayJson, "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode("[]", "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue("generating from an unsaved draft with no active version must succeed",
                json.getBoolean("ok"));
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
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/renderTemplate?overlayJson=" + URLEncoder.encode("{}", "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode("[]", "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue("an empty draft/chain must resolve, not error out", json.getBoolean("ok"));
        assertEquals("JSON", json.getString("contentType"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        assertTrue("an empty override against an empty chain must template to an empty object",
                template.isEmpty());
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
        JenkinsRule.WebClient wc = jenkins.createWebClient();

        Page jobResult = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/computeMerge?overlayJson=" + URLEncoder.encode(overlayJson, "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject jobMerge = JSONObject.fromObject(jobResult.getWebResponse().getContentAsString());
        assertTrue("job merge preview must succeed for a resolvable chain", jobMerge.getBoolean("ok"));

        JSONObject merged = JSONObject.fromObject(jobMerge.getString("merged"));
        assertEquals("base's own 'a' must survive the fold (overlay never touches it)", 1, merged.getInt("a"));
        assertEquals("overlay's 'b' must win over the base chain's own 'b' (RFC 7396 overlay-wins)",
                2, merged.getInt("b"));

        JSONObject mergedBases = JSONObject.fromObject(jobMerge.getString("mergedBases"));
        assertEquals("mergedBases must reflect the base chain BEFORE the overlay is applied",
                1, mergedBases.getInt("b"));
    }

    // ---- State 3: override-only, zero chain, content used verbatim ----

    @Test
    public void overrideOnly_zeroChain_contentUsedVerbatim() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("state3-job");
        JenkinsRule.WebClient wc = jenkins.createWebClient();

        String overlayJson = "{\"solo\":true}";
        Page result = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/computeMerge?overlayJson=" + URLEncoder.encode(overlayJson, "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode("[]", "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());

        assertTrue("an empty chain must resolve, not error out", json.getBoolean("ok"));
        assertEquals("JSON", json.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(json.getString("merged"));
        assertTrue("with zero bases, the override content is used verbatim as the merged result",
                merged.getBoolean("solo"));
        JSONObject mergedBases = JSONObject.fromObject(json.getString("mergedBases"));
        assertTrue("zero bases fold to an empty object", mergedBases.isEmpty());
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

        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configTemplates/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "should be rejected"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page result = wc.getPage(request);

        assertFalse("a mismatched-type base chain must be rejected, not accepted with 200 OK",
                result.getWebResponse().getStatusCode() == 200);
        assertNull("no property must have been persisted",
                project.getProperty(JobConfigTemplateProperty.class));

        // Extended (tech-lead test-coverage migration decision, 2026-09-14 follow-up pass, rule 7):
        // this test already proved rejection but not the exact FR-61 message text — the dead
        // ConfigTemplatesUiTest#doSave_envPage_rejectsMixedContentTypeBaseChainWithExactWireframeMessage
        // asserted it at the (now-404ing) global env route; assert it here at the job route instead.
        String body = result.getWebResponse().getContentAsString();
        assertTrue("exact wireframe message shape (FR-61) must also render at the job route: " + body,
                body.contains("Save blocked: mismatched content types in base chain — "
                        + "mismatch-json (JSON), mismatch-xml (XML) must all share one content type."));
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
        assertEquals("append-only: both versions must remain in history", 2, property.getVersions().size());
        assertEquals(v1, property.getVersion(v1).getVersionNumber());
        assertEquals(v2, property.getVersion(v2).getVersionNumber());
        assertEquals("{\"x\":1}", property.getVersion(v1).getContentJson());
        assertEquals("{\"x\":2}", property.getVersion(v2).getContentJson());
        assertEquals(1, property.getVersion(v1).getBaseChain().size());
        assertTrue("v2 was saved with an explicitly empty chain", property.getVersion(v2).getBaseChain().isEmpty());

        // Activate v1 (the one WITH a base chain) via the classic endpoint and confirm the response
        // shape carries activatedBaseChain/activatedContent/activatedContentType but explicitly
        // never activatedExplicitlyStandalone.
        ConfigTemplatesJobAction action = findJobAction(project);
        JSONObject activated = action.doActivateVersion(v1);
        assertTrue(activated.getBoolean("ok"));
        assertEquals("{\"x\":1}", activated.getString("activatedContent"));
        assertEquals(1, activated.getJSONArray("activatedBaseChain").size());
        assertEquals("JSON", activated.getString("activatedContentType"));
        assertFalse("JobConfigTemplateProperty/JobConfigTemplateVersion has no explicitlyStandalone "
                        + "concept — the activate response must never carry that field",
                activated.has("activatedExplicitlyStandalone"));

        // Version-history rows must also never carry an explicitlyStandalone field.
        JSONArray versionsJson = activated.getJSONArray("versions");
        for (int i = 0; i < versionsJson.size(); i++) {
            assertFalse("version-history rows must never carry explicitlyStandalone for a job",
                    versionsJson.getJSONObject(i).has("explicitlyStandalone"));
        }
    }

    // ---- Migrated from ConfigTemplatesUiTest (tech-lead test-coverage migration decision,
    // 2026-09-14 follow-up pass): genuine, currently-uncovered, still-live business logic that used
    // to be exercised against the now-404ing global env route (ProjectConfigPage.getDynamic was
    // deleted); rewritten here against /job/<name>/configTemplates instead. ------------------------

    private void seedRealCredential(String id) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, id, "test credential seeded for UI test",
                hudson.util.Secret.fromString("dummy-value")));
        SystemCredentialsProvider.getInstance().save();
    }

    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?:\\s[^>]*)?>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);

    /** Mirrors ConfigTemplatesUiTest's own identically-named real-JS-engine syntax guard. */
    private void assertAllInlineScriptsAreSyntacticallyValidJs(String pageLabel, String html) throws ScriptException {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull("Nashorn JS engine must be resolvable on the test classpath "
                + "(org.openjdk.nashorn:nashorn-core test dependency)", engine);
        Compilable compilable = (Compilable) engine;

        Matcher matcher = INLINE_SCRIPT.matcher(html);
        int nonEmptyBlockCount = 0;
        List<String> failures = new ArrayList<>();
        while (matcher.find()) {
            String js = matcher.group(1);
            if (js == null || js.trim().isEmpty()) {
                continue;
            }
            nonEmptyBlockCount++;
            try {
                compilable.compile(js);
            } catch (ScriptException e) {
                failures.add("Inline <script> block on " + pageLabel + " is not valid JS: " + e.getMessage());
            }
        }
        assertTrue("expected at least one non-empty inline <script> block to check on " + pageLabel,
                nonEmptyBlockCount > 0);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void jobPage_doAddSecret_persistsMappingOnTheJobPropertyItself() throws Exception {
        seedRealCredential("job-secret-real-cred");
        FreeStyleProject project = jenkins.createFreeStyleProject("job-add-secret");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}",
                "seed", "seed-author", 1L, java.util.Collections.emptyList(), null);
        project.addProperty(property);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/registerSecret?path=database.password&credentialId=job-secret-real-cred");
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

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/unbindSecret?path=database.password");
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
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function removeSecret"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (remove-secret JS)", html);
    }

    @Test
    public void jobPage_rendersASecretCredentialPickerNotFreeText() throws Exception {
        seedRealCredential("job-picker-real-cred");
        FreeStyleProject project = jenkins.createFreeStyleProject("job-secret-picker");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("job page must render a real <select> credential picker (OQ-1), not free text",
                html.contains("id=\"secretCredentialId\""));
        assertTrue("job page's credential picker must list actually-registered credentials",
                html.contains("job-picker-real-cred"));
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-inline-scripts-valid");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "job Config Templates page", page.getWebResponse().getContentAsString());
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
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "job Config Templates page (multiline JSON seed)", page.getWebResponse().getContentAsString());
    }

    @Test
    public void jobPage_rendersGenerateTemplateButtonAboveThreePanelTable() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-generate-template-layout");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("job page must render a page-level Generate Template button (FR-41)",
                html.contains("id=\"generateTemplateBtn\""));
        assertTrue("job page must render the merge layout grid with its own id, replaced on click (FR-73)",
                html.contains("id=\"mergeLayout\""));
        assertTrue("job page must render the full-width generated-template panel container",
                html.contains("id=\"generatedTemplatePanel\""));
        assertTrue("job page must render the Back-to-3-panel-view affordance",
                html.contains("id=\"backTo3PanelBtn\""));
    }

    @Test
    public void jobPage_generateTemplateButtonNeverDisabled() throws Exception {
        // Owner requirement (2026-09-12): the job page's Generate Template button must ALWAYS be
        // clickable — renamed off "Common" (a job has no COMMON-active-version concept to reference).
        FreeStyleProject project = jenkins.createFreeStyleProject("job-generate-template-never-disabled");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        Matcher matcher = Pattern.compile("<button[^>]*id=\"generateTemplateBtn\"[^>]*>").matcher(html);
        assertTrue("must find the Generate Template button in the rendered HTML", matcher.find());
        assertFalse("Generate Template must never render disabled, even before this job has ever saved",
                matcher.group().contains("disabled"));
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withGenerateTemplateCode() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-generate-template-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("showGeneratedTemplateView"));
        assertTrue(html.contains("backTo3PanelView"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (generate-template JS)", html);
    }

    @Test
    public void jobPage_rendersExplicitBackToEditingButtonInCompareBanner() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-compare-banner");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("job compare banner must render an explicit 'Back to editing' button (FR-45a)",
                html.contains("id=\"backToEditingBtn\""));
        assertTrue("'Back to editing' must be wired to switchToEditMode() (FR-45b)",
                html.contains("onclick=\"switchToEditMode();\""));
        assertTrue("'Load into editor' must remain present and distinct (FR-45c)",
                html.contains("id=\"loadComparedBtn\""));
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
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();

        Pattern activeRow = Pattern.compile("id=\"historyRow-" + v2 + "\"[\\s\\S]*?</tr>");
        Matcher matcher = activeRow.matcher(html);
        assertTrue("must find the active version's history row in the rendered HTML", matcher.find());
        String rowHtml = matcher.group();
        assertTrue("the already-active row's Activate button must render disabled (FR-47)",
                rowHtml.contains("disabled=\"disabled\""));
        assertTrue("the disabled Activate button must explain why",
                rowHtml.contains("Already the active version"));
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withBusyDisableGuardCode() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-busy-disable-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("prepareSubmit must disable both save buttons (FR-46)",
                html.contains("document.getElementById('saveBtn').disabled = true"));
        assertTrue("activateVersion must disable every Activate button, not just the clicked one (FR-46)",
                html.contains("function setActivateButtonsDisabled"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (busy-disable-guard JS)", html);
    }

    @Test
    public void jobPage_rendersBaseChainEditorMarkup() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-base-chain-markup");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("job page must render the base-chain editor rows table",
                html.contains("id=\"baseChainRowsTable\""));
        assertTrue("job page must render the Add-base row control",
                html.contains("id=\"addBaseChainRowBtn\""));
        assertFalse("job page has no explicitlyStandalone concept — the checkbox must never render",
                html.contains("id=\"explicitlyStandaloneCheckbox\""));
        assertTrue("job page must render the merged-bases pane (FR-73)",
                html.contains("id=\"mergedBasesEditor\""));
        assertTrue("job page must render the Discard-all-changes button (FR-74)",
                html.contains("id=\"discardAllBtn\""));
        assertTrue("job page must render the hidden baseChainJson field",
                html.contains("id=\"baseChainField\""));
        assertTrue("job page must expose the available common project keys as row-picker seed data",
                html.contains("__availableProjectKeys"));
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
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("version-history row must render a [N bases] marker (FR-58)", html.contains("2 bases"));
        assertTrue("expandable detail must include the PINNED entry's resolved version number",
                html.contains("(v1)"));
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withBaseChainEditorCode() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-base-chain-editor-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function addBaseChainRow"));
        assertTrue(html.contains("function toggleBaseChainRowExpanded"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (base-chain editor JS)", html);
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
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/computeMerge?overlayJson=" + URLEncoder.encode("{}", "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals("JSON", json.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(json.getString("merged"));
        assertEquals(1, merged.getInt("a"));
        assertEquals(2, merged.getInt("b"));
        assertEquals("perReference must carry one entry per resolved chain member (FR-57)",
                2, json.getJSONArray("perReference").size());

        JSONObject row1 = json.getJSONArray("perReference").getJSONObject(0);
        JSONObject row2 = json.getJSONArray("perReference").getJSONObject(1);
        JSONObject row1Cumulative = JSONObject.fromObject(row1.getString("cumulativeJson"));
        assertEquals(1, row1Cumulative.getInt("a"));
        assertFalse(row1Cumulative.containsKey("b"));
        JSONObject row2Cumulative = JSONObject.fromObject(row2.getString("cumulativeJson"));
        assertEquals(1, row2Cumulative.getInt("a"));
        assertEquals(2, row2Cumulative.getInt("b"));
        assertEquals("row N's cumulative must equal mergedBases (both are the fold of the full "
                        + "chain with no overlay applied)",
                JSONObject.fromObject(json.getString("mergedBases")).toString(), row2Cumulative.toString());
    }

    @Test
    public void jobPage_doComputeMerge_unresolvableChainReferenceReportsErrorWithoutThrowing() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-unresolvable-chain");
        String baseChainJson = "[{\"projectKey\":\"no-such-common-project\",\"pinMode\":\"ACTIVE\"}]";
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/computeMerge?overlayJson=" + URLEncoder.encode("{}", "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("an unresolvable base-chain reference must be reported, not thrown as a server error",
                json.getBoolean("ok"));
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

        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configTemplates/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "missing pinMode"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson),
                new org.htmlunit.util.NameValuePair("activate", "true")
        ));
        Page result = wc.getPage(request);
        assertEquals("a base-chain row with no pinMode must default to ACTIVE and save successfully",
                200, result.getWebResponse().getStatusCode());

        JobConfigTemplateProperty property = project.getProperty(JobConfigTemplateProperty.class);
        assertNotNull(property);
        assertEquals(PinMode.ACTIVE, property.getActiveVersion().getBaseChain().get(0).getPinMode());
    }

    @Test
    public void jobPage_sectionsAreFramedNotCollapsible_andRenderInTheNewOrder() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-sections-order");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();

        assertFalse("none of the four reorganized sections may render as a collapsible <details> accordion",
                html.contains("<details"));
        assertTrue("each section must use Jenkins core's own framed-section convention",
                html.contains("jenkins-section"));

        int versionHistoryIdx = html.indexOf("id=\"versionHistoryDetails\"");
        int secretsManifestIdx = html.indexOf("id=\"secretsManifestDetails\"");
        int baseChainIdx = html.indexOf("id=\"baseChainEditor\"");
        int editorIdx = html.indexOf("id=\"editorDetails\"");
        assertTrue("all four section markers must be present",
                versionHistoryIdx >= 0 && secretsManifestIdx >= 0 && baseChainIdx >= 0 && editorIdx >= 0);
        assertTrue("Version history must render before Secrets manifest", versionHistoryIdx < secretsManifestIdx);
        assertTrue("Secrets manifest must render before the Base chain section", secretsManifestIdx < baseChainIdx);
        assertTrue("Base chain must render before the Editor section", baseChainIdx < editorIdx);
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_afterSectionReorg() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-section-reorg-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "job Config Templates page (post section-reorg)", page.getWebResponse().getContentAsString());
    }

    @Test
    public void jobPage_inlineScriptsContainReloadEditorStateFunction_andAreValidJs() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-reload-editor-state-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("activateVersion must reload the editor state from the newly-activated version",
                html.contains("function reloadEditorStateFromActivatedVersion"));
        assertTrue(html.contains("reloadEditorStateFromActivatedVersion(r)"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (activate-reload JS)", html);
    }

    @Test
    public void jobPage_addBaseButtonUsesClearerWording() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-add-base-wording");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("the Add-base button must use the clearer 'Add base config' wording",
                html.contains(">&#10133; Add base config</button>") || html.contains("Add base config"));
    }

    @Test
    public void jobPage_baseChainTable_constrainsSelectDropdownWidth() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-base-chain-select-width");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("the base-chain table must scope a compact max-width rule to its own selects",
                html.contains(".ctsync-basechain-table select.jenkins-select__input"));
    }

    @Test
    public void jobPage_rendersMergedBasesPaneAndDiscardButton() throws Exception {
        // SPLIT (rule 5): real half of the old envEditPage_rendersMergedBasesPaneAndDiscardButtonAndStandaloneCheckbox
        // — the explicitlyStandaloneCheckbox assertion is dropped (dead concept for jobs, rule 4).
        FreeStyleProject project = jenkins.createFreeStyleProject("job-merged-bases-pane");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("job page must render the Merged-bases pane (FR-73)", html.contains("id=\"mergedBasesEditor\""));
        assertTrue("job page must render the Discard-all-changes button (FR-74)",
                html.contains("id=\"discardAllBtn\""));
        assertTrue("the base-chain accordion toggle column must render per row (FR-71)",
                html.contains("ctsync-basechain-row-toggle") || html.contains("buildBaseChainRowElement"));
    }

    @Test
    public void jobPage_inlineScriptsAreSyntacticallyValidJs_withDiscardCode() throws Exception {
        // SPLIT (rule 5): real half of envEditPage_inlineScriptsAreSyntacticallyValidJs_withStandaloneAndDiscardCode
        // — dropped the standalone-code half (onExplicitlyStandaloneChange has no job-route equivalent).
        FreeStyleProject project = jenkins.createFreeStyleProject("job-discard-code-js");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function discardAllChangesClicked"));
        assertTrue(html.contains("function projectOptionsForRow"));
        assertTrue(html.contains("monaco.editor.createDiffEditor"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (discard JS)", html);
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
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"contentTypeLockedDisplay\""));
        assertFalse(html.contains("id=\"contentTypeUnlockedGroup\""));
    }

    @Test
    public void jobPage_rendersContentTypeRow_unlockedWhenNoVersionExists() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-content-type-unlocked");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("job page must render the FR-104 content-type row", html.contains("id=\"contentTypeRow\""));
        assertTrue("no version yet must render the interactive (unlocked) radio group",
                html.contains("id=\"contentTypeUnlockedGroup\""));
        assertTrue(html.contains("name=\"contentTypeRadio\""));
        assertFalse("must not render the locked display before any version exists",
                html.contains("id=\"contentTypeLockedDisplay\""));
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
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function onContentTypeChange"));
        assertTrue(html.contains("function applyContentTypeLocked"));
        assertTrue(html.contains("function updateContentTypeRowVisibility"));
        assertAllInlineScriptsAreSyntacticallyValidJs("job Config Templates page (content-type picker JS)", html);
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

        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configTemplates/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("content", "<root><a>2</a></root>"),
                new org.htmlunit.util.NameValuePair("note", "second save, attempted type change"),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]"),
                new org.htmlunit.util.NameValuePair("contentType", "YAML")
        ));
        wc.getPage(request);

        JobConfigTemplateProperty reloaded = project.getProperty(JobConfigTemplateProperty.class);
        assertEquals("a second version of an already-locked job property must never change its ContentType",
                ContentType.XML, reloaded.getContentType());
    }

    @Test
    public void jobPage_doComputeMerge_recomputesOnOverrideEdit_andReportsInvalidWithoutThrowing() throws Exception {
        // Not explicitly named in the tech-lead's disposition list but migrated by extension — mirrors
        // the deleted env-route doPreviewMerge_recomputesOnOverrideEdit_andReportsInvalidWithoutThrowing,
        // using an explicit baseChainJson (the job route has no FR-52 self-reference default to rely on).
        seedCommon("job-recompute-common", "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}", "seed");
        String baseChainJson = "[{\"projectKey\":\"job-recompute-common\",\"pinMode\":\"ACTIVE\"}]";

        FreeStyleProject project = jenkins.createFreeStyleProject("job-recompute-merge");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page valid = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/computeMerge?overlayJson="
                + URLEncoder.encode("{\"database\":{\"host\":null}}", "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject validJson = JSONObject.fromObject(valid.getWebResponse().getContentAsString());
        assertTrue(validJson.getBoolean("ok"));
        assertEquals("JSON", validJson.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(validJson.getString("merged"));
        assertFalse("null in overlay must remove the key from the merged result (RFC 7396)",
                merged.getJSONObject("database").has("host"));
        assertEquals(5432, merged.getJSONObject("database").getInt("port"));

        Page invalid = wc.getPage(wc.getContextPath() + "job/" + project.getName()
                + "/configTemplates/computeMerge?overlayJson=" + URLEncoder.encode("{ not valid", "UTF-8")
                + "&baseChainJson=" + URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject invalidJson = JSONObject.fromObject(invalid.getWebResponse().getContentAsString());
        assertFalse("transiently invalid override JSON must be reported, not thrown as a server error",
                invalidJson.getBoolean("ok"));
        assertNotNull(invalidJson.getString("error"));
    }

    @Test
    public void jobPage_baseChainRowExpanded_reorderLockstep_and_projectOptionsForRow_typeFilter_runInARealJsEngine()
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-basechain-js-engine");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();

        Matcher matcher = INLINE_SCRIPT.matcher(html);
        StringBuilder allScripts = new StringBuilder();
        while (matcher.find()) {
            if (matcher.group(1) != null) { allScripts.append(matcher.group(1)).append('\n'); }
        }

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        String harness =
                "var document = { getElementById: function() { return { addEventListener: function(){}, "
                        + "style:{}, classList:{add:function(){},remove:function(){}} }; }, "
                        + "querySelectorAll: function() { return []; } };"
                        + "var require = function(){}; require.config = function(){};"
                        + "var monaco = undefined;"
                        + "function makeStaplerProxy() { return {}; }"
                        + allScripts;
        engine.eval(harness);

        engine.eval("baseChainRows = [{projectKey:'a',pinMode:'ACTIVE',pinnedVersionNumber:0},"
                + "{projectKey:'b',pinMode:'ACTIVE',pinnedVersionNumber:0}];"
                + "baseChainRowExpanded = [false, true];"
                + "renderBaseChainRows = function() {};"
                + "recomputeMerge = function() {};"
                + "moveBaseChainRow(1, -1);");
        Object expandedAfterMove = engine.eval("baseChainRowExpanded[0]");
        assertEquals("moving the expanded row up must carry its expand state with it",
                Boolean.TRUE, expandedAfterMove);

        engine.eval("availableProjectKeys = ['json-proj', 'xml-proj'];"
                + "commonVersionCatalog = { typeByProject: { 'json-proj': 'JSON', 'xml-proj': 'XML' } };"
                + "baseChainRows = [{projectKey:'json-proj'}, {projectKey:'xml-proj'}];");
        Object row0Options = engine.eval("projectOptionsForRow(0).length");
        assertEquals("row #1 (index 0) must list every available project, unfiltered",
                2.0, ((Number) row0Options).doubleValue(), 0.001);
        Object row1Json = engine.eval("JSON.stringify(projectOptionsForRow(1))");
        assertEquals("row #2+ must be filtered to row #1's resolved ContentType — 'xml-proj' does not "
                + "match 'json-proj's JSON type", "[\"json-proj\"]", row1Json);
    }

    @Test
    public void jobPage_pinnedVersionLabel_isTruncatedSoLongNotesCannotPushTheRowActionsOut()
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-basechain-version-label");
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("job/" + project.getName() + "/configTemplates/");
        String html = page.getWebResponse().getContentAsString();

        Matcher matcher = INLINE_SCRIPT.matcher(html);
        StringBuilder allScripts = new StringBuilder();
        while (matcher.find()) {
            if (matcher.group(1) != null) { allScripts.append(matcher.group(1)).append('
'); }
        }

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        engine.eval("var document = { getElementById: function() { return { addEventListener: function(){}, "
                + "style:{}, classList:{add:function(){},remove:function(){}} }; }, "
                + "querySelectorAll: function() { return []; } };"
                + "var require = function(){}; require.config = function(){};"
                + "var monaco = undefined;"
                + "function makeStaplerProxy() { return {}; }"
                + allScripts);

        // A note short enough to read in the select must come back byte-for-byte. The info icon's
        // visibility is derived from exactly this equality, so an over-eager truncation here would
        // also paint an icon that reveals nothing.
        Object shortLabel = engine.eval("baseChainVersionLabel({version: 3, note: 'tidy up'})");
        assertEquals("v3 — tidy up", shortLabel);
        assertEquals("a label that already fits must be returned unchanged",
                shortLabel, engine.eval("truncateVersionLabel(baseChainVersionLabel("
                        + "{version: 3, note: 'tidy up'}))"));

        // Owner report 2026-09-21: an unbounded note widened the select, widened the column, and
        // pushed the row's action buttons out of the table.
        engine.eval("var longLabel = baseChainVersionLabel({version: 12, "
                + "note: 'switched the payment gateway sandbox endpoint and raised every retry budget'});");
        Object truncated = engine.eval("truncateVersionLabel(longLabel)");
        assertEquals("a long label must be capped so the select cannot grow without bound",
                40, ((String) truncated).length());
        assertTrue("a truncated label must end in an ellipsis so the elision is visible: " + truncated,
                ((String) truncated).endsWith("…"));
        assertTrue("the truncated label must keep the version prefix, which is the part that "
                        + "actually identifies the row: " + truncated,
                ((String) truncated).startsWith("v12 — "));
        assertEquals("truncation must not mutate the source label - the untruncated text is what "
                        + "the info tooltip shows", Boolean.TRUE,
                engine.eval("longLabel !== truncateVersionLabel(longLabel)"));
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
        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configTemplates/submitSave");
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
        assertNotNull("save must have persisted a JobConfigTemplateProperty", property);
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
