package io.jenkins.plugins.jobconfigchain.ui;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.ManagementLink;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlTextInput;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import javax.script.Compilable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end Stapler/Jelly UI tests for the Milestone-2 admin screens (FR-30–FR-39). Uses
 * {@link JenkinsRule.WebClient} (HtmlUnit), the standard Jenkins UI-testing pattern for
 * JenkinsRule-based tests — see https://www.jenkins.io/doc/developer/testing/ ("Testing views").
 *
 * <p>Each test seeds its own uniquely-named Config Set (no shared cross-test state) to keep tests
 * isolated per the pom.xml's own notes on Windows JenkinsRule fork/AV flakiness.</p>
 */
public class ConfigTemplatesUiTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private ConfigSet seedCommon(String projectKey, String contentJson, String note) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v = common.addVersion(contentJson, note, "seed-author", 1L);
        common.activate(v);
        repository.save(common);
        return common;
    }

    // --- Entry point: ManagementLink under /manage/, not a top-nav RootAction (owner request,
    // 2026-09-02) ----------------------------------------------------------------------------

    @Test
    public void rootAction_isNotRegisteredAsATopNavAction() throws Exception {
        // Jenkins' top-right nav bar icon strip is populated exclusively from Jenkins#getActions(),
        // which core only ever fills from RootAction extensions (see
        // jenkins.model.Jenkins#getActions() javadoc). This screen must no longer appear there.
        boolean stillATopNavAction = jenkins.jenkins.getActions().stream()
                .anyMatch(a -> a instanceof ConfigTemplatesRootAction);
        assertFalse("the plugin's entry point must no longer be a top-nav RootAction",
                stillATopNavAction);
    }

    @Test
    public void rootAction_isRegisteredAsAManagementLinkUnderToolsCategory() throws Exception {
        ConfigTemplatesRootAction managementLink = ManagementLink.all().stream()
                .filter(ConfigTemplatesRootAction.class::isInstance)
                .map(ConfigTemplatesRootAction.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull("the plugin must register itself as a ManagementLink extension", managementLink);
        assertEquals("configTemplates", managementLink.getUrlName());
        assertEquals("Config Templates", managementLink.getDisplayName());
        assertEquals("closest fit in ManagementLink's fixed Category enum (see class javadoc)",
                ManagementLink.Category.TOOLS, managementLink.getCategory());
    }

    @Test
    public void managePage_listsTheConfigTemplatesEntry() throws Exception {
        HtmlPage page = jenkins.createWebClient().goTo("manage");
        assertTrue("the Manage Jenkins page must list an entry for this plugin",
                page.asNormalizedText().contains("Config Templates"));
    }

    @Test
    public void rootUrl_stillResolvesToTheSameConfigTemplatesScreen_viaManagementLinkNowInsteadOfRootAction()
            throws Exception {
        // Jenkins' root object resolves a bare /<urlName> token against both its RootAction AND its
        // ManagementLink extensions (Jenkins#getManagementLinks()/ManagementLink.all()) — confirming
        // the URL space is unchanged even though the extension point backing it changed.
        seedCommon("uitest70", "{\"a\":1}", "seed");

        HtmlPage page = jenkins.createWebClient().goTo("configTemplates");
        assertTrue("existing /configTemplates URL must still resolve to the same list page",
                page.asNormalizedText().contains("uitest70"));
    }

    @Test
    public void globalListPage_rendersSeededCommonConfigSets() throws Exception {
        seedCommon("uitest1", "{\"a\":1}", "seed");

        HtmlPage page = jenkins.createWebClient().goTo("configTemplates");
        assertTrue(page.asNormalizedText().contains("uitest1"));
    }

    @Test
    public void globalListPage_rowLinkText_isBareProjectKey_noCommonSuffix() throws Exception {
        // Owner decision, 2026-09-14 ("Config-Key hub page IS the common editor directly"): the
        // undocumented "-common" title-suffix convention is retired — the row link text must be the
        // bare project key, and the row link itself must resolve to /configTemplates/<key>/ (no
        // /common segment).
        seedCommon("uitest150", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates");
        String html = page.getWebResponse().getContentAsString();

        assertTrue("row link must point at /configTemplates/<key>/ with no /common suffix",
                html.contains("configTemplates/uitest150/\">uitest150<"));
        assertFalse("row link text must no longer carry the retired -common suffix",
                html.contains("uitest150-common"));
    }

    @Test
    public void editPage_loadsActiveVersionContent() throws Exception {
        seedCommon("uitest3", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        // JS disabled: the vendored Monaco AMD loader uses ES2015+ syntax the embedded legacy
        // HtmlUnit JS engine can't execute — irrelevant here, this assertion only needs the raw
        // server-rendered HTML (the seeded active version's JSON is inlined server-side).
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest3/");
        assertTrue(page.getWebResponse().getContentAsString().contains("db.internal"));
    }

    @Test
    public void rootProjectUrl_servesTheCommonEditorDirectly_notALandingPage() throws Exception {
        // Owner decision, 2026-09-14 ("Config-Key hub page IS the common editor directly"):
        // /configTemplates/<key>/ must render the full common Config Set editor (version history,
        // Monaco editor, Generate Template) directly — never a near-empty landing page that only
        // links onward to a separate /common sub-page.
        seedCommon("uitest151", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest151/");
        String html = page.getWebResponse().getContentAsString();

        assertTrue("the root project URL must render the seeded active version's content, proving "
                + "the common editor (not a stub landing page) is served here",
                html.contains("db.internal"));
        assertTrue("the root project URL must render the common editor's version-history section",
                html.contains("id=\"versionHistoryDetails\""));
        assertTrue("the root project URL must render the Generate Template action",
                html.contains("id=\"modeGenerateBtn\""));
    }

    @Test
    public void oldCommonUrlSegment_404sPlainly_noRedirect() throws Exception {
        // Owner decision, 2026-09-14: the /common URL segment is removed entirely, not kept as an
        // alias/redirect — a request to the old /configTemplates/<key>/common path must get a plain
        // Jenkins-core 404 (this plugin has never shipped externally, so there is no bookmark/
        // pipeline-script audience to protect with a redirect — same reasoning already applied to
        // the earlier env-level route removal).
        seedCommon("uitest152", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest152/common/");
        assertEquals("the old /common URL segment must plainly 404, not redirect or render a stub",
                404, result.getWebResponse().getStatusCode());
    }

    @Test
    public void commonConfigSetPage_getDisplayName_returnsTheBareProjectKey() throws Exception {
        // Owner decision, 2026-09-14: the undocumented "-common" title suffix is retired — this page
        // is now the sole handler for its own URL and needs no disambiguating role label.
        CommonConfigSetPage page = new CommonConfigSetPage("uitest153", new ConfigSetRepository());
        assertEquals("uitest153", page.getDisplayName());
    }

    @Test
    public void doSave_rejectsInvalidJson_bothWaysGuarded() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest4/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "{ not valid json"),
                new org.htmlunit.util.NameValuePair("note", "bad save attempt")
        ));
        Page result = wc.getPage(request);
        assertFalse("invalid JSON must not be accepted (FR-33)",
                result.getWebResponse().getStatusCode() == 200);

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals("no Config Set must have been persisted from the rejected save",
                null, repository.findCommon("uitest4"));
    }

    @Test
    public void doActivate_flipsActiveFlagWithoutPageReload() throws Exception {
        ConfigSet common = seedCommon("uitest5", "{\"a\":1}", "v1");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloaded = repository.findCommon("uitest5");
        int v2 = reloaded.addVersion("{\"a\":2}", "v2", "seed-author", 2L);
        repository.save(reloaded);
        assertEquals(1, reloaded.getActiveVersionNumber());

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(
                wc.getContextPath() + "configTemplates/uitest5/activateVersion?version=" + v2);
        String body = result.getWebResponse().getContentAsString();
        JSONObject json = JSONObject.fromObject(body);
        assertTrue(json.getBoolean("ok"));
        assertEquals(v2, json.getInt("active"));

        ConfigSetRepository verify = new ConfigSetRepository();
        assertEquals(v2, verify.findCommon("uitest5").getActiveVersionNumber());
    }

    @Test
    public void doCompareVersions_returnsOneVersionsContentForDiffAgainstTheCurrentDraft() throws Exception {
        ConfigSet common = seedCommon("uitest8", "{\"a\":1}", "v1");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloaded = repository.findCommon("uitest8");
        int v2 = reloaded.addVersion("{\"a\":2}", "v2", "seed-author", 2L);
        repository.save(reloaded);

        // Server-side reachability check for the Compare mode toggle (wireframe: the SAME editor
        // region switches to monaco.editor.createDiffEditor). Interaction redesign (2026-09-01,
        // owner report): click one history row to diff it against the CURRENT DRAFT, no checkbox
        // pair required — this endpoint now only fetches ONE
        // version's content — the other side of the diff is the client's own already-in-memory
        // draft buffer, never round-tripped to the server. Full Monaco JS execution
        // (createDiffEditor itself) is not exercisable here: the vendored AMD loader/bundle uses
        // ES2015+ syntax the embedded legacy HtmlUnit JS engine can't run (same limitation already
        // documented on editPage_loadsActiveVersionContent above) — this test instead proves the
        // exact data contract the client-side diff view is wired to.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest8/diffVersions?version=" + v2);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals(v2, json.getInt("version"));
        assertEquals("{\"a\":2}", json.getString("content"));
    }

    @Test
    public void doCompareVersions_reportsErrorForMissingVersionWithoutThrowing() throws Exception {
        seedCommon("uitest9", "{\"a\":1}", "v1");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest9/diffVersions?version=99");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("a nonexistent version number must be reported, not thrown as a server error",
                json.getBoolean("ok"));
        assertNotNull(json.getString("error"));
    }

    @Test
    public void secretPlaceholderPath_neverAcceptsARealValueOnSave() throws Exception {
        ConfigSet common = seedCommon("uitest7", "{\"database\":{\"password\":\""
                + SecretPlaceholder.VALUE + "\"}}", "seed");
        common.putSecretManifestEntry("database.password", "some-credential-id");
        new ConfigSetRepository().save(common);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest7/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair(
                        "content", "{\"database\":{\"password\":\"hunter2-real-secret\"}}"),
                new org.htmlunit.util.NameValuePair("note", "trying to leak a real secret")
        ));
        Page result = wc.getPage(request);
        assertFalse("a real value at a manifest-declared secret path must be rejected (FR-14/OQ-1)",
                result.getWebResponse().getStatusCode() == 200);

        ConfigSetRepository verify = new ConfigSetRepository();
        ConfigSet reloaded = verify.findCommon("uitest7");
        assertEquals("no new version must have been persisted from the rejected save",
                1, reloaded.getVersions().size());
        assertFalse("the real secret text must never have been written to storage",
                reloaded.getActiveVersion().getContentJson().contains("hunter2-real-secret"));
    }

    private void seedRealCredential(String id) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, id, "test credential seeded for UI test",
                hudson.util.Secret.fromString("dummy-value")));
        SystemCredentialsProvider.getInstance().save();
    }

    @Test
    public void doAddSecret_rejectsACredentialIdThatDoesNotExist() throws Exception {
        seedCommon("uitest10", "{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest10/registerSecret?path=database.password&credentialId=does-not-exist");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("a credential ID with no matching real Jenkins credential must be rejected (OQ-1)",
                json.getBoolean("ok"));
        assertNotNull(json.getString("error"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertTrue("no manifest entry must have been persisted for the rejected credential ID",
                repository.findCommon("uitest10").getSecretsManifest().isEmpty());
    }

    @Test
    public void doAddSecret_acceptsARealExistingCredentialAndPersistsTheMapping() throws Exception {
        seedRealCredential("uitest11-real-cred");
        seedCommon("uitest11", "{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest11/registerSecret?path=database.password&credentialId=uitest11-real-cred");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals("uitest11-real-cred",
                repository.findCommon("uitest11").getSecretsManifest().get("database.password"));
    }

    @Test
    public void doAddSecret_rejectsRetroactiveDeclareWhenActiveVersionHoldsARealValue() throws Exception {
        // FR-12 retroactive-manifest-change gap: the active version was saved BEFORE this path was
        // ever declared secret, so it legitimately holds a real plain-text value. Newly declaring it
        // secret now must be rejected rather than silently leaving that real value sitting in storage.
        seedRealCredential("uitest13-real-cred");
        seedCommon("uitest13", "{\"database\":{\"password\":\"a-real-plaintext-password\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest13/registerSecret?path=database.password&credentialId=uitest13-real-cred");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("must reject declaring a path secret when the active version already holds a real value",
                json.getBoolean("ok"));
        assertNotNull(json.getString("error"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertTrue("no manifest entry must have been persisted for the rejected retroactive declaration",
                repository.findCommon("uitest13").getSecretsManifest().isEmpty());
    }

    @Test
    public void doAddSecret_acceptsRetroactiveDeclareWhenActiveVersionAlreadyHoldsThePlaceholder() throws Exception {
        seedRealCredential("uitest14-real-cred");
        seedCommon("uitest14", "{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest14/registerSecret?path=database.password&credentialId=uitest14-real-cred");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue("a path already holding the placeholder must be acceptable to newly declare secret",
                json.getBoolean("ok"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals("uitest14-real-cred",
                repository.findCommon("uitest14").getSecretsManifest().get("database.password"));
    }

    // --- Secrets manifest delete/unbind (OQ-1 CRUD completion, 2026-09-01) -----------------

    @Test
    public void doUnbindSecret_removesAnExistingManifestEntryAndPersists() throws Exception {
        seedRealCredential("uitest31-real-cred");
        ConfigSet common = seedCommon("uitest31", "{\"database\":{\"password\":\""
                + SecretPlaceholder.VALUE + "\"}}", "seed");
        common.putSecretManifestEntry("database.password", "uitest31-real-cred");
        new ConfigSetRepository().save(common);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest31/unbindSecret?path=database.password");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertTrue("the manifest entry must no longer be present after removal",
                repository.findCommon("uitest31").getSecretsManifest().isEmpty());
    }

    @Test
    public void doUnbindSecret_reportsErrorForAPathNeverBoundWithoutThrowing() throws Exception {
        seedCommon("uitest32", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest32/unbindSecret?path=never.bound");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("unbinding a path that was never bound must be reported, not thrown as a server error",
                json.getBoolean("ok"));
        assertNotNull(json.getString("error"));
    }

    @Test
    public void commonEditPage_rendersARemoveButtonPerManifestEntry() throws Exception {
        seedRealCredential("uitest34-real-cred");
        ConfigSet common = seedCommon("uitest34", "{\"database\":{\"password\":\""
                + SecretPlaceholder.VALUE + "\"}}", "seed");
        common.putSecretManifestEntry("database.password", "uitest34-real-cred");
        new ConfigSetRepository().save(common);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest34/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("a manifest row must render a delete control (CRUD completion)",
                html.contains("ctsync-remove-secret-btn"));
        assertTrue("the delete control must carry the dotted path so removeSecret() can read it",
                html.contains("data-secret-path=\"database.password\""));
    }

    @Test
    public void commonEditPage_inlineScriptsAreSyntacticallyValidJs_withRemoveSecretCode() throws Exception {
        // Regression guard for the newly added removeSecret JS, matching the existing real-JS-engine
        // syntax-check pattern for this test class.
        seedCommon("uitest35", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest35/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function removeSecret"));
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (remove-secret JS)", html);
    }

    @Test
    public void commonEditPage_savesResultViaNativeNotificationBar_notInlineBanner() throws Exception {
        // Owner requirement (2026-09-14): the old inline #saveBanner div (and its showSaveBanner JS
        // helper) is gone — save-result feedback now goes through Jenkins core's native
        // window.notificationBar toast, the same one "Apply" uses on /job/&lt;name&gt;/configure.
        // HtmlUnit's Rhino-based JS engine can't reliably render live notificationBar DOM state, so
        // this asserts on the served script content instead (reliably testable) rather than trying
        // to observe a live toast.
        seedCommon("uitest131", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest131/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("save success/error must go through the native notificationBar toast",
                html.contains("window.notificationBar.show("));
        assertFalse("the old inline save banner element must be removed, not just unused",
                html.contains("id=\"saveBanner\""));
        assertFalse("the old showSaveBanner helper must be gone",
                html.contains("function showSaveBanner"));
    }

    // --- Real-JS-engine regression guard --------------------------------------------------
    //
    // Bug history: CommonConfigSetPage/EnvConfigSetPage's inline Monaco-seed <script> blocks
    // built their embedded JSON via `"${h.jsStringEscape(...)}"` — `h.jsStringEscape` only
    // escapes `'`, `\`, and `"`, NOT raw newlines, so pretty-printed JSON content produced a
    // literal unescaped line break inside a single-line double-quoted JS string, i.e. an
    // unterminated-string SyntaxError in every real browser JS engine. HtmlUnit's own embedded
    // JS engine did not fail on the same rendered HTML (see the JS-execution-limitation notes on
    // editPage_loadsActiveVersionContent/doCompareVersions_returnsBothVersionsContentForDiffMode
    // above), so a plain JenkinsRule/HtmlUnit assertion would never have caught this class of
    // bug. These tests instead extract each page's actual rendered inline <script> bodies and
    // compile (syntax-check only, never execute — Monaco/require()/DOM globals are not present
    // here) them with a real, standalone ECMAScript engine (Nashorn, test-scope only).

    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?:\\s[^>]*)?>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);

    /**
     * Extracts every inline (non-{@code src=}) {@code <script>} body from rendered HTML and
     * compiles each with a real JS engine, failing with a clear message per broken block if any
     * fails to parse. Asserts at least one non-empty inline script was actually found, so this
     * guard cannot silently pass by finding nothing to check.
     */
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
                continue; // an external `<script src="...">` tag, nothing inline to check here
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
    public void commonEditPage_inlineScriptsAreSyntacticallyValidJs() throws Exception {
        seedCommon("uitest16", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest16/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "common edit page", page.getWebResponse().getContentAsString());
    }

    @Test
    public void commonPage_survivesPrettyPrintedMultilineJsonSeedContentWithoutBreakingTheInlineScript()
            throws Exception {
        // Regression-specific: this is the exact shape that triggered the bug — real newline
        // bytes (not "\n" escape sequences) inside the stored contentJson, as produced by any
        // pretty-printer, plus a quote character to also confirm h.jsStringEscape's quote/
        // backslash handling still round-trips correctly through the new encoding. The env-page
        // half of this regression is now covered at the job route — see
        // ConfigTemplatesJobActionTest#jobPage_survivesPrettyPrintedMultilineJsonSeedContentWithoutBreakingTheInlineScript
        // (tech-lead test-coverage migration decision, 2026-09-14 follow-up pass).
        String multilineJson = "{\n  \"database\": {\n    \"host\": \"db.internal\",\n"
                + "    \"note\": \"has a \\\"quoted\\\" word and a </script> look-alike\"\n  }\n}";
        seedCommon("uitest18", multilineJson, "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);

        HtmlPage commonPage = wc.goTo("configTemplates/uitest18/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "common edit page (multiline JSON seed)", commonPage.getWebResponse().getContentAsString());
    }

    // --- Generate Template (FR-15/16, FR-40-44) -------------------------------------------

    @Test
    public void commonPage_doRenderTemplate_returnsTokenizedActiveVersionContent() throws Exception {
        seedCommon("uitest20", "{\"database\":{\"host\":\"db.internal\",\"password\":\""
                + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest20/renderTemplate");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        // §7/FR-64: `template` is now already-serialized text in the resolved contentType, plus a
        // new `contentType` field — parse it here to inspect.
        assertEquals("JSON", json.getString("contentType"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        assertEquals("#{database.host}#", template.getJSONObject("database").getString("host"));
        assertEquals("secret leaf must render as the identical token, no special-casing (FR-42)",
                "#{database.password}#", template.getJSONObject("database").getString("password"));
    }

    @Test
    public void commonPage_doRenderTemplate_noActiveVersionReturnsClearErrorNotException() throws Exception {
        // A Config Set that has never been created/activated: getConfigSet() is null, so
        // getActiveVersion() is null — must return a structured ok:false error, never
        // throw an exception page (FR-40's "disabled-state message, not an exception page").
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest21-nonexistent/renderTemplate");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("no active version must be reported, not thrown as a server error", json.getBoolean("ok"));
        assertNotNull(json.getString("error"));
    }

    @Test
    public void commonEditPage_rendersGenerateTemplateButtonAndBanner() throws Exception {
        seedCommon("uitest25", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest25/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("global page must render the Generate Template toggle button (FR-40)",
                html.contains("id=\"modeGenerateBtn\""));
        assertTrue("global page must render the ACTIVE-version banner text (FR-16)",
                html.contains("NOT this box's unsaved edits"));
    }

    @Test
    public void commonEditPage_generateTemplateButtonDisabledWhenNoActiveVersion() throws Exception {
        // A Config Set page rendered for a project key that has never been saved: isExists() is
        // false, so isTemplateAvailable() is false — the button must render disabled with the
        // "no active version yet" label (FR-40), never silently no-op on click.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest26-nonexistent/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("Generate Template button must render disabled with an explanatory label (FR-40)",
                html.contains("disabled=\"disabled\"") && html.contains("no active version yet"));
    }

    @Test
    public void commonEditPage_inlineScriptsAreSyntacticallyValidJs_withGenerateTemplateCode() throws Exception {
        // Regression guard for the newly added switchToGenerateMode/copyGeneratedTemplate JS,
        // matching the existing real-JS-engine syntax-check pattern for this test class.
        seedCommon("uitest29", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest29/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("switchToGenerateMode"));
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (generate-template JS)", html);
    }

    // --- Compare-mode state machine and busy/disabled states (FR-45/46/47, 2026-09-01 audit) ----

    @Test
    public void commonEditPage_rendersExplicitBackToEditingButtonInCompareBanner() throws Exception {
        // FR-45(a): a "Back to editing" button MUST exist alongside "Load into editor" in the
        // compare banner, distinct from it, wired to the same switchToEditMode() no-op-exit
        // function the re-click-the-selected-row path (FR-45(b)) already uses.
        seedCommon("uitest40", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest40/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("compare banner must render an explicit 'Back to editing' button (FR-45a)",
                html.contains("id=\"backToEditingBtn\""));
        assertTrue("'Back to editing' must be wired to switchToEditMode(), the same no-op-exit "
                + "function the re-click-same-row path already calls (FR-45b)",
                html.contains("onclick=\"switchToEditMode();\""));
        assertTrue("'Load into editor' must remain present and distinct (FR-45c)",
                html.contains("id=\"loadComparedBtn\""));
    }

    @Test
    public void commonEditPage_activateButtonDisabledOnTheAlreadyActiveRow() throws Exception {
        // FR-47: the Activate button for the currently-active version MUST render disabled at the
        // control level, with an explanatory title — not merely a harmless server-side no-op.
        ConfigSet common = seedCommon("uitest42", "{\"a\":1}", "v1");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloaded = repository.findCommon("uitest42");
        reloaded.addVersion("{\"a\":2}", "v2", "seed-author", 2L);
        repository.save(reloaded);
        int activeVersion = reloaded.getActiveVersionNumber();

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest42/");
        String html = page.getWebResponse().getContentAsString();

        Pattern activeRow = Pattern.compile(
                "id=\"historyRow-" + activeVersion + "\"[\\s\\S]*?</tr>");
        Matcher matcher = activeRow.matcher(html);
        assertTrue("must find the active version's history row in the rendered HTML", matcher.find());
        String rowHtml = matcher.group();
        assertTrue("the already-active row's Activate button must render disabled (FR-47)",
                rowHtml.contains("disabled=\"disabled\""));
        assertTrue("the disabled Activate button must explain why",
                rowHtml.contains("Already the active version"));

        int inactiveVersion = activeVersion == 1 ? 2 : 1;
        Pattern inactiveRow = Pattern.compile(
                "id=\"historyRow-" + inactiveVersion + "\"[\\s\\S]*?</tr>");
        Matcher inactiveMatcher = inactiveRow.matcher(html);
        assertTrue(inactiveMatcher.find());
        assertFalse("a non-active row's Activate button must remain enabled",
                inactiveMatcher.group().contains("disabled=\"disabled\""));
    }

    @Test
    public void commonEditPage_inlineScriptsAreSyntacticallyValidJs_withBusyDisableGuardCode() throws Exception {
        // Regression guard for the newly added FR-46 busy/disable guard JS (prepareSubmit's
        // save-button disable + activateVersion's all-rows-disable), matching the existing
        // real-JS-engine syntax-check pattern for this test class.
        seedCommon("uitest44", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest44/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("prepareSubmit must disable both save buttons (FR-46)",
                html.contains("document.getElementById('saveBtn').disabled = true"));
        assertTrue("activateVersion must disable every Activate button, not just the clicked one (FR-46)",
                html.contains("function setActivateButtonsDisabled"));
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (busy-disable-guard JS)", html);
    }

    @Test
    public void globalListPage_inlineScriptsAreSyntacticallyValidJs() throws Exception {
        seedCommon("uitest19", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates");
        Matcher matcher = INLINE_SCRIPT.matcher(page.getWebResponse().getContentAsString());
        boolean anyNonEmpty = false;
        while (matcher.find()) {
            if (matcher.group(1) != null && !matcher.group(1).trim().isEmpty()) {
                anyNonEmpty = true;
                break;
            }
        }
        // The common/env list pages currently have no plugin-authored inline <script> content
        // (confirmed by source inspection) — this test exists so that if one is ever added, the
        // next assertion below (reusing the same real-JS-engine check as the edit pages) starts
        // actually gating it instead of this class silently never having covered list pages.
        if (anyNonEmpty) {
            assertAllInlineScriptsAreSyntacticallyValidJs(
                    "common list page", page.getWebResponse().getContentAsString());
        }
    }

    // --- Multi-base config chain UI (FR-51/FR-55/FR-56/FR-57/FR-58) ------------------------

    @Test
    public void jobPage_brandNewJob_baseChainEditorSeedsAnEmptyArray_noSelfReferenceDefault() throws Exception {
        // Rewritten (2026-09-14 full removal of ConfigSetRole.ENV/EnvConfigSetPage): the OLD
        // env-role behavior (FR-56) defaulted a brand-new env Config Set's chain editor to one
        // self-referencing ACTIVE row, because an env Config Set is structurally paired to its own
        // COMMON project. A Job has no such paired project (job-scoped-config.md, JobConfigTemplateVersion's
        // own javadoc: "deliberately no explicitlyStandalone field... a job has no implicit
        // self-reference to guard against"), so ConfigTemplatesJobAction#getBaseChainSeedJsonForScript()
        // seeds an EMPTY array for a brand-new job instead — this is the NEW correct behavior this
        // test now asserts, not a silently dropped FR-56 default.
        seedCommon("uitest51", "{\"a\":1}", "seed");

        hudson.model.FreeStyleProject job = jenkins.createFreeStyleProject("uitest51-job");
        ConfigTemplatesJobAction action = new ConfigTemplatesJobAction(job);
        String seedLiteral = action.getBaseChainSeedJsonForScript();
        String jsonArrayText = new com.google.gson.Gson().fromJson(seedLiteral, String.class);
        com.google.gson.JsonArray seededChain = com.google.gson.JsonParser.parseString(jsonArrayText).getAsJsonArray();

        assertEquals("a brand-new job must seed an EMPTY base-chain array — no self-reference "
                        + "default exists for the job-scoped model",
                0, seededChain.size());
    }

    // ---- Multi-format content (FR-59-FR-70) ----

    @Test
    public void rootListPage_rendersTypeColumnForEachContentType() throws Exception {
        seedCommon("uitest60", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet xmlSet = new ConfigSet("uitest60-xml", ConfigSetRole.COMMON, null, "Common", ContentType.XML);
        xmlSet.addVersion("<root><a>1</a></root>", "seed", "seed-author", 1L);
        repository.save(xmlSet);

        HtmlPage page = jenkins.createWebClient().goTo("configTemplates");
        String text = page.asNormalizedText();
        assertTrue("Type column must render JSON for a JSON Config Set (FR-67)", text.contains("JSON"));
        assertTrue("Type column must render XML for an XML Config Set (FR-67)", text.contains("XML"));
    }

    @Test
    public void doSave_firstSave_withContentTypeParameter_persistsChosenType() throws Exception {
        // Raw WebRequest POSTs (no HtmlForm involved) must supply a valid crumb explicitly, or
        // disable the crumb issuer for this test — same requirement as any other CSRF-protected
        // Stapler POST endpoint (https://www.jenkins.io/doc/developer/security/csrf-protection/).
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        // Save redirects to the edit page, which loads the vendored Monaco AMD loader — its ES2015+
        // syntax is not parseable by HtmlUnit's embedded legacy JS engine (same limitation already
        // documented on editPage_loadsActiveVersionContent above); this test only needs the
        // persisted-content-type assertion below, not any client-side behavior.
        wc.getOptions().setJavaScriptEnabled(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest61/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "<root><a>1</a></root>"),
                new org.htmlunit.util.NameValuePair("note", "first save, XML"),
                new org.htmlunit.util.NameValuePair("contentType", "XML")
        ));
        wc.getPage(request);

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet saved = repository.findCommon("uitest61");
        assertNotNull(saved);
        assertEquals(ContentType.XML, saved.getContentType());
    }

    @Test
    public void doSave_contentTypeIsImmutableAfterFirstVersion() throws Exception {
        seedCommon("uitest62", "{\"a\":1}", "seed"); // JSON, per seedCommon

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false); // see doSave_firstSave_withContentTypeParameter... above

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest62/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        // Attempting to smuggle a different contentType on a second save must have no effect —
        // the field is only meaningful when getConfigSet() == null (FR-59).
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "{\"a\":2}"),
                new org.htmlunit.util.NameValuePair("note", "second save"),
                new org.htmlunit.util.NameValuePair("contentType", "XML")
        ));
        wc.getPage(request);

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals(ContentType.JSON, repository.findCommon("uitest62").getContentType());
    }

    @Test
    public void doCheckContentSyntax_and_doReformatContent_noCollisionWithExistingEndpoints() throws Exception {
        seedCommon("uitest64", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();

        Page validPage = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest64/checkContentSyntax?content="
                + java.net.URLEncoder.encode("<root><a>1</a></root>", "UTF-8") + "&contentType=XML");
        JSONObject validJson = JSONObject.fromObject(validPage.getWebResponse().getContentAsString());
        assertTrue("doCheckContentSyntax must accept syntactically valid XML", validJson.getBoolean("ok"));

        Page invalidPage = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest64/checkContentSyntax?content="
                + java.net.URLEncoder.encode("<root><a></root>", "UTF-8") + "&contentType=XML");
        JSONObject invalidJson = JSONObject.fromObject(invalidPage.getWebResponse().getContentAsString());
        assertFalse("doCheckContentSyntax must reject malformed XML", invalidJson.getBoolean("ok"));

        Page formatPage = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest64/reformatContent?content="
                + java.net.URLEncoder.encode("a: 1", "UTF-8") + "&contentType=YAML");
        JSONObject formatJson = JSONObject.fromObject(formatPage.getWebResponse().getContentAsString());
        assertTrue("doReformatContent must succeed for valid YAML", formatJson.getBoolean("ok"));
        assertNotNull(formatJson.getString("formatted"));
    }

    // --- Base-chain accordion / type-filtered picker / three-pane layout / discard / FR-89 --------
    // (2026-09-03 tech-lead-contracted redesign pass)

    @Test
    public void getCommonVersionCatalogJsonForScript_exposesVersionsAndTypeByProjectMaps() throws Exception {
        // FR-72: the payload shape changed from a bare {projectKey: [...]} object to
        // {versionsByProject: {...}, typeByProject: {...}} — proves the new sibling map exists with
        // the same key set, for the client-side type filter.
        seedCommon("uitest74", "{\"a\":1}", "seed");
        hudson.model.FreeStyleProject job = jenkins.createFreeStyleProject("uitest74-job");
        ConfigTemplatesJobAction action = new ConfigTemplatesJobAction(job);
        String literal = action.getCommonVersionCatalogJsonForScript();
        String jsonText = new com.google.gson.Gson().fromJson(literal, String.class);
        com.google.gson.JsonObject wrapper = com.google.gson.JsonParser.parseString(jsonText).getAsJsonObject();
        assertTrue(wrapper.has("versionsByProject"));
        assertTrue(wrapper.has("typeByProject"));
        assertEquals("JSON", wrapper.getAsJsonObject("typeByProject").get("uitest74").getAsString());
    }

    // --- FR-105: "choose content type at creation" moved to the root list page --------------

    @Test
    public void rootListPage_rendersNewConfigSetContentTypePicker_withJsonPreselectedByDefault() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates");
        String html = page.getWebResponse().getContentAsString();

        assertTrue("root page must render the new-project content-type radio group (FR-105)",
                html.contains("id=\"newContentTypeGroup\""));
        assertTrue("root page must reuse the same 'choose once — locked forever' helper wording",
                html.contains("choose once") && html.contains("locked forever after the first Save"));

        Matcher jsonRadio = Pattern.compile("id=\"newContentTypeJson\"[^>]*").matcher(html);
        assertTrue(jsonRadio.find());
        assertTrue("JSON radio must render pre-checked by default", jsonRadio.group().contains("checked=\"checked\""));

        Matcher xmlRadio = Pattern.compile("id=\"newContentTypeXml\"[^>]*").matcher(html);
        assertTrue(xmlRadio.find());
        assertFalse("XML radio must not be pre-checked by default", xmlRadio.group().contains("checked"));

        Matcher yamlRadio = Pattern.compile("id=\"newContentTypeYaml\"[^>]*").matcher(html);
        assertTrue(yamlRadio.find());
        assertFalse("YAML radio must not be pre-checked by default", yamlRadio.group().contains("checked"));
    }

    @Test
    public void rootListPage_clickingCreateWithXmlSelected_navigatesWithContentTypeCarriedThrough() throws Exception {
        // The destination Common page's Monaco AMD loader uses ES2015+ syntax the embedded legacy
        // HtmlUnit JS engine can't execute (same limitation documented on
        // editPage_loadsActiveVersionContent above) — script errors during that follow-on page load
        // are swallowed rather than aborting navigation, so the resulting page's URL (this test's
        // only assertion target) is still reliably observable.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        HtmlPage page = wc.goTo("configTemplates");

        HtmlTextInput projectKeyInput = (HtmlTextInput) page.getElementById("newProjectKey");
        projectKeyInput.setValueAttribute("uitest90");
        HtmlRadioButtonInput xmlRadio = (HtmlRadioButtonInput) page.getElementById("newContentTypeXml");
        xmlRadio.setChecked(true);

        HtmlButton createButton = (HtmlButton) page.getElementById("newConfigSetCreate");
        Page result = createButton.click();

        String url = result.getUrl().toString();
        assertTrue("clicking create must navigate to the new project's common editor: " + url,
                url.contains("/uitest90/"));
        assertTrue("the selected content type must be carried through as a query parameter (FR-105): " + url,
                url.contains("contentType=XML"));
    }

    @Test
    public void rootListPage_clickingCreateWithEmptyProjectKey_preservesExistingNavigationShape() throws Exception {
        // Pre-existing behavior (unchanged by FR-105): an empty projectKey field was never
        // client-side-validated — the button's onclick handler always navigated regardless, landing
        // on ".../configTemplates//" (an empty, encodeURIComponent-produced path segment).
        // This test locks in that the FR-105 change is strictly additive (only appends
        // ?contentType=...) and introduces no new validation/regression for the empty-field case.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        HtmlPage page = wc.goTo("configTemplates");

        HtmlButton createButton = (HtmlButton) page.getElementById("newConfigSetCreate");
        Page result = createButton.click();

        String url = result.getUrl().toString();
        assertTrue("an empty projectKey must still produce the same empty-segment navigation shape "
                + "as before this change: " + url, url.contains("configTemplates//"));
        assertTrue("the default JSON content type must still be appended: " + url,
                url.contains("contentType=JSON"));
    }

    @Test
    public void commonEditPage_preselectsCarriedThroughContentType_whenProjectDoesNotYetExist() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest91-new/?contentType=XML");
        String html = page.getWebResponse().getContentAsString();

        assertTrue("the picker must still render unlocked for a brand-new project",
                html.contains("id=\"contentTypeUnlockedGroup\""));
        Matcher xmlRadio = Pattern.compile("value=\"XML\"[^>]*").matcher(html);
        assertTrue(xmlRadio.find());
        assertTrue("the XML radio must render pre-selected from the carried-through ?contentType= param (FR-105)",
                xmlRadio.group().contains("checked=\"checked\""));

        Matcher jsonRadio = Pattern.compile("value=\"JSON\"[^>]*").matcher(html);
        assertTrue(jsonRadio.find());
        assertFalse("JSON must no longer be the pre-selected radio once XML was carried through",
                jsonRadio.group().contains("checked=\"checked\""));
    }

    @Test
    public void commonEditPage_ignoresIncomingContentTypeParam_whenProjectAlreadyExists() throws Exception {
        // FR-59's immutability: an existing (locked) Config Set's committed type is authoritative —
        // an incoming ?contentType=... must never be honored once a first version has been saved.
        ConfigSet common = seedCommon("uitest92", "{\"a\":1}", "seed"); // JSON, per seedCommon
        assertEquals(ContentType.JSON, common.getContentType());

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest92/?contentType=XML");
        String html = page.getWebResponse().getContentAsString();

        assertTrue("an existing project's picker must render locked, never the unlocked radio group",
                html.contains("id=\"contentTypeLockedDisplay\""));
        assertFalse(html.contains("id=\"contentTypeUnlockedGroup\""));
        Matcher lockedDisplay = Pattern.compile("id=\"contentTypeLockedDisplay\">([^<]*)").matcher(html);
        assertTrue(lockedDisplay.find());
        assertTrue("the locked display must still show the originally-committed JSON type, not the "
                + "ignored incoming XML param: " + lockedDisplay.group(1),
                lockedDisplay.group(1).trim().startsWith("JSON"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals("the incoming contentType param must have no persistence effect either",
                ContentType.JSON, repository.findCommon("uitest92").getContentType());
    }

    // --- Live manual review fixes (2026-09-04): section reorg (accordion -> framed sections,
    // reordered top-to-bottom), Activate-reloads-editor-state bug, button wording, base-chain
    // dropdown sizing -------------------------------------------------------------------------

    @Test
    public void commonEditPage_sectionsAreFramedNotCollapsible() throws Exception {
        // Consistency pass applied to CommonConfigSetPage too (owner instruction), section ORDER
        // unchanged there — only the accordion-to-framed-section conversion applies.
        seedCommon("uitest104", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest104/");
        String html = page.getWebResponse().getContentAsString();

        assertFalse("Common page's sections must also no longer render as <details> accordions",
                html.contains("<details"));
        assertTrue("Common page's sections must also use Jenkins core's framed-section convention",
                html.contains("jenkins-section"));
    }
}
