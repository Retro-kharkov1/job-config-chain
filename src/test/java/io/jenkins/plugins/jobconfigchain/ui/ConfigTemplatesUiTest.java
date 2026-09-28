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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Stapler/Jelly UI tests for the Milestone-2 admin screens (FR-30–FR-39). Uses
 * {@link JenkinsRule.WebClient} (HtmlUnit), the standard Jenkins UI-testing pattern for
 * JenkinsRule-based tests — see https://www.jenkins.io/doc/developer/testing/ ("Testing views").
 *
 * <p>Each test seeds its own uniquely-named Config Set (no shared cross-test state) to keep tests
 * isolated per the pom.xml's own notes on Windows JenkinsRule fork/AV flakiness.</p>
 */
@WithJenkins
public class ConfigTemplatesUiTest {

    private JenkinsRule jenkins;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

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
        assertFalse(stillATopNavAction, "the plugin's entry point must no longer be a top-nav RootAction");
    }

    @Test
    public void rootAction_isRegisteredAsAManagementLinkUnderToolsCategory() throws Exception {
        ConfigTemplatesRootAction managementLink = ManagementLink.all().stream()
                .filter(ConfigTemplatesRootAction.class::isInstance)
                .map(ConfigTemplatesRootAction.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(managementLink, "the plugin must register itself as a ManagementLink extension");
        assertEquals("configTemplates", managementLink.getUrlName());
        assertEquals("Config Templates", managementLink.getDisplayName());
        assertEquals(ManagementLink.Category.TOOLS, managementLink.getCategory(), "closest fit in ManagementLink's fixed Category enum (see class javadoc)");
    }

    @Test
    public void managePage_listsTheConfigTemplatesEntry() throws Exception {
        HtmlPage page = jenkins.createWebClient().goTo("manage");
        assertTrue(page.asNormalizedText().contains("Config Templates"), "the Manage Jenkins page must list an entry for this plugin");
    }

    @Test
    public void rootUrl_stillResolvesToTheSameConfigTemplatesScreen_viaManagementLinkNowInsteadOfRootAction()
            throws Exception {
        // Jenkins' root object resolves a bare /<urlName> token against both its RootAction AND its
        // ManagementLink extensions (Jenkins#getManagementLinks()/ManagementLink.all()) — confirming
        // the URL space is unchanged even though the extension point backing it changed.
        seedCommon("uitest70", "{\"a\":1}", "seed");

        HtmlPage page = jenkins.createWebClient().goTo("configTemplates");
        assertTrue(page.asNormalizedText().contains("uitest70"), "existing /configTemplates URL must still resolve to the same list page");
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

        assertTrue(html.contains("configTemplates/uitest150/\">uitest150<"), "row link must point at /configTemplates/<key>/ with no /common suffix");
        assertFalse(html.contains("uitest150-common"), "row link text must no longer carry the retired -common suffix");
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

        assertTrue(html.contains("db.internal"), "the root project URL must render the seeded active version's content, proving "
                + "the common editor (not a stub landing page) is served here");
        assertTrue(html.contains("id=\"versionHistoryDetails\""), "the root project URL must render the common editor's version-history section");
        assertTrue(html.contains("id=\"modeGenerateBtn\""), "the root project URL must render the Generate Template action");
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
        assertEquals(404, result.getWebResponse().getStatusCode(), "the old /common URL segment must plainly 404, not redirect or render a stub");
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
        assertFalse(result.getWebResponse().getStatusCode() == 200, "invalid JSON must not be accepted (FR-33)");

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals(null, repository.findCommon("uitest4"), "no Config Set must have been persisted from the rejected save");
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
        URL activateUrl = new URL(wc.getContextPath() + "configTemplates/uitest5/activateVersion");
        WebRequest activateRequest = new WebRequest(activateUrl, HttpMethod.POST);
        activateRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("version", String.valueOf(v2))
        ));
        Page result = wc.getPage(activateRequest);
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
        URL diffUrl = new URL(wc.getContextPath() + "configTemplates/uitest8/diffVersions");
        WebRequest diffRequest = new WebRequest(diffUrl, HttpMethod.POST);
        diffRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("version", String.valueOf(v2))
        ));
        Page result = wc.getPage(diffRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals(v2, json.getInt("version"));
        assertEquals("{\"a\":2}", json.getString("content"));
    }

    @Test
    public void doCompareVersions_reportsErrorForMissingVersionWithoutThrowing() throws Exception {
        seedCommon("uitest9", "{\"a\":1}", "v1");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL diffUrl = new URL(wc.getContextPath() + "configTemplates/uitest9/diffVersions");
        WebRequest diffRequest = new WebRequest(diffUrl, HttpMethod.POST);
        diffRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("version", "99")
        ));
        Page result = wc.getPage(diffRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse(json.getBoolean("ok"), "a nonexistent version number must be reported, not thrown as a server error");
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
        assertFalse(result.getWebResponse().getStatusCode() == 200, "a real value at a manifest-declared secret path must be rejected (FR-14/OQ-1)");

        ConfigSetRepository verify = new ConfigSetRepository();
        ConfigSet reloaded = verify.findCommon("uitest7");
        assertEquals(1, reloaded.getVersions().size(), "no new version must have been persisted from the rejected save");
        assertFalse(reloaded.getActiveVersion().getContentJson().contains("hunter2-real-secret"), "the real secret text must never have been written to storage");
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
        URL registerUrl = new URL(wc.getContextPath() + "configTemplates/uitest10/registerSecret");
        WebRequest registerRequest = new WebRequest(registerUrl, HttpMethod.POST);
        registerRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("path", "database.password"),
                new org.htmlunit.util.NameValuePair("credentialId", "does-not-exist")
        ));
        Page result = wc.getPage(registerRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse(json.getBoolean("ok"), "a credential ID with no matching real Jenkins credential must be rejected (OQ-1)");
        assertNotNull(json.getString("error"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertTrue(repository.findCommon("uitest10").getSecretsManifest().isEmpty(), "no manifest entry must have been persisted for the rejected credential ID");
    }

    @Test
    public void doAddSecret_acceptsARealExistingCredentialAndPersistsTheMapping() throws Exception {
        seedRealCredential("uitest11-real-cred");
        seedCommon("uitest11", "{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL registerUrl = new URL(wc.getContextPath() + "configTemplates/uitest11/registerSecret");
        WebRequest registerRequest = new WebRequest(registerUrl, HttpMethod.POST);
        registerRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("path", "database.password"),
                new org.htmlunit.util.NameValuePair("credentialId", "uitest11-real-cred")
        ));
        Page result = wc.getPage(registerRequest);
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
        URL registerUrl = new URL(wc.getContextPath() + "configTemplates/uitest13/registerSecret");
        WebRequest registerRequest = new WebRequest(registerUrl, HttpMethod.POST);
        registerRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("path", "database.password"),
                new org.htmlunit.util.NameValuePair("credentialId", "uitest13-real-cred")
        ));
        Page result = wc.getPage(registerRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse(json.getBoolean("ok"), "must reject declaring a path secret when the active version already holds a real value");
        assertNotNull(json.getString("error"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertTrue(repository.findCommon("uitest13").getSecretsManifest().isEmpty(), "no manifest entry must have been persisted for the rejected retroactive declaration");
    }

    @Test
    public void doAddSecret_acceptsRetroactiveDeclareWhenActiveVersionAlreadyHoldsThePlaceholder() throws Exception {
        seedRealCredential("uitest14-real-cred");
        seedCommon("uitest14", "{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL registerUrl = new URL(wc.getContextPath() + "configTemplates/uitest14/registerSecret");
        WebRequest registerRequest = new WebRequest(registerUrl, HttpMethod.POST);
        registerRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("path", "database.password"),
                new org.htmlunit.util.NameValuePair("credentialId", "uitest14-real-cred")
        ));
        Page result = wc.getPage(registerRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"), "a path already holding the placeholder must be acceptable to newly declare secret");

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
        URL unbindUrl = new URL(wc.getContextPath() + "configTemplates/uitest31/unbindSecret");
        WebRequest unbindRequest = new WebRequest(unbindUrl, HttpMethod.POST);
        unbindRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("path", "database.password")
        ));
        Page result = wc.getPage(unbindRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertTrue(repository.findCommon("uitest31").getSecretsManifest().isEmpty(), "the manifest entry must no longer be present after removal");
    }

    @Test
    public void doUnbindSecret_reportsErrorForAPathNeverBoundWithoutThrowing() throws Exception {
        seedCommon("uitest32", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL unbindUrl = new URL(wc.getContextPath() + "configTemplates/uitest32/unbindSecret");
        WebRequest unbindRequest = new WebRequest(unbindUrl, HttpMethod.POST);
        unbindRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("path", "never.bound")
        ));
        Page result = wc.getPage(unbindRequest);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse(json.getBoolean("ok"), "unbinding a path that was never bound must be reported, not thrown as a server error");
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
        assertTrue(html.contains("ctsync-remove-secret-btn"), "a manifest row must render a delete control (CRUD completion)");
        assertTrue(html.contains("data-secret-path=\"database.password\""), "the delete control must carry the dotted path so removeSecret() can read it");
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
        assertTrue(fetchExternalScripts(wc, html).contains("function removeSecret"), "removeSecret is now defined in the external index.js, not inline (CSP migration)");
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (remove-secret JS)", wc, html);
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
        // CSP migration: this logic now lives in the external index.js, not inline — see
        // fetchExternalScripts.
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("window.notificationBar.show("), "save success/error must go through the native notificationBar toast");
        assertFalse(html.contains("id=\"saveBanner\""), "the old inline save banner element must be removed, not just unused");
        assertFalse(js.contains("function showSaveBanner"), "the old showSaveBanner helper must be gone");
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

    private static final Pattern EXTERNAL_SCRIPT_SRC =
            Pattern.compile("<script src=\"([^\"]+)\"");

    /**
     * CSP migration (see https://www.jenkins.io/doc/developer/security/csp/): every
     * plugin-authored {@code <script>} on these pages is now external ({@code <script src="...">},
     * loaded via {@code h.getViewResource}) rather than inline — this fleet's own inline-script
     * syntax guard now also fetches and concatenates the content of every such external script
     * (skipping the vendored Monaco loader under {@code /monaco/}, which is not plugin-authored and
     * uses ES2015+ syntax the same Nashorn engine used here cannot parse either).
     */
    private String fetchExternalScripts(JenkinsRule.WebClient wc, String html) throws Exception {
        Matcher m = EXTERNAL_SCRIPT_SRC.matcher(html);
        StringBuilder combined = new StringBuilder();
        while (m.find()) {
            String src = m.group(1);
            if (src.contains("/monaco/")) {
                continue; // vendored AMD loader — not plugin-authored, not in scope for this guard
            }
            URL url = new URL(new URL(wc.getContextPath()), src);
            combined.append(wc.getPage(url).getWebResponse().getContentAsString()).append('\n');
        }
        return combined.toString();
    }

    /**
     * Extracts every inline (non-{@code src=}) {@code <script>} body from rendered HTML PLUS the
     * fetched content of every external, plugin-authored {@code <script src="...">} it references
     * (see {@link #fetchExternalScripts}), and compiles each with a real JS engine, failing with a
     * clear message per broken block if any fails to parse. Asserts at least one non-empty script
     * was actually found, so this guard cannot silently pass by finding nothing to check.
     */
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
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void commonEditPage_inlineScriptsAreSyntacticallyValidJs() throws Exception {
        seedCommon("uitest16", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest16/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "common edit page", wc, page.getWebResponse().getContentAsString());
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
                "common edit page (multiline JSON seed)", wc, commonPage.getWebResponse().getContentAsString());
    }

    // --- Generate Template (FR-15/16, FR-40-44) -------------------------------------------

    @Test
    public void commonPage_doRenderTemplate_returnsTokenizedActiveVersionContent() throws Exception {
        seedCommon("uitest20", "{\"database\":{\"host\":\"db.internal\",\"password\":\""
                + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL renderUrl = new URL(wc.getContextPath() + "configTemplates/uitest20/renderTemplate");
        Page result = wc.getPage(new WebRequest(renderUrl, HttpMethod.POST));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        // §7/FR-64: `template` is now already-serialized text in the resolved contentType, plus a
        // new `contentType` field — parse it here to inspect.
        assertEquals("JSON", json.getString("contentType"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        assertEquals("#{database.host}#", template.getJSONObject("database").getString("host"));
        assertEquals("#{database.password}#", template.getJSONObject("database").getString("password"), "secret leaf must render as the identical token, no special-casing (FR-42)");
    }

    @Test
    public void commonPage_doRenderTemplate_noActiveVersionReturnsClearErrorNotException() throws Exception {
        // A Config Set that has never been created/activated: getConfigSet() is null, so
        // getActiveVersion() is null — must return a structured ok:false error, never
        // throw an exception page (FR-40's "disabled-state message, not an exception page").
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        URL renderUrl = new URL(wc.getContextPath() + "configTemplates/uitest21-nonexistent/renderTemplate");
        Page result = wc.getPage(new WebRequest(renderUrl, HttpMethod.POST));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse(json.getBoolean("ok"), "no active version must be reported, not thrown as a server error");
        assertNotNull(json.getString("error"));
    }

    @Test
    public void commonEditPage_rendersGenerateTemplateButtonAndBanner() throws Exception {
        seedCommon("uitest25", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest25/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"modeGenerateBtn\""), "global page must render the Generate Template toggle button (FR-40)");
        assertTrue(html.contains("NOT this box's unsaved edits"), "global page must render the ACTIVE-version banner text (FR-16)");
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
        assertTrue(html.contains("disabled=\"disabled\"") && html.contains("no active version yet"), "Generate Template button must render disabled with an explanatory label (FR-40)");
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
        assertTrue(fetchExternalScripts(wc, html).contains("switchToGenerateMode"), "switchToGenerateMode is now defined in the external index.js (CSP migration)");
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (generate-template JS)", wc, html);
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
        assertTrue(html.contains("id=\"backToEditingBtn\""), "compare banner must render an explicit 'Back to editing' button (FR-45a)");
        // CSP migration: no inline onclick attribute any more — backToEditingBtn is wired to
        // switchToEditMode() in the external index.js instead (same function the re-click-same-row
        // path already calls); see CommonConfigSetPage/index.js's wiring block.
        assertTrue(fetchExternalScripts(wc, html).contains("on('backToEditingBtn', 'click', switchToEditMode)"), "'Back to editing' must be wired to switchToEditMode(), the same no-op-exit "
                + "function the re-click-same-row path already calls (FR-45b)");
        assertTrue(html.contains("id=\"loadComparedBtn\""), "'Load into editor' must remain present and distinct (FR-45c)");
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
        assertTrue(matcher.find(), "must find the active version's history row in the rendered HTML");
        String rowHtml = matcher.group();
        assertTrue(rowHtml.contains("disabled=\"disabled\""), "the already-active row's Activate button must render disabled (FR-47)");
        assertTrue(rowHtml.contains("Already the active version"), "the disabled Activate button must explain why");

        int inactiveVersion = activeVersion == 1 ? 2 : 1;
        Pattern inactiveRow = Pattern.compile(
                "id=\"historyRow-" + inactiveVersion + "\"[\\s\\S]*?</tr>");
        Matcher inactiveMatcher = inactiveRow.matcher(html);
        assertTrue(inactiveMatcher.find());
        assertFalse(inactiveMatcher.group().contains("disabled=\"disabled\""), "a non-active row's Activate button must remain enabled");
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
        String js = fetchExternalScripts(wc, html);
        assertTrue(js.contains("document.getElementById('saveBtn').disabled = true"), "prepareSubmit must disable both save buttons (FR-46)");
        assertTrue(js.contains("function setActivateButtonsDisabled"), "activateVersion must disable every Activate button, not just the clicked one (FR-46)");
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (busy-disable-guard JS)", wc, html);
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
                    "common list page", wc, page.getWebResponse().getContentAsString());
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

        assertEquals(0, seededChain.size(), "a brand-new job must seed an EMPTY base-chain array — no self-reference "
                        + "default exists for the job-scoped model");
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
        assertTrue(text.contains("JSON"), "Type column must render JSON for a JSON Config Set (FR-67)");
        assertTrue(text.contains("XML"), "Type column must render XML for an XML Config Set (FR-67)");
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

        URL checkSyntaxUrl = new URL(wc.getContextPath() + "configTemplates/uitest64/checkContentSyntax");

        WebRequest validRequest = new WebRequest(checkSyntaxUrl, HttpMethod.POST);
        validRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "<root><a>1</a></root>"),
                new org.htmlunit.util.NameValuePair("contentType", "XML")
        ));
        Page validPage = wc.getPage(validRequest);
        JSONObject validJson = JSONObject.fromObject(validPage.getWebResponse().getContentAsString());
        assertTrue(validJson.getBoolean("ok"), "doCheckContentSyntax must accept syntactically valid XML");

        WebRequest invalidRequest = new WebRequest(checkSyntaxUrl, HttpMethod.POST);
        invalidRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "<root><a></root>"),
                new org.htmlunit.util.NameValuePair("contentType", "XML")
        ));
        Page invalidPage = wc.getPage(invalidRequest);
        JSONObject invalidJson = JSONObject.fromObject(invalidPage.getWebResponse().getContentAsString());
        assertFalse(invalidJson.getBoolean("ok"), "doCheckContentSyntax must reject malformed XML");

        URL reformatUrl = new URL(wc.getContextPath() + "configTemplates/uitest64/reformatContent");
        WebRequest formatRequest = new WebRequest(reformatUrl, HttpMethod.POST);
        formatRequest.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "a: 1"),
                new org.htmlunit.util.NameValuePair("contentType", "YAML")
        ));
        Page formatPage = wc.getPage(formatRequest);
        JSONObject formatJson = JSONObject.fromObject(formatPage.getWebResponse().getContentAsString());
        assertTrue(formatJson.getBoolean("ok"), "doReformatContent must succeed for valid YAML");
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

        assertTrue(html.contains("id=\"newContentTypeGroup\""), "root page must render the new-project content-type radio group (FR-105)");
        assertTrue(html.contains("choose once") && html.contains("locked forever after the first Save"), "root page must reuse the same 'choose once — locked forever' helper wording");

        Matcher jsonRadio = Pattern.compile("id=\"newContentTypeJson\"[^>]*").matcher(html);
        assertTrue(jsonRadio.find());
        assertTrue(jsonRadio.group().contains("checked=\"checked\""), "JSON radio must render pre-checked by default");

        Matcher xmlRadio = Pattern.compile("id=\"newContentTypeXml\"[^>]*").matcher(html);
        assertTrue(xmlRadio.find());
        assertFalse(xmlRadio.group().contains("checked"), "XML radio must not be pre-checked by default");

        Matcher yamlRadio = Pattern.compile("id=\"newContentTypeYaml\"[^>]*").matcher(html);
        assertTrue(yamlRadio.find());
        assertFalse(yamlRadio.group().contains("checked"), "YAML radio must not be pre-checked by default");
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
        assertTrue(url.contains("/uitest90/"), "clicking create must navigate to the new project's common editor: " + url);
        assertTrue(url.contains("contentType=XML"), "the selected content type must be carried through as a query parameter (FR-105): " + url);
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
        assertTrue(url.contains("configTemplates//"), "an empty projectKey must still produce the same empty-segment navigation shape "
                + "as before this change: " + url);
        assertTrue(url.contains("contentType=JSON"), "the default JSON content type must still be appended: " + url);
    }

    @Test
    public void commonEditPage_preselectsCarriedThroughContentType_whenProjectDoesNotYetExist() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest91-new/?contentType=XML");
        String html = page.getWebResponse().getContentAsString();

        assertTrue(html.contains("id=\"contentTypeUnlockedGroup\""), "the picker must still render unlocked for a brand-new project");
        Matcher xmlRadio = Pattern.compile("value=\"XML\"[^>]*").matcher(html);
        assertTrue(xmlRadio.find());
        assertTrue(xmlRadio.group().contains("checked=\"checked\""), "the XML radio must render pre-selected from the carried-through ?contentType= param (FR-105)");

        Matcher jsonRadio = Pattern.compile("value=\"JSON\"[^>]*").matcher(html);
        assertTrue(jsonRadio.find());
        assertFalse(jsonRadio.group().contains("checked=\"checked\""), "JSON must no longer be the pre-selected radio once XML was carried through");
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

        assertTrue(html.contains("id=\"contentTypeLockedDisplay\""), "an existing project's picker must render locked, never the unlocked radio group");
        assertFalse(html.contains("id=\"contentTypeUnlockedGroup\""));
        Matcher lockedDisplay = Pattern.compile("id=\"contentTypeLockedDisplay\">([^<]*)").matcher(html);
        assertTrue(lockedDisplay.find());
        assertTrue(lockedDisplay.group(1).trim().startsWith("JSON"), "the locked display must still show the originally-committed JSON type, not the "
                + "ignored incoming XML param: " + lockedDisplay.group(1));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals(ContentType.JSON, repository.findCommon("uitest92").getContentType(), "the incoming contentType param must have no persistence effect either");
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

        assertFalse(html.contains("<details"), "Common page's sections must also no longer render as <details> accordions");
        assertTrue(html.contains("jenkins-section"), "Common page's sections must also use Jenkins core's framed-section convention");
    }
}
