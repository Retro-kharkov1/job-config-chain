package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.FreeStyleProject;
import jenkins.model.Jenkins;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review point C9 (CSP-clean pages incl. the Monaco editor) and G2 (in-UI help for admin fields).
 *
 * <p>JenkinsRule enforces Jenkins' Content-Security-Policy, but HtmlUnit does not apply one, so a
 * violation cannot be observed here directly. These tests pin the strongest things that can be
 * checked without a real browser, each one a cause of a violation seen on the live page: no inline
 * script or handler, no inline {@code style} attribute, the enforced policy never widened
 * ({@code unsafe-eval}, {@code unsafe-inline} scripts, {@code blob:}), Monaco's workers started from
 * same-origin files that really exist, and the codicon font served as a file rather than a
 * {@code data:} URI (the page policy has no {@code font-src}, so {@code default-src 'self'} governs).
 * The real-browser console check is a separate manual step (see the P6 report).</p>
 */
@WithJenkins
public class CspCleanPagesTest {

    private static final Path MONACO = Path.of("src/main/webapp/monaco/vs");
        private static final Path SHARED_JS =
            Path.of("src/main/resources/io/jenkins/plugins/jobconfigchain/adjuncts/ctsyncSharedScript.js");

    private JenkinsRule jenkins;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

    private JenkinsRule.WebClient client() {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        return wc;
    }

    /** The three admin pages, name to URL; a deleted Config Set exists so the list offers its toggle. */
    private Map<String, String> pages() throws Exception {
        ConfigSetRepository repo = new ConfigSetRepository();
        ConfigSet live = new ConfigSet("csp-live", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        live.activate(live.addVersion("{\"a\":1}", "seed", "alice", 1L));
        live.addVersion("{\"a\":2}", "second", "alice", 2L);
        live.putSecretManifestEntry("db.password", "cred-id");
        repo.save(live);
        ConfigSet gone = new ConfigSet("csp-gone", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        gone.activate(gone.addVersion("{}", "seed", "alice", 1L));
        repo.save(gone);
        JSONObject confirm = new JSONObject();
        confirm.put("confirmName", "csp-gone");
        new CommonConfigSetPage("csp-gone", repo).jsDeleteConfigSet(confirm.toString());
        FreeStyleProject job = jenkins.createFreeStyleProject("csp-job");

        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("global list", "manage/configChains/");
        pages.put("common editor", "manage/configChains/csp-live/");
        pages.put("job page", "job/" + job.getName() + "/configChains/");
        return pages;
    }

    private static String mainPanel(String html) {
        int start = html.indexOf("id=\"main-panel\"");
        assertTrue(start >= 0, "the page must render core's main panel");
        return html.substring(start);
    }

    @Test
    public void thePagesCarryNoInlineScriptHandlerOrStyle() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> p : pages().entrySet()) {
            String panel = mainPanel(client().goTo(p.getValue()).getWebResponse().getContentAsString());
            Matcher script = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>").matcher(panel);
            while (script.find()) {
                problems.add(p.getKey() + ": inline script " + script.group());
            }
            Matcher handler = Pattern.compile("<[a-zA-Z][^<>]*\\son[a-z]+\\s*=").matcher(panel);
            while (handler.find()) {
                problems.add(p.getKey() + ": inline event handler in " + handler.group());
            }
            Matcher style = Pattern.compile("<[a-zA-Z][^<>]*\\sstyle\\s*=").matcher(panel);
            while (style.find()) {
                problems.add(p.getKey() + ": inline style attribute in " + style.group());
            }
            assertFalse(Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE).matcher(panel).find(),
                    p.getKey() + ": no javascript: URLs");
        }
        assertTrue(problems.isEmpty(), "CSP-forbidden markup: " + problems);
    }

    @Test
    public void thePagePolicyIsEnforcedAndNotWidenedForTheEditor() throws Exception {
        for (Map.Entry<String, String> p : pages().entrySet()) {
            WebResponse rsp = client().goTo(p.getValue()).getWebResponse();
            String csp = rsp.getResponseHeaderValue("Content-Security-Policy");
            assertNotNull(csp, p.getKey() + ": JenkinsRule must enforce a Content-Security-Policy header");
            assertFalse(csp.contains("unsafe-eval"), p.getKey() + ": script-src must not gain unsafe-eval: " + csp);
            assertFalse(csp.contains("blob:"),
                    p.getKey() + ": Monaco must use same-origin workers, so blob: is never allowed: " + csp);
            assertFalse(csp.contains("worker-src") || csp.contains("font-src"),
                    p.getKey() + ": no per-page widening is contributed: " + csp);
            Matcher scriptSrc = Pattern.compile("script-src ([^;]*);").matcher(csp);
            assertTrue(scriptSrc.find(), p.getKey() + ": script-src present");
            assertFalse(scriptSrc.group(1).contains("unsafe-inline"),
                    p.getKey() + ": script-src must not allow unsafe-inline: " + scriptSrc.group(1));
        }
    }

    @Test
    public void theHiddenStateIsAClassSoNoInlineStyleIsNeeded() throws Exception {
        String css = Files.readString(Path.of(
                "src/main/resources/io/jenkins/plugins/jobconfigchain/adjuncts/ctsyncSharedStyle.css"),
                StandardCharsets.UTF_8);
        assertTrue(css.contains(".ctsync-hidden { display: none !important; }"));
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/io/jenkins/plugins/jobconfigchain/adjuncts"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".js")).toList()) {
                String js = Files.readString(f, StandardCharsets.UTF_8);
                for (String line : js.split("\n")) {
                    // Table filtering writes tr.style.display through the CSSOM (allowed under CSP, and
                    // owned by the filter block); every other show/hide goes through ctsyncSetHidden.
                    if (line.contains("style.display") && !line.contains("tr.style.display")
                            && !line.trim().startsWith("//") && !line.trim().startsWith("*")) {
                        throw new AssertionError(f.getFileName() + " toggles style.display directly: " + line.trim());
                    }
                }
            }
        }
    }

    // ---- Monaco ---------------------------------------------------------------------------------

    @Test
    public void monacoWorkersAreStartedFromSameOriginFilesThatExist() throws Exception {
        String js = Files.readString(SHARED_JS, StandardCharsets.UTF_8);
        Matcher files = Pattern.compile("'(assets/[A-Za-z0-9._-]+\\.worker-[A-Za-z0-9_-]+\\.js)'").matcher(js);
        List<String> mapped = new ArrayList<>();
        while (files.find()) {
            mapped.add(files.group(1));
        }
        assertTrue(mapped.size() >= 6, "the worker map and the default worker must be present: " + mapped);
        for (String f : mapped) {
            assertTrue(Files.isRegularFile(MONACO.resolve(f)),
                    "ctsyncSharedScript.js names a worker file the bundle does not ship (Monaco upgraded?): " + f);
        }
        // The map must still agree with the bundle: each worker module in the bundle points at
        // exactly the asset the map names for its language.
        for (String module : List.of("json.worker-BizpAl9O.js", "css.worker-CyhWkhHo.js",
                "html.worker-CA3iAimZ.js", "ts.worker-2QLmBukE.js")) {
            Matcher asset = Pattern.compile("toUrl\\(\"\\./(assets/[^\"]+)\"\\)")
                    .matcher(Files.readString(MONACO.resolve(module), StandardCharsets.UTF_8));
            assertTrue(asset.find(), module + " must reference its worker asset");
            assertTrue(mapped.contains(asset.group(1)),
                    "worker map in ctsyncSharedScript.js is out of date for " + module + ": " + asset.group(1));
        }
        Matcher editorWorker = Pattern.compile("a\\.toUrl\\(\"\\.\\./(assets/editor\\.worker-[^\"]+)\"\\)")
                .matcher(Files.readString(MONACO.resolve("editor/editor.main.js"), StandardCharsets.UTF_8));
        assertTrue(editorWorker.find());
        assertTrue(mapped.contains(editorWorker.group(1)), "default editor worker out of date");
    }

    @Test
    public void bothPagesInstallTheSameOriginWorkersRightAfterTheEditorLoads() throws Exception {
        for (String name : List.of("ctsyncCommonScript.js", "ctsyncJobScript.js")) {
            String js = Files.readString(Path.of(
                    "src/main/resources/io/jenkins/plugins/jobconfigchain/adjuncts", name), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                    "require\\(\\['vs/editor/editor\\.main'\\], function \\(\\) \\{\\s+ctsyncInstallMonacoWorkers\\(")
                    .matcher(js);
            assertTrue(m.find(), name + " must replace MonacoEnvironment.getWorker before creating any editor");
        }
    }

    @Test
    public void theCodiconFontIsAFileNotADataUri() throws Exception {
        String css = Files.readString(MONACO.resolve("editor/editor.main.css"), StandardCharsets.UTF_8);
        assertFalse(css.contains("data:font"), "font-src falls back to default-src 'self', which excludes data:");
        assertTrue(css.contains("@font-face{font-family:codicon;font-display:block;src:url(codicon.ttf) format(\"truetype\")"));
        assertTrue(Files.size(MONACO.resolve("editor/codicon.ttf")) > 100_000, "the extracted font must be the real one");
        // And it is actually served, next to the stylesheet that names it.
        Page font = client().goTo("plugin/job-config-chain/monaco/vs/editor/codicon.ttf", null);
        assertEquals(200, font.getWebResponse().getStatusCode());
    }

    // ---- In-UI help (G2) ------------------------------------------------------------------------

    private static final Map<String, List<String>> REQUIRED_HELP = Map.of(
            "global list", List.of("newConfigKey", "contentType", "showDeleted"),
            "common editor", List.of("secretBinding", "commonEditor", "contentType", "changeNote"),
            "job page", List.of("secretBinding", "baseChainRows", "generateTemplate", "mergedBases",
                    "overrideEditor", "mergedResult", "contentType", "changeNote"));

    private WebResponse help(String name, String acceptLanguage) throws Exception {
        JenkinsRule.WebClient wc = client();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        if (acceptLanguage != null) {
            wc.addRequestHeader("Accept-Language", acceptLanguage);
        }
        return wc.goTo(PageHelp.URL_NAME + "/" + name, null).getWebResponse();
    }

    @Test
    public void everyAdminFieldHasAHelpLinkThatResolvesToAHelpFile() throws Exception {
        for (Map.Entry<String, String> p : pages().entrySet()) {
            String html = client().goTo(p.getValue()).getWebResponse().getContentAsString();
            for (String name : REQUIRED_HELP.get(p.getKey())) {
                assertTrue(html.contains("helpURL=\"") && Pattern.compile(
                        "helpURL=\"[^\"]*/" + PageHelp.URL_NAME + "/" + name + "\"").matcher(html).find(),
                        p.getKey() + ": a (?) link to " + name + " must be rendered");
                WebResponse rsp = help(name, null);
                assertEquals(200, rsp.getStatusCode(), name + " must be served");
                assertTrue(rsp.getContentAsString().trim().startsWith("<div>"),
                        name + " must be an HTML fragment wrapped in a div, as core's help loader expects");
            }
            int links = count(html, "class=\"jenkins-help-button\"");
            assertTrue(links >= REQUIRED_HELP.get(p.getKey()).size(), p.getKey() + ": help links rendered: " + links);
            assertTrue(count(html, "help-area") >= links, p.getKey() + ": every help link needs a help area");
        }
    }

    @Test
    public void helpIsServedInTheBrowsersLanguage() throws Exception {
        assertTrue(help("newConfigKey", "en").getContentAsString().contains("names a new global"));
        String uk = help("newConfigKey", "uk").getContentAsString();
        assertTrue(uk.contains("Ключ конфігурації"), "Ukrainian help for uk: " + uk);
        assertEquals(uk, help("newConfigKey", "ru").getContentAsString(),
                "a Russian browser gets the Ukrainian help (owner rule)");
        assertEquals(uk, help("newConfigKey", "uk-UA,uk;q=0.9,en;q=0.5").getContentAsString());
        assertTrue(help("newConfigKey", "de").getContentAsString().contains("neuen globalen"),
                "German help for de (one of the 32 added locales)");
        assertTrue(help("newConfigKey", "sw").getContentAsString().contains("names a new global"),
                "an unsupported language (Swahili) falls back to English");
        assertTrue(help("overrideEditor", "uk").getContentAsString().contains("Масиви замінюються цілком"));
    }

    @Test
    public void theHelpEndpointServesOnlyKnownNamesAndOnlyToAdministrators() throws Exception {
        assertEquals(404, help("noSuchHelp", null).getStatusCode());
        assertEquals(404, help("..%2F..%2Fpom", null).getStatusCode());
        assertEquals(404, help("newConfigKey.html", null).getStatusCode());
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("reader")
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        JenkinsRule.WebClient reader = jenkins.createWebClient().withBasicCredentials("reader");
        reader.getOptions().setThrowExceptionOnFailingStatusCode(false);
        assertEquals(403, reader.goTo(PageHelp.URL_NAME + "/newConfigKey", null).getWebResponse().getStatusCode());
        JenkinsRule.WebClient admin = jenkins.createWebClient().withBasicCredentials("admin");
        assertEquals(200, admin.goTo(PageHelp.URL_NAME + "/newConfigKey", null).getWebResponse().getStatusCode());
    }

    @Test
    public void theArraysAreReplacedWholesaleRuleIsExplainedInEnglish() throws Exception {
        // out-of-scope.md: the arrays-replaced-wholesale statement is mandatory in-UI help.
        for (String name : List.of("overrideEditor", "mergedBases", "mergedResult", "commonEditor")) {
            String body = help(name, "en").getContentAsString().replaceAll("\\s+", " ").toLowerCase();
            assertTrue(body.contains("replaced wholesale"), name + " must explain arrays are replaced wholesale");
        }
    }

    @Test
    public void apostrophesInBundleTextSurviveFormatting() throws Exception {
        pages();
        String root = client().goTo("manage/configChains/").getWebResponse().getContentAsString();
        assertTrue(root.contains("Config Key&#039;s common Config Set editor") || root.contains("Config Key's common Config Set editor"),
                "the New Config Set button tooltip must keep its apostrophe");
        assertTrue(root.contains("a job&#039;s own Config Chain") || root.contains("a job's own Config Chain"),
                "the intro must keep its apostrophes");
    }

    private static int count(String html, String needle) throws IOException {
        int n = 0;
        for (int i = html.indexOf(needle); i >= 0; i = html.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
