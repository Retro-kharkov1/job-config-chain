package io.github.retrokharkov1.configtemplatesync.ui;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
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
        assertTrue("env page must render the 3-panel table with its own id, replaced on click",
                html.contains("id=\"threePanelTable\""));
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
        assertTrue("env page must render the Inspect-chain drawer toggle",
                html.contains("id=\"inspectChainToggle\""));
        assertTrue("env page must render the Inspect-chain drawer container",
                html.contains("id=\"inspectChainDrawer\""));
        assertTrue("env page must render the hidden baseChainJson form field",
                html.contains("name=\"baseChainJson\""));
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
        assertTrue(html.contains("function renderInspectDrawer"));
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest61/common/save");
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest62/common/save");
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest63/dev/save");
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

        URL url = new URL(wc.getContextPath() + "configTemplates/uitest65/dev/save");
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
}
