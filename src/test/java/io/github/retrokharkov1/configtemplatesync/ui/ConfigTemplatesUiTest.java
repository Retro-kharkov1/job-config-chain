package io.github.retrokharkov1.configtemplatesync.ui;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
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
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common");
        int v = common.addVersion(contentJson, note, "seed-author", 1L);
        common.activate(v);
        repository.save(common);
        return common;
    }

    private ConfigSet seedEnv(String projectKey, String environment, String patchJson, String note) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet(projectKey, ConfigSetRole.ENV, environment, "Env");
        int v = env.addVersion(patchJson, note, "seed-author", 1L);
        env.activate(v);
        repository.save(env);
        return env;
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest4/common/save");
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
        assertFalse("null in overlay must remove the key from the merged result (RFC 7396)",
                validJson.getJSONObject("merged").getJSONObject("database").has("host"));
        assertEquals(5432, validJson.getJSONObject("merged").getJSONObject("database").getInt("port"));

        Page invalid = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest6/dev/computeMerge?overlayJson="
                + java.net.URLEncoder.encode("{ not valid", "UTF-8"));
        JSONObject invalidJson = JSONObject.fromObject(invalid.getWebResponse().getContentAsString());
        assertFalse("transiently invalid override JSON must be reported, not thrown as a server error",
                invalidJson.getBoolean("ok"));
        assertNotNull(invalidJson.getString("error"));
    }

    @Test
    public void doCompareVersions_returnsBothVersionsContentForDiffMode() throws Exception {
        ConfigSet common = seedCommon("uitest8", "{\"a\":1}", "v1");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloaded = repository.findCommon("uitest8");
        int v2 = reloaded.addVersion("{\"a\":2}", "v2", "seed-author", 2L);
        repository.save(reloaded);

        // Server-side reachability check for the Compare mode toggle (wireframe: the SAME editor
        // region switches to monaco.editor.createDiffEditor fed by these two versions' content).
        // Full Monaco JS execution (createDiffEditor itself) is not exercisable here: the vendored
        // AMD loader/bundle uses ES2015+ syntax the embedded legacy HtmlUnit JS engine can't run
        // (same limitation already documented on editPage_loadsActiveVersionContent above) — this
        // test instead proves the exact data contract the client-side diff view is wired to.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest8/common/diffVersions?oldVersion=1&newVersion=" + v2);
        JSONObject json = JSONObject.fromObject(result.getWebResponse().getContentAsString());
        assertTrue(json.getBoolean("ok"));
        assertEquals(1, json.getInt("oldVersion"));
        assertEquals(v2, json.getInt("newVersion"));
        assertEquals("{\"a\":1}", json.getString("oldContent"));
        assertEquals("{\"a\":2}", json.getString("newContent"));
    }

    @Test
    public void doCompareVersions_reportsErrorForMissingVersionWithoutThrowing() throws Exception {
        seedCommon("uitest9", "{\"a\":1}", "v1");

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        Page result = wc.getPage(wc.getContextPath()
                + "configTemplates/uitest9/common/diffVersions?oldVersion=1&newVersion=99");
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest7/common/save");
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
}
