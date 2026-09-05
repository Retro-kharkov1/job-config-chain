package io.github.retrokharkov1.configtemplatesync.ui;

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
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.model.PinMode;
import io.github.retrokharkov1.configtemplatesync.model.SecretPlaceholder;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
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
import java.util.Collections;
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

    private ConfigSet seedEnv(String projectKey, String environment, String patchJson, String note) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet(projectKey, ConfigSetRole.ENV, environment, "Env", ContentType.JSON);
        int v = env.addVersion(patchJson, note, "seed-author", 1L);
        env.activate(v);
        repository.save(env);
        return env;
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
                page.asNormalizedText().contains("uitest70-common"));
    }

    @Test
    public void globalListPage_rendersSeededCommonConfigSets() throws Exception {
        seedCommon("uitest1", "{\"a\":1}", "seed");

        HtmlPage page = jenkins.createWebClient().goTo("configTemplates");
        assertTrue(page.asNormalizedText().contains("uitest1-common"));
    }

    @Test
    public void envListPage_rendersSeededEnvConfigSets() throws Exception {
        seedCommon("uitest2", "{\"a\":1}", "seed");
        seedEnv("uitest2", "dev", "{}", "seed");

        HtmlPage page = jenkins.createWebClient().goTo("configTemplates/uitest2/");
        assertTrue(page.asNormalizedText().contains("dev"));
    }

    @Test
    public void editPage_loadsActiveVersionContent() throws Exception {
        seedCommon("uitest3", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        // JS disabled: the vendored Monaco AMD loader uses ES2015+ syntax the embedded legacy
        // HtmlUnit JS engine can't execute — irrelevant here, this assertion only needs the raw
        // server-rendered HTML (the seeded active version's JSON is inlined server-side).
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest3/common/");
        assertTrue(page.getWebResponse().getContentAsString().contains("db.internal"));
    }

    @Test
    public void doSave_rejectsInvalidJson_bothWaysGuarded() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest4/common/submitSave");
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
                wc.getContextPath() + "configTemplates/uitest5/common/activateVersion?version=" + v2);
        String body = result.getWebResponse().getContentAsString();
        JSONObject json = JSONObject.fromObject(body);
        assertTrue(json.getBoolean("ok"));
        assertEquals(v2, json.getInt("active"));

        ConfigSetRepository verify = new ConfigSetRepository();
        assertEquals(v2, verify.findCommon("uitest5").getActiveVersionNumber());
    }

    @Test
    public void doPreviewMerge_recomputesOnOverrideEdit_andReportsInvalidWithoutThrowing() throws Exception {
        seedCommon("uitest6", "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}", "seed");
        seedEnv("uitest6", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page valid = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest6/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{\"database\":{\"host\":null}}", "UTF-8"));
        JSONObject validJson = JSONObject.fromObject(valid.getWebResponse().getContentAsString());
        assertTrue(validJson.getBoolean("ok"));
        assertEquals("JSON", validJson.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(validJson.getString("merged"));
        assertFalse("null in overlay must remove the key from the merged result (RFC 7396)",
                merged.getJSONObject("database").has("host"));
        assertEquals(5432, merged.getJSONObject("database").getInt("port"));

        Page invalid = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest6/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{ not valid", "UTF-8"));
        JSONObject invalidJson = JSONObject.fromObject(invalid.getWebResponse().getContentAsString());
        assertFalse("transiently invalid override JSON must be reported, not thrown as a server error",
                invalidJson.getBoolean("ok"));
        assertNotNull(invalidJson.getString("error"));
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
                + "configTemplates/uitest8/common/diffVersions?version=" + v2);
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
                + "configTemplates/uitest9/common/diffVersions?version=99");
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest7/common/submitSave");
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
                + "configTemplates/uitest10/common/registerSecret?path=database.password&credentialId=does-not-exist");
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
                + "configTemplates/uitest11/common/registerSecret?path=database.password&credentialId=uitest11-real-cred");
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
                + "configTemplates/uitest13/common/registerSecret?path=database.password&credentialId=uitest13-real-cred");
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
                + "configTemplates/uitest14/common/registerSecret?path=database.password&credentialId=uitest14-real-cred");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue("a path already holding the placeholder must be acceptable to newly declare secret",
                json.getBoolean("ok"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals("uitest14-real-cred",
                repository.findCommon("uitest14").getSecretsManifest().get("database.password"));
    }

    @Test
    public void envEditPage_doAddSecret_persistsMappingOnTheEnvConfigSetItself() throws Exception {
        // Confirms the env-level page's "Add/update secret path" section (FR-12 applies to env
        // content too) is wired to the SAME doAddSecret contract as the common page, and that it
        // registers the mapping on the ENV Config Set's own manifest, independent of common's.
        seedRealCredential("uitest15-real-cred");
        seedCommon("uitest15", "{\"a\":1}", "seed");
        seedEnv("uitest15", "dev", "{\"database\":{\"password\":\"" + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest15/dev/registerSecret?path=database.password&credentialId=uitest15-real-cred");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals("uitest15-real-cred",
                repository.findEnv("uitest15", "dev").getSecretsManifest().get("database.password"));
        assertTrue("the common Config Set's own manifest must remain untouched",
                repository.findCommon("uitest15").getSecretsManifest().isEmpty());
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
                + "configTemplates/uitest31/common/unbindSecret?path=database.password");
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
                + "configTemplates/uitest32/common/unbindSecret?path=never.bound");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("unbinding a path that was never bound must be reported, not thrown as a server error",
                json.getBoolean("ok"));
        assertNotNull(json.getString("error"));
    }

    @Test
    public void envEditPage_doUnbindSecret_removesMappingOnTheEnvConfigSetItself() throws Exception {
        // Mirrors envEditPage_doAddSecret_persistsMappingOnTheEnvConfigSetItself: confirms the
        // env-level page's delete action is wired to the SAME doUnbindSecret contract as the
        // common page, and mutates only the ENV Config Set's own manifest.
        seedRealCredential("uitest33-real-cred");
        seedCommon("uitest33", "{\"a\":1}", "seed");
        ConfigSet env = seedEnv("uitest33", "dev", "{\"database\":{\"password\":\""
                + SecretPlaceholder.VALUE + "\"}}", "seed");
        env.putSecretManifestEntry("database.password", "uitest33-real-cred");
        new ConfigSetRepository().save(env);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest33/dev/unbindSecret?path=database.password");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));

        ConfigSetRepository repository = new ConfigSetRepository();
        assertTrue("the env Config Set's own manifest entry must be removed",
                repository.findEnv("uitest33", "dev").getSecretsManifest().isEmpty());
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
        HtmlPage page = wc.goTo("configTemplates/uitest34/common/");
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
        HtmlPage page = wc.goTo("configTemplates/uitest35/common/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function removeSecret"));
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (remove-secret JS)", html);
    }

    @Test
    public void envEditPage_inlineScriptsAreSyntacticallyValidJs_withRemoveSecretCode() throws Exception {
        seedCommon("uitest36", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest36", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest36/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function removeSecret"));
        assertAllInlineScriptsAreSyntacticallyValidJs("env edit page (remove-secret JS)", html);
    }

    @Test
    public void envEditPage_rendersASecretCredentialPickerNotFreeText() throws Exception {
        seedRealCredential("uitest12-real-cred");
        seedCommon("uitest12", "{\"a\":1}", "seed");
        seedEnv("uitest12", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest12/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("env page must render a real <select> credential picker (OQ-1), not free text",
                html.contains("id=\"secretCredentialId\""));
        assertTrue("env page's credential picker must list actually-registered credentials",
                html.contains("uitest12-real-cred"));
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
        HtmlPage page = wc.goTo("configTemplates/uitest16/common/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "common edit page", page.getWebResponse().getContentAsString());
    }

    @Test
    public void envEditPage_inlineScriptsAreSyntacticallyValidJs() throws Exception {
        seedCommon("uitest17", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest17", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest17/dev/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "env edit page", page.getWebResponse().getContentAsString());
    }

    @Test
    public void editPages_survivePrettyPrintedMultilineJsonSeedContentWithoutBreakingTheInlineScript() throws Exception {
        // Regression-specific: this is the exact shape that triggered the bug — real newline
        // bytes (not "\n" escape sequences) inside the stored contentJson, as produced by any
        // pretty-printer, plus a quote character to also confirm h.jsStringEscape's quote/
        // backslash handling still round-trips correctly through the new encoding.
        String multilineJson = "{\n  \"database\": {\n    \"host\": \"db.internal\",\n"
                + "    \"note\": \"has a \\\"quoted\\\" word and a </script> look-alike\"\n  }\n}";
        seedCommon("uitest18", multilineJson, "seed");
        seedEnv("uitest18", "dev", multilineJson, "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);

        HtmlPage commonPage = wc.goTo("configTemplates/uitest18/common/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "common edit page (multiline JSON seed)", commonPage.getWebResponse().getContentAsString());

        HtmlPage envPage = wc.goTo("configTemplates/uitest18/dev/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "env edit page (multiline JSON seed)", envPage.getWebResponse().getContentAsString());
    }

    // --- Generate Template (FR-15/16, FR-40-44) -------------------------------------------

    @Test
    public void commonPage_doRenderTemplate_returnsTokenizedActiveVersionContent() throws Exception {
        seedCommon("uitest20", "{\"database\":{\"host\":\"db.internal\",\"password\":\""
                + SecretPlaceholder.VALUE + "\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest20/common/renderTemplate");
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
        // getActiveVersionForTemplate() is null — must return a structured ok:false error, never
        // throw an exception page (FR-40's "disabled-state message, not an exception page").
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest21-nonexistent/common/renderTemplate");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("no active version must be reported, not thrown as a server error", json.getBoolean("ok"));
        assertNotNull(json.getString("error"));
    }

    @Test
    public void envPage_doRenderTemplate_returnsTokenizedEffectiveMergedContent() throws Exception {
        seedCommon("uitest22", "{\"database\":{\"host\":\"db.internal\",\"port\":5432}}", "seed");
        seedEnv("uitest22", "dev", "{\"database\":{\"host\":null}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest22/dev/renderTemplate");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        JSONObject database = template.getJSONObject("database");
        assertFalse("null in the env overlay must remove the key before tokenization (RFC 7396)",
                database.has("host"));
        assertEquals("#{database.port}#", database.getString("port"));
    }

    @Test
    public void envPage_doRenderTemplate_gatedOnlyByCommonActiveVersion_envWithNoActiveVersionStillWorks() throws Exception {
        // FR-15b/tech-lead decision: the env layer's own active version is NOT required — an env
        // Config Set with no active version yet is a legitimate empty overlay.
        seedCommon("uitest23", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest23/dev/renderTemplate");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue("must succeed against common-only content when the env layer has no active version yet",
                json.getBoolean("ok"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        assertEquals("#{database.host}#", template.getJSONObject("database").getString("host"));
    }

    @Test
    public void envPage_doRenderTemplate_noActiveCommonVersionReturnsClearErrorNotException() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest24-nonexistent/dev/renderTemplate");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse(json.getBoolean("ok"));
        assertNotNull(json.getString("error"));
    }

    @Test
    public void commonEditPage_rendersGenerateTemplateButtonAndBanner() throws Exception {
        seedCommon("uitest25", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest25/common/");
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
        HtmlPage page = wc.goTo("configTemplates/uitest26-nonexistent/common/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("Generate Template button must render disabled with an explanatory label (FR-40)",
                html.contains("disabled=\"disabled\"") && html.contains("no active version yet"));
    }

    @Test
    public void envEditPage_rendersGenerateTemplateButtonAboveThreePanelTable() throws Exception {
        seedCommon("uitest27", "{\"a\":1}", "seed");
        seedEnv("uitest27", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest27/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("env page must render a page-level Generate Template button (FR-41)",
                html.contains("id=\"generateTemplateBtn\""));
        assertTrue("env page must render the merge layout grid with its own id, replaced on click (FR-73)",
                html.contains("id=\"mergeLayout\""));
        assertTrue("env page must render the full-width generated-template panel container",
                html.contains("id=\"generatedTemplatePanel\""));
        assertTrue("env page must render the Back-to-3-panel-view affordance",
                html.contains("id=\"backTo3PanelBtn\""));
    }

    @Test
    public void envEditPage_generateTemplateButtonDisabledWhenNoActiveCommonVersion() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest28-nonexistent/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("Generate Template button must render disabled when the common layer has no active version",
                html.contains("disabled=\"disabled\"") && html.contains("no active version yet"));
    }

    @Test
    public void commonEditPage_inlineScriptsAreSyntacticallyValidJs_withGenerateTemplateCode() throws Exception {
        // Regression guard for the newly added switchToGenerateMode/copyGeneratedTemplate JS,
        // matching the existing real-JS-engine syntax-check pattern for this test class.
        seedCommon("uitest29", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest29/common/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("switchToGenerateMode"));
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (generate-template JS)", html);
    }

    @Test
    public void envEditPage_inlineScriptsAreSyntacticallyValidJs_withGenerateTemplateCode() throws Exception {
        seedCommon("uitest30", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest30", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest30/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("showGeneratedTemplateView"));
        assertTrue(html.contains("backTo3PanelView"));
        assertAllInlineScriptsAreSyntacticallyValidJs("env edit page (generate-template JS)", html);
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
        HtmlPage page = wc.goTo("configTemplates/uitest40/common/");
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
    public void envEditPage_rendersExplicitBackToEditingButtonInCompareBanner() throws Exception {
        seedCommon("uitest41", "{\"a\":1}", "seed");
        seedEnv("uitest41", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest41/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("env compare banner must render an explicit 'Back to editing' button (FR-45a)",
                html.contains("id=\"backToEditingBtn\""));
        assertTrue("'Back to editing' must be wired to switchToEditMode() (FR-45b)",
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
        HtmlPage page = wc.goTo("configTemplates/uitest42/common/");
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
    public void envEditPage_activateButtonDisabledOnTheAlreadyActiveRow() throws Exception {
        seedCommon("uitest43", "{\"a\":1}", "seed");
        ConfigSet env = seedEnv("uitest43", "dev", "{\"a\":1}", "v1");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloaded = repository.findEnv("uitest43", "dev");
        reloaded.addVersion("{\"a\":2}", "v2", "seed-author", 2L);
        repository.save(reloaded);
        int activeVersion = reloaded.getActiveVersionNumber();

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest43/dev/");
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
    }

    @Test
    public void commonEditPage_inlineScriptsAreSyntacticallyValidJs_withBusyDisableGuardCode() throws Exception {
        // Regression guard for the newly added FR-46 busy/disable guard JS (prepareSubmit's
        // save-button disable + activateVersion's all-rows-disable), matching the existing
        // real-JS-engine syntax-check pattern for this test class.
        seedCommon("uitest44", "{\"database\":{\"host\":\"db.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest44/common/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("prepareSubmit must disable both save buttons (FR-46)",
                html.contains("document.getElementById('saveBtn').disabled = true"));
        assertTrue("activateVersion must disable every Activate button, not just the clicked one (FR-46)",
                html.contains("function setActivateButtonsDisabled"));
        assertAllInlineScriptsAreSyntacticallyValidJs("common edit page (busy-disable-guard JS)", html);
    }

    @Test
    public void envEditPage_inlineScriptsAreSyntacticallyValidJs_withBusyDisableGuardCode() throws Exception {
        seedCommon("uitest45", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest45", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest45/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("prepareSubmit must disable both save buttons (FR-46)",
                html.contains("document.getElementById('saveBtn').disabled = true"));
        assertTrue("activateVersion must disable every Activate button, not just the clicked one (FR-46)",
                html.contains("function setActivateButtonsDisabled"));
        assertAllInlineScriptsAreSyntacticallyValidJs("env edit page (busy-disable-guard JS)", html);
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
    public void envEditPage_rendersBaseChainEditorMarkup() throws Exception {
        seedCommon("uitest50", "{\"a\":1}", "seed");
        seedEnv("uitest50", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest50/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("env page must render the base-chain editor rows table",
                html.contains("id=\"baseChainRowsTable\""));
        assertTrue("env page must render the Add-base row control",
                html.contains("id=\"addBaseChainRowBtn\""));
        assertTrue("env page must render the explicitlyStandalone checkbox (FR-89)",
                html.contains("id=\"explicitlyStandaloneCheckbox\""));
        assertTrue("env page must render the merged-bases pane (FR-73)",
                html.contains("id=\"mergedBasesEditor\""));
        assertTrue("env page must render the Discard-all-changes button (FR-74)",
                html.contains("id=\"discardAllBtn\""));
        // In-place-update pass (2026-09-02): baseChainField is no longer a <form>-submitted field
        // (Save now posts via proxy.save(...) AJAX, reading this element by id, not name) — assert
        // on the id JS actually reads instead of a submission-era name attribute.
        assertTrue("env page must render the hidden baseChainJson field",
                html.contains("id=\"baseChainField\""));
        assertTrue("env page must expose the available common project keys as row-picker seed data",
                html.contains("__availableProjectKeys"));
    }

    @Test
    public void envEditPage_brandNewConfigSet_baseChainEditorDefaultsToOneRow() throws Exception {
        // FR-56: a brand-new env Config Set (never saved) must default its chain editor to exactly
        // one row — this env's own project, ACTIVE — with no manual interaction. Exercised directly
        // against the accessor (same package, package-private constructor) rather than scraping the
        // rendered/JS-escaped HTML, since the seed value is itself a Gson-JSON-string-encoded JS
        // string literal — asserting the underlying data contract is more robust than re-parsing
        // escaped inline-script text.
        seedCommon("uitest51", "{\"a\":1}", "seed");
        // deliberately no seedEnv call — the env Config Set does not exist yet.

        ConfigSetRepository repository = new ConfigSetRepository();
        EnvConfigSetPage page = new EnvConfigSetPage("uitest51", "dev", repository);
        String seedLiteral = page.getBaseChainSeedJsonForScript();
        // seedLiteral is a complete JS string-literal expression (Gson-encoded, quotes included) —
        // decode it back to the raw JSON array text via Gson, exactly mirroring the client-side
        // JSON.parse(__baseChainSeed) contract this accessor feeds.
        String jsonArrayText = new com.google.gson.Gson().fromJson(seedLiteral, String.class);
        com.google.gson.JsonArray seededChain = com.google.gson.JsonParser.parseString(jsonArrayText).getAsJsonArray();

        assertEquals("a brand-new env Config Set must seed exactly one default base-chain row (FR-56)",
                1, seededChain.size());
        com.google.gson.JsonObject row = seededChain.get(0).getAsJsonObject();
        assertEquals("uitest51", row.get("projectKey").getAsString());
        assertEquals("ACTIVE", row.get("pinMode").getAsString());
    }

    @Test
    public void envEditPage_versionHistoryRendersBaseChainMarkerPerVersion() throws Exception {
        // FR-58: each historical version's own frozen base chain renders a "[N bases]" marker,
        // expandable inline with no additional AJAX call.
        seedCommon("uitest52", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("uitest52", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int v = env.addVersion("{}", "seed", "seed-author", 1L, java.util.Arrays.asList(
                BaseConfigReference.active("uitest52"),
                BaseConfigReference.pinned("uitest52", 1)));
        env.activate(v);
        repository.save(env);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest52/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("version-history row must render a [N bases] marker (FR-58)",
                html.contains("2 bases"));
        assertTrue("expandable detail must include the PINNED entry's resolved version number",
                html.contains("(v1)"));
    }

    @Test
    public void envEditPage_inlineScriptsAreSyntacticallyValidJs_withBaseChainEditorCode() throws Exception {
        // Regression guard for the newly added base-chain editor JS, matching the existing
        // real-JS-engine syntax-check pattern for this test class.
        seedCommon("uitest53", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest53", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest53/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function addBaseChainRow"));
        assertTrue(html.contains("function toggleBaseChainRowExpanded"));
        assertAllInlineScriptsAreSyntacticallyValidJs("env edit page (base-chain editor JS)", html);
    }

    @Test
    public void envPage_doComputeMerge_multiBaseChainFoldsAllBasesBeforeOverlay() throws Exception {
        seedCommon("uitest54", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet teamB = new ConfigSet("team-b-common", ConfigSetRole.COMMON, null, "Team B Common", ContentType.JSON);
        int bV = teamB.addVersion("{\"b\":2}", "seed", "seed-author", 1L);
        teamB.activate(bV);
        repository.save(teamB);
        seedEnv("uitest54", "dev", "{}", "seed");

        String baseChainJson = "[{\"projectKey\":\"uitest54\",\"pinMode\":\"ACTIVE\"},"
                + "{\"projectKey\":\"team-b-common\",\"pinMode\":\"ACTIVE\"}]";

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest54/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{}", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        // §7: `merged` is now already-serialized text in the resolved chain's ContentType, not a
        // nested JSON object — parse it here to inspect, and confirm the new `contentType` field.
        assertEquals("JSON", json.getString("contentType"));
        JSONObject merged = JSONObject.fromObject(json.getString("merged"));
        assertEquals(1, merged.getInt("a"));
        assertEquals(2, merged.getInt("b"));
        assertEquals("perReference must carry one entry per resolved chain member (FR-57)",
                2, json.getJSONArray("perReference").size());
    }

    @Test
    public void envPage_doComputeMerge_unresolvableChainReferenceReportsErrorWithoutThrowing() throws Exception {
        seedCommon("uitest55", "{\"a\":1}", "seed");
        seedEnv("uitest55", "dev", "{}", "seed");

        String baseChainJson = "[{\"projectKey\":\"no-such-common-project\",\"pinMode\":\"ACTIVE\"}]";

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest55/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{}", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertFalse("an unresolvable base-chain reference must be reported, not thrown as a server error",
                json.getBoolean("ok"));
        assertNotNull(json.getString("error"));
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest61/common/submitSave");
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest62/common/submitSave");
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
    public void doSave_envPage_rejectsMixedContentTypeBaseChainWithExactWireframeMessage() throws Exception {
        seedCommon("uitest63", "{\"a\":1}", "seed"); // JSON
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet xmlBase = new ConfigSet("uitest63-xml-common", ConfigSetRole.COMMON, null, "Common", ContentType.XML);
        xmlBase.addVersion("<root><b>2</b></root>", "seed", "seed-author", 1L);
        xmlBase.activate(1);
        repository.save(xmlBase);

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        String baseChainJson = "[{\"projectKey\":\"uitest63\",\"pinMode\":\"ACTIVE\"},"
                + "{\"projectKey\":\"uitest63-xml-common\",\"pinMode\":\"ACTIVE\"}]";

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest63/dev/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "mixed chain"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson)
        ));
        Page result = wc.getPage(request);
        assertFalse("Save blocked: mismatched content types must reject the save (FR-61)",
                result.getWebResponse().getStatusCode() == 200);
        String body = result.getWebResponse().getContentAsString();
        assertTrue("exact wireframe message shape (FR-61)",
                body.contains("Save blocked: mismatched content types in base chain — "
                        + "uitest63 (JSON), uitest63-xml-common (XML) must all share one content type."));

        assertEquals("no env Config Set version must have been persisted from the rejected save",
                null, repository.findEnv("uitest63", "dev"));
    }

    @Test
    public void doSave_envPage_baseChainRowMissingPinModeDefaultsToActive() throws Exception {
        // Secondary-bug investigation (2026-09-02, owner report): "adding a new base-chain row
        // without selecting an Active/Pin radio causes HTTP 400 + a JS TypeError". Not reproducible
        // through the normal UI flow — EnvConfigSetPage/index.jelly's own addBaseChainRow() already
        // seeds every newly-added row with pinMode:'ACTIVE' before it is ever serialized — but
        // ConfigSetPage#parseBaseChainOrFail now also defensively defaults an absent/blank pinMode
        // to ACTIVE (matching that same client default) rather than throwing a raw
        // NullPointerException from PinMode#valueOf, for any other caller of this shared parse path.
        seedCommon("uitest65", "{\"a\":1}", "seed");

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        // A successful save redirects to the env page itself, which embeds the Monaco editor's
        // loader.js — real ES2015+ syntax that HtmlUnit's Rhino-based JS engine cannot parse
        // ("Script identifier is a reserved word: class"). Every other test in this class that
        // exercises a page load disables JS for exactly this reason; this test does too, since it
        // asserts on the save's HTTP status/persisted state, not on any client-side behavior.
        wc.getOptions().setJavaScriptEnabled(false);

        String baseChainJson = "[{\"projectKey\":\"uitest65\"}]"; // pinMode deliberately absent

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest65/dev/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "missing pinMode"),
                new org.htmlunit.util.NameValuePair("baseChainJson", baseChainJson),
                // doSave() never activates a version by itself (ConfigSet#addVersion's own javadoc:
                // "Never activates the new version by itself") — only the "Save & Activate" button
                // sets this hidden field. This test asserts on the persisted ACTIVE version's base
                // chain below, so it must exercise that same save-and-activate path, exactly like a
                // real first save through the UI would if the admin wants an immediately-active
                // version.
                new org.htmlunit.util.NameValuePair("activate", "true")
        ));
        Page result = wc.getPage(request);
        assertEquals("a base-chain row with no pinMode must default to ACTIVE and save successfully",
                200, result.getWebResponse().getStatusCode());

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet envSet = repository.findEnv("uitest65", "dev");
        assertNotNull(envSet);
        assertEquals(PinMode.ACTIVE,
                envSet.getActiveVersion().getBaseChain().get(0).getPinMode());
    }

    @Test
    public void doCheckContentSyntax_and_doReformatContent_noCollisionWithExistingEndpoints() throws Exception {
        seedCommon("uitest64", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();

        Page validPage = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest64/common/checkContentSyntax?content="
                + java.net.URLEncoder.encode("<root><a>1</a></root>", "UTF-8") + "&contentType=XML");
        JSONObject validJson = JSONObject.fromObject(validPage.getWebResponse().getContentAsString());
        assertTrue("doCheckContentSyntax must accept syntactically valid XML", validJson.getBoolean("ok"));

        Page invalidPage = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest64/common/checkContentSyntax?content="
                + java.net.URLEncoder.encode("<root><a></root>", "UTF-8") + "&contentType=XML");
        JSONObject invalidJson = JSONObject.fromObject(invalidPage.getWebResponse().getContentAsString());
        assertFalse("doCheckContentSyntax must reject malformed XML", invalidJson.getBoolean("ok"));

        Page formatPage = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest64/common/reformatContent?content="
                + java.net.URLEncoder.encode("a: 1", "UTF-8") + "&contentType=YAML");
        JSONObject formatJson = JSONObject.fromObject(formatPage.getWebResponse().getContentAsString());
        assertTrue("doReformatContent must succeed for valid YAML", formatJson.getBoolean("ok"));
        assertNotNull(formatJson.getString("formatted"));
    }

    // --- Base-chain accordion / type-filtered picker / three-pane layout / discard / FR-89 --------
    // (2026-09-03 tech-lead-contracted redesign pass)

    @Test
    public void doSubmitSave_persistsExplicitlyStandaloneTrue_viaSixArgAddVersion() throws Exception {
        // Real-gap regression guard (§1 of the tech-lead contract): saveImpl previously called the
        // OLD 5-arg ConfigSet.addVersion overload, which hardcodes explicitlyStandalone=false
        // regardless of what the UI sent. This proves the checkbox's boolean state actually survives
        // a real save round trip.
        seedCommon("uitest70a", "{\"a\":1}", "seed");

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest70a/dev/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "standalone save"),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]"),
                new org.htmlunit.util.NameValuePair("explicitlyStandalone", "true"),
                new org.htmlunit.util.NameValuePair("activate", "true")
        ));
        Page result = wc.getPage(request);
        assertEquals("a valid explicitlyStandalone=true save with an empty chain must succeed",
                200, result.getWebResponse().getStatusCode());

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = repository.findEnv("uitest70a", "dev");
        assertNotNull(env);
        assertTrue("explicitlyStandalone=true must actually persist through saveImpl's addVersion call",
                env.getActiveVersion().isExplicitlyStandalone());
    }

    @Test
    public void doSubmitSave_omittedExplicitlyStandalone_defaultsFalse() throws Exception {
        seedCommon("uitest70b", "{\"a\":1}", "seed");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest70b/dev/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "ordinary save"),
                new org.htmlunit.util.NameValuePair("activate", "true")
        ));
        wc.getPage(request);

        ConfigSetRepository repository = new ConfigSetRepository();
        assertFalse(repository.findEnv("uitest70b", "dev").getActiveVersion().isExplicitlyStandalone());
    }

    @Test
    public void effectiveBaseChainForTemplate_respectsExplicitlyStandalone_forSeedAndTemplate() throws Exception {
        // Real gap fix (§5): effectiveBaseChainForTemplate previously ignored isExplicitlyStandalone()
        // entirely, unconditionally applying the FR-52 synthesized default. Both the base-chain
        // editor's re-seed-on-reopen AND Generate Template must now honor it.
        seedCommon("uitest71", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("uitest71", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int v = env.addVersion("{\"own\":true}", "standalone save", "seed-author", 1L,
                Collections.emptyList(), true);
        env.activate(v);
        repository.save(env);

        EnvConfigSetPage page = new EnvConfigSetPage("uitest71", "dev", repository);
        String seedLiteral = page.getBaseChainSeedJsonForScript();
        String jsonArrayText = new com.google.gson.Gson().fromJson(seedLiteral, String.class);
        com.google.gson.JsonArray seededChain = com.google.gson.JsonParser.parseString(jsonArrayText).getAsJsonArray();
        assertEquals("a standalone version must re-seed the editor with ZERO rows, not the FR-52 default",
                0, seededChain.size());

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath() + "configTemplates/uitest71/dev/renderTemplate");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        JSONObject template = JSONObject.fromObject(json.getString("template"));
        assertTrue("Generate Template on a standalone version must reflect ONLY the env's own content",
                template.has("own"));
        assertFalse("must NOT spuriously fold in the common project's own content (FR-87)",
                template.has("database"));
    }

    @Test
    public void doComputeMerge_explicitlyStandaloneTrue_emptyChain_doesNotApplyFr52Default() throws Exception {
        // Real gap fix (§5): previewMergeImpl previously always substituted the FR-52 synthesized
        // single-entry default for an empty draft chain, with no way to preview a genuinely empty
        // (explicitlyStandalone) chain while mid-edit.
        seedCommon("uitest72", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest72", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page standalone = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest72/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{\"own\":1}", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode("[]", "UTF-8")
                + "&explicitlyStandalone=true");
        JSONObject standaloneJson = JSONObject.fromObject(standalone.getWebResponse().getContentAsString());
        assertTrue(standaloneJson.getBoolean("ok"));
        JSONObject merged = JSONObject.fromObject(standaloneJson.getString("merged"));
        assertFalse("the FR-52 default (this project's own common content) must NOT be folded in "
                + "while explicitlyStandalone=true", merged.has("database"));
        assertEquals(1, merged.getInt("own"));
        JSONObject mergedBases = JSONObject.fromObject(standaloneJson.getString("mergedBases"));
        assertTrue("mergedBases must be an empty fold when the chain is genuinely empty",
                mergedBases.isEmpty());

        Page ordinary = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest72/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{\"own\":1}", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode("[]", "UTF-8")
                + "&explicitlyStandalone=false");
        JSONObject ordinaryJson = JSONObject.fromObject(ordinary.getWebResponse().getContentAsString());
        assertTrue(ordinaryJson.getBoolean("ok"));
        JSONObject ordinaryMerged = JSONObject.fromObject(ordinaryJson.getString("merged"));
        assertTrue("without explicitlyStandalone, an empty chain must still apply the FR-52 default",
                ordinaryMerged.has("database"));
    }

    @Test
    public void doComputeMerge_returnsMergedBasesField_foldOfChainBeforeOverlay() throws Exception {
        // FR-73: "Merged bases" is the fold BEFORE the env override is applied — distinct from
        // "merged" (the fold AFTER the override).
        seedCommon("uitest73", "{\"a\":1,\"b\":1}", "seed");
        seedEnv("uitest73", "dev", "{}", "seed");

        String baseChainJson = "[{\"projectKey\":\"uitest73\",\"pinMode\":\"ACTIVE\"}]";
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest73/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{\"b\":2}", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode(baseChainJson, "UTF-8"));
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        JSONObject mergedBases = JSONObject.fromObject(json.getString("mergedBases"));
        assertEquals("mergedBases must reflect the chain fold BEFORE the override", 1, mergedBases.getInt("b"));
        JSONObject merged = JSONObject.fromObject(json.getString("merged"));
        assertEquals("merged must reflect the fold AFTER the override is applied", 2, merged.getInt("b"));
    }

    @Test
    public void getCommonVersionCatalogJsonForScript_exposesVersionsAndTypeByProjectMaps() throws Exception {
        // FR-72: the payload shape changed from a bare {projectKey: [...]} object to
        // {versionsByProject: {...}, typeByProject: {...}} — proves the new sibling map exists with
        // the same key set, for the client-side type filter.
        seedCommon("uitest74", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        EnvConfigSetPage page = new EnvConfigSetPage("uitest74", "dev", repository);
        String literal = page.getCommonVersionCatalogJsonForScript();
        String jsonText = new com.google.gson.Gson().fromJson(literal, String.class);
        com.google.gson.JsonObject wrapper = com.google.gson.JsonParser.parseString(jsonText).getAsJsonObject();
        assertTrue(wrapper.has("versionsByProject"));
        assertTrue(wrapper.has("typeByProject"));
        assertEquals("JSON", wrapper.getAsJsonObject("typeByProject").get("uitest74").getAsString());
    }

    @Test
    public void envEditPage_rendersMergedBasesPaneAndDiscardButtonAndStandaloneCheckbox() throws Exception {
        seedCommon("uitest75", "{\"a\":1}", "seed");
        seedEnv("uitest75", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest75/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("env page must render the Merged-bases pane (FR-73)",
                html.contains("id=\"mergedBasesEditor\""));
        assertTrue("env page must render the Discard-all-changes button (FR-74)",
                html.contains("id=\"discardAllBtn\""));
        assertTrue("env page must render the explicitlyStandalone checkbox (FR-89)",
                html.contains("id=\"explicitlyStandaloneCheckbox\""));
        assertTrue("the base-chain accordion toggle column must render per row (FR-71)",
                html.contains("ctsync-basechain-row-toggle") || html.contains("buildBaseChainRowElement"));
    }

    @Test
    public void envEditPage_versionHistoryRendersStandaloneBadgeInsteadOfBasesLink() throws Exception {
        // FR-89 second half: a version saved as explicitlyStandalone renders a distinguishing badge
        // INSTEAD OF the [N bases] link, since it would otherwise be visually indistinguishable from
        // "0 declared bases" via any other route.
        seedCommon("uitest76", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("uitest76", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int v = env.addVersion("{}", "standalone", "seed-author", 1L, Collections.emptyList(), true);
        env.activate(v);
        repository.save(env);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest76/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("a standalone version's history row must render the Standalone badge",
                html.contains("ctsync-standalone-badge") && html.contains("Standalone"));
    }

    @Test
    public void envEditPage_inlineScriptsAreSyntacticallyValidJs_withStandaloneAndDiscardCode() throws Exception {
        seedCommon("uitest77", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest77", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest77/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function onExplicitlyStandaloneChange"));
        assertTrue(html.contains("function discardAllChangesClicked"));
        assertTrue(html.contains("function projectOptionsForRow"));
        assertTrue(html.contains("function findPerReferenceFor"));
        assertAllInlineScriptsAreSyntacticallyValidJs("env edit page (standalone/discard JS)", html);
    }

    // --- Pure-logic regression guard for the accordion expand-state/reorder lockstep and the
    // type-filter logic, exercised via a real, standalone JS engine (same technique as the
    // inline-script syntax guards above), since the vendored Monaco loader's ES2015+ syntax blocks
    // full in-browser execution under HtmlUnit for this whole page. -------------------------------

    @Test
    public void baseChainRowExpanded_reorderLockstep_and_projectOptionsForRow_typeFilter_runInARealJsEngine()
            throws Exception {
        seedCommon("uitest78", "{\"a\":1}", "seed");
        seedEnv("uitest78", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest78/dev/");
        String html = page.getWebResponse().getContentAsString();

        Matcher matcher = INLINE_SCRIPT.matcher(html);
        StringBuilder allScripts = new StringBuilder();
        while (matcher.find()) {
            if (matcher.group(1) != null) { allScripts.append(matcher.group(1)).append('\n'); }
        }

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        // Stub the handful of DOM/monaco globals this page's top-level script touches eagerly
        // (require.config/require, document.getElementById chains for id lookups used outside any
        // function body, and the addEventListener calls) so the file can be evaluated far enough to
        // define moveBaseChainRow/projectOptionsForRow as real, callable functions — never executing
        // Monaco itself, exactly like the existing real-JS-engine syntax guards in this class.
        String harness =
                "var document = { getElementById: function() { return { addEventListener: function(){}, "
                        + "style:{}, classList:{add:function(){},remove:function(){}} }; }, "
                        + "querySelectorAll: function() { return []; } };"
                        + "var require = function(){}; require.config = function(){};"
                        + "var monaco = undefined;"
                        + "function makeStaplerProxy() { return {}; }"
                        + allScripts;
        engine.eval(harness);

        // moveBaseChainRow must keep baseChainRowExpanded in lockstep with baseChainRows.
        engine.eval("baseChainRows = [{projectKey:'a',pinMode:'ACTIVE',pinnedVersionNumber:0},"
                + "{projectKey:'b',pinMode:'ACTIVE',pinnedVersionNumber:0}];"
                + "baseChainRowExpanded = [false, true];"
                + "renderBaseChainRows = function() {};" // stub out the DOM-touching render for this unit check
                + "recomputeMerge = function() {};"
                + "moveBaseChainRow(1, -1);");
        Object expandedAfterMove = engine.eval("baseChainRowExpanded[0]");
        assertEquals("moving the expanded row up must carry its expand state with it",
                Boolean.TRUE, expandedAfterMove);

        // projectOptionsForRow: row 0 unfiltered, row 1 filtered to row 0's resolved type.
        engine.eval("availableProjectKeys = ['json-proj', 'xml-proj'];"
                + "commonVersionCatalog = { typeByProject: { 'json-proj': 'JSON', 'xml-proj': 'XML' } };"
                + "baseChainRows = [{projectKey:'json-proj'}, {projectKey:'xml-proj'}];");
        Object row0Options = engine.eval("projectOptionsForRow(0).length");
        assertEquals("row #1 (index 0) must list every available project, unfiltered",
                2.0, ((Number) row0Options).doubleValue(), 0.001);
        Object row1Options = engine.eval("projectOptionsForRow(1)");
        // Nashorn arrays come back as a ScriptObjectMirror; check via JSON.stringify for a simple assert.
        Object row1Json = engine.eval("JSON.stringify(projectOptionsForRow(1))");
        assertEquals("row #2+ must be filtered to row #1's resolved ContentType — 'xml-proj' does not "
                + "match 'json-proj's JSON type", "[\"json-proj\"]", row1Json);
    }

    // --- FR-104 (2026-09-03): env content-type picker for the explicitlyStandalone-with-no-chain
    // gap — a real functional gap found in live manual testing of the already-shipped FR-60/86/87 UI.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void doComputeMerge_standaloneEmptyChain_usesClientSuppliedContentType() throws Exception {
        // Real gap fix: previewMergeImpl previously hardcoded ContentType.JSON whenever the resolved
        // chain was empty, with no way for an explicitlyStandalone draft to preview as XML/YAML.
        seedCommon("uitest80", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest80/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("<root><own>1</own></root>", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode("[]", "UTF-8")
                + "&explicitlyStandalone=true"
                + "&standaloneContentType=XML");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals("an explicitlyStandalone empty chain must resolve its ContentType from the "
                + "client's own content-type picker selection, not a hardcoded JSON default",
                "XML", json.getString("contentType"));
        assertTrue("the override content must round-trip as XML, not be mis-parsed as JSON",
                json.getString("merged").contains("<own>1</own>"));
    }

    @Test
    public void doComputeMerge_standaloneEmptyChain_missingContentType_stillDefaultsJson() throws Exception {
        // Backward-compat guard: an explicitlyStandalone caller that never supplies
        // standaloneContentType at all (e.g. a stale client, or a hand-crafted request) must keep
        // getting the pre-FR-104 JSON default, not an error.
        seedCommon("uitest80b", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest80b/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{}", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode("[]", "UTF-8")
                + "&explicitlyStandalone=true");
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals("JSON", json.getString("contentType"));
    }

    @Test
    public void doComputeMerge_nonStandaloneResolvedChain_ignoresClientSuppliedStandaloneContentType()
            throws Exception {
        // Regression guard (must NOT change FR-60's existing behavior): when the chain actually
        // resolves to ≥1 entry, its ContentType always wins — a standaloneContentType value must
        // never override it, even if a stale/careless client sends one.
        seedCommon("uitest81", "{\"a\":1}", "seed"); // JSON, per seedCommon

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest81/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{}", "UTF-8")
                + "&baseChainJson=" + java.net.URLEncoder.encode("[]", "UTF-8") // FR-52 default applies
                + "&explicitlyStandalone=false"
                + "&standaloneContentType=XML"); // must be ignored — the chain resolves to JSON
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals("a resolved (non-empty) chain's ContentType must always win over any "
                + "client-supplied standaloneContentType", "JSON", json.getString("contentType"));
    }

    @Test
    public void doSubmitSave_envFirstSave_explicitlyStandalone_withContentType_persistsChosenType()
            throws Exception {
        seedCommon("uitest82", "{\"a\":1}", "seed"); // establishes projectKey, irrelevant to the env's own type

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest82/dev/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "<root><own>1</own></root>"),
                new org.htmlunit.util.NameValuePair("note", "first standalone save, XML"),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]"),
                new org.htmlunit.util.NameValuePair("explicitlyStandalone", "true"),
                new org.htmlunit.util.NameValuePair("contentType", "XML")
        ));
        Page result = wc.getPage(request);
        assertEquals(200, result.getWebResponse().getStatusCode());

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet saved = repository.findEnv("uitest82", "dev");
        assertNotNull(saved);
        assertEquals("the env Config Set's own persisted ContentType must be the picker's choice, "
                + "not the pre-FR-104 hardcoded JSON default", ContentType.XML, saved.getContentType());
    }

    @Test
    public void doSubmitSave_envNonStandaloneFirstSave_withNoContentType_stillDefaultsJson()
            throws Exception {
        // Regression guard: the ordinary (non-standalone) env first-save path must be completely
        // unaffected by FR-104 — its client never sends contentType, so this must keep behaving
        // exactly as it did before this feature existed.
        seedCommon("uitest82b", "{\"a\":1}", "seed");

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest82b/dev/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "{}"),
                new org.htmlunit.util.NameValuePair("note", "ordinary first save")
        ));
        wc.getPage(request);

        ConfigSetRepository repository = new ConfigSetRepository();
        assertEquals(ContentType.JSON, repository.findEnv("uitest82b", "dev").getContentType());
    }

    @Test
    public void doSubmitSave_envContentType_immutableAfterFirstStandaloneSave() throws Exception {
        seedCommon("uitest83", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("uitest83", ConfigSetRole.ENV, "dev", "Env", ContentType.XML);
        int v = env.addVersion("<root><a>1</a></root>", "seed", "seed-author", 1L,
                Collections.emptyList(), true);
        env.activate(v);
        repository.save(env);

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest83/dev/submitSave");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        // Attempting to smuggle a different contentType on a second save must have no effect — the
        // field is only meaningful when getConfigSet() == null (FR-59, reused verbatim by FR-104).
        request.setRequestParameters(java.util.List.of(
                new org.htmlunit.util.NameValuePair("content", "<root><a>2</a></root>"),
                new org.htmlunit.util.NameValuePair("note", "second save, attempted type change"),
                new org.htmlunit.util.NameValuePair("baseChainJson", "[]"),
                new org.htmlunit.util.NameValuePair("explicitlyStandalone", "true"),
                new org.htmlunit.util.NameValuePair("contentType", "YAML")
        ));
        wc.getPage(request);

        assertEquals("a second version of an already-locked env Config Set must never change its "
                + "ContentType, standalone or not", ContentType.XML, repository.findEnv("uitest83", "dev").getContentType());
    }

    @Test
    public void envEditPage_rendersContentTypeRow_unlockedWhenNoVersionExists() throws Exception {
        seedCommon("uitest84", "{\"a\":1}", "seed");
        // deliberately no seedEnv — this env Config Set does not exist yet.

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest84/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("env page must render the FR-104 content-type row",
                html.contains("id=\"envContentTypeRow\""));
        assertTrue("no version yet must render the interactive (unlocked) radio group",
                html.contains("id=\"envContentTypeUnlockedGroup\""));
        assertTrue(html.contains("name=\"envContentTypeRadio\""));
        assertFalse("must not render the locked display before any version exists",
                html.contains("id=\"envContentTypeLockedDisplay\""));
    }

    @Test
    public void envEditPage_rendersContentTypeRow_lockedWhenStandaloneVersionExists() throws Exception {
        seedCommon("uitest85", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("uitest85", ConfigSetRole.ENV, "dev", "Env", ContentType.XML);
        int v = env.addVersion("<root><a>1</a></root>", "seed", "seed-author", 1L,
                Collections.emptyList(), true);
        env.activate(v);
        repository.save(env);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest85/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("once a version exists, the picker must render the locked/read-only display",
                html.contains("id=\"envContentTypeLockedDisplay\""));
        assertFalse("the interactive radio group must no longer render once locked",
                html.contains("id=\"envContentTypeUnlockedGroup\""));
    }

    @Test
    public void envEditPage_rendersContentTypeRow_lockedWhenNonStandaloneVersionExists() throws Exception {
        // Requirement: locking applies "whether standalone or not" — an ordinary, chain-based env
        // Config Set's picker (once shown, e.g. via a later explicitlyStandalone toggle) must ALSO
        // render locked once any version exists, never a fresh unlocked group.
        seedCommon("uitest86", "{\"a\":1}", "seed");
        seedEnv("uitest86", "dev", "{}", "seed"); // ordinary, non-standalone version

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest86/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("id=\"envContentTypeLockedDisplay\""));
        assertFalse(html.contains("id=\"envContentTypeUnlockedGroup\""));
    }

    @Test
    public void envEditPage_inlineScript_containsContentTypePickerFunctions_andIsValidJs() throws Exception {
        seedCommon("uitest87", "{\"a\":1}", "seed");
        seedEnv("uitest87", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest87/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("function onEnvContentTypeChange"));
        assertTrue(html.contains("function applyEnvContentTypeLocked"));
        assertTrue(html.contains("function updateEnvContentTypeRowVisibility"));
        assertAllInlineScriptsAreSyntacticallyValidJs("env edit page (content-type picker JS)", html);
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
                url.contains("/uitest90/common/"));
        assertTrue("the selected content type must be carried through as a query parameter (FR-105): " + url,
                url.contains("contentType=XML"));
    }

    @Test
    public void rootListPage_clickingCreateWithEmptyProjectKey_preservesExistingNavigationShape() throws Exception {
        // Pre-existing behavior (unchanged by FR-105): an empty projectKey field was never
        // client-side-validated — the button's onclick handler always navigated regardless, landing
        // on ".../configTemplates//common/" (an empty, encodeURIComponent-produced path segment).
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
                + "as before this change: " + url, url.contains("configTemplates//common/"));
        assertTrue("the default JSON content type must still be appended: " + url,
                url.contains("contentType=JSON"));
    }

    @Test
    public void commonEditPage_preselectsCarriedThroughContentType_whenProjectDoesNotYetExist() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest91-new/common/?contentType=XML");
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
        HtmlPage page = wc.goTo("configTemplates/uitest92/common/?contentType=XML");
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
    public void envEditPage_sectionsAreFramedNotCollapsible_andRenderInTheNewOrder() throws Exception {
        // Owner-directed reorg (2026-09-04): Version history / Secrets manifest / Base chain /
        // Editor are no longer collapsible <details> accordions, and Version history now renders
        // FIRST, ahead of Secrets manifest, ahead of the (newly-extracted) Base chain section,
        // ahead of Editor last.
        seedCommon("uitest100", "{\"a\":1}", "seed");
        seedEnv("uitest100", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest100/dev/");
        String html = page.getWebResponse().getContentAsString();

        assertFalse("none of the four reorganized sections may render as a collapsible <details> "
                + "accordion anymore", html.contains("<details"));
        assertTrue("each section must use Jenkins core's own framed-section convention",
                html.contains("jenkins-section"));

        int versionHistoryIdx = html.indexOf("id=\"versionHistoryDetails\"");
        int secretsManifestIdx = html.indexOf("id=\"secretsManifestDetails\"");
        int baseChainIdx = html.indexOf("id=\"baseChainEditor\"");
        int editorIdx = html.indexOf("id=\"editorDetails\"");
        assertTrue("all four section markers must be present",
                versionHistoryIdx >= 0 && secretsManifestIdx >= 0 && baseChainIdx >= 0 && editorIdx >= 0);
        assertTrue("Version history must render before Secrets manifest",
                versionHistoryIdx < secretsManifestIdx);
        assertTrue("Secrets manifest must render before the Base chain section",
                secretsManifestIdx < baseChainIdx);
        assertTrue("Base chain must render before the Editor section — it is pulled out entirely, "
                + "no longer nested inside Editor", baseChainIdx < editorIdx);
    }

    @Test
    public void commonEditPage_sectionsAreFramedNotCollapsible() throws Exception {
        // Consistency pass applied to CommonConfigSetPage too (owner instruction), section ORDER
        // unchanged there — only the accordion-to-framed-section conversion applies.
        seedCommon("uitest104", "{\"a\":1}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest104/common/");
        String html = page.getWebResponse().getContentAsString();

        assertFalse("Common page's sections must also no longer render as <details> accordions",
                html.contains("<details"));
        assertTrue("Common page's sections must also use Jenkins core's framed-section convention",
                html.contains("jenkins-section"));
    }

    @Test
    public void envEditPage_inlineScriptsAreSyntacticallyValidJs_afterSectionReorg() throws Exception {
        seedCommon("uitest105", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest105", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest105/dev/");
        assertAllInlineScriptsAreSyntacticallyValidJs(
                "env edit page (post section-reorg)", page.getWebResponse().getContentAsString());
    }

    @Test
    public void doActivate_envPage_reloadsNewlyActivatedVersionsBaseChainAndContent() throws Exception {
        // Real bug fix: activating a DIFFERENT version than whatever the editor currently has
        // loaded previously left the operator staring at a stale draft. The activate response must
        // now carry the NEWLY ACTIVATED version's own persisted content/baseChain/
        // explicitlyStandalone/contentType so the client can reload its editor state.
        seedCommon("uitest110", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("uitest110", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int v1 = env.addVersion("{\"v\":1}", "v1", "seed-author", 1L,
                Collections.singletonList(BaseConfigReference.active("uitest110")), false);
        env.activate(v1);
        int v2 = env.addVersion("{\"v\":2}", "v2", "seed-author", 2L, Collections.emptyList(), true);
        repository.save(env);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest110/dev/activateVersion?version=" + v2);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals(v2, json.getInt("active"));

        assertEquals("the activate response must carry the newly-activated version's own content, "
                + "not the previously-active version's", "{\"v\":2}", json.getString("activatedContent"));
        assertTrue("the activate response must carry the newly-activated version's "
                + "explicitlyStandalone flag", json.getBoolean("activatedExplicitlyStandalone"));
        assertEquals("the activate response must carry v2's own (empty, standalone) base chain, not v1's",
                0, json.getJSONArray("activatedBaseChain").size());
        assertEquals("JSON", json.getString("activatedContentType"));
    }

    @Test
    public void doActivate_envPage_reloadsNonStandaloneVersionsBaseChainBackToAnOlderVersion() throws Exception {
        seedCommon("uitest111", "{\"a\":1}", "seed");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("uitest111", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int v1 = env.addVersion("{\"v\":1}", "v1", "seed-author", 1L,
                Collections.singletonList(BaseConfigReference.active("uitest111")), false);
        env.activate(v1);
        int v2 = env.addVersion("{\"v\":2}", "v2", "seed-author", 2L,
                java.util.Arrays.asList(BaseConfigReference.active("uitest111"),
                        BaseConfigReference.pinned("uitest111", 1)),
                false);
        env.activate(v2);
        repository.save(env);

        // Activate BACK to v1 — must reload v1's own (single-row) chain, not v2's two-row chain.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest111/dev/activateVersion?version=" + v1);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals("{\"v\":1}", json.getString("activatedContent"));
        assertFalse(json.getBoolean("activatedExplicitlyStandalone"));
        assertEquals("activating back to v1 must reload v1's own one-row chain, not v2's two-row chain",
                1, json.getJSONArray("activatedBaseChain").size());
    }

    @Test
    public void envEditPage_inlineScriptsContainReloadEditorStateFunction_andAreValidJs() throws Exception {
        seedCommon("uitest112", "{\"database\":{\"host\":\"db.internal\"}}", "seed");
        seedEnv("uitest112", "dev", "{\"database\":{\"host\":\"db.dev.internal\"}}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest112/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("activateVersion must reload the editor state from the newly-activated version",
                html.contains("function reloadEditorStateFromActivatedVersion"));
        assertTrue(html.contains("reloadEditorStateFromActivatedVersion(r)"));
        assertAllInlineScriptsAreSyntacticallyValidJs("env edit page (activate-reload JS)", html);
    }

    @Test
    public void envEditPage_addBaseButtonUsesClearerWording() throws Exception {
        // Button-label wording refinement: "Add base" -> "Add base config" (EN).
        seedCommon("uitest120", "{\"a\":1}", "seed");
        seedEnv("uitest120", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest120/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("the Add-base button must use the clearer 'Add base config' wording",
                html.contains(">&#10133; Add base config</button>") || html.contains("Add base config"));
    }

    @Test
    public void envEditPage_baseChainTable_constrainsSelectDropdownWidth() throws Exception {
        // CSS bug fix: the Project/Pinned-version <select> elements in the base-chain table
        // inherited Jenkins core's full-width `.jenkins-select__input` sizing, overflowing the
        // table's narrow columns. Both possible selects per row now share ONE scoped rule.
        seedCommon("uitest130", "{\"a\":1}", "seed");
        seedEnv("uitest130", "dev", "{}", "seed");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo("configTemplates/uitest130/dev/");
        String html = page.getWebResponse().getContentAsString();
        assertTrue("the base-chain table must scope a compact max-width rule to its own selects, "
                + "covering both the Project and Pinned-version pickers via one shared selector",
                html.contains(".ctsync-basechain-table select.jenkins-select__input"));
    }
}
