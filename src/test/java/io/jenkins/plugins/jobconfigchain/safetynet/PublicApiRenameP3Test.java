package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.model.Result;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.steps.ConfigChainSubstituteStep;
import io.jenkins.plugins.jobconfigchain.steps.ConfigChainValidateStep;
import io.jenkins.plugins.jobconfigchain.steps.SetupConfigChainStep;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.legacySetup;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.legacySubstitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.legacyValidate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.run;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBaseWithSecret;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedCredential;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.setup;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.stepLogLines;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.validate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoint B3 of the public-API rename (review point C12): the new function names, the old names as
 * deprecated aliases, and the old URLs. The rename changes only what a user types or reads; every test
 * here pins that the behavior behind the names did not change.
 */
@WithJenkins
public class PublicApiRenameP3Test {

    private static final String FILE = "app.cfg";
    private static final String BIN = "BIN";
    private static final String TEMPLATE = "host=#{Db.Host}# pw=#{Db.Password}# own=#{Own}#";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    // ------------------------------------------------------------------------------------------
    // Pipeline steps: old name output == new name output
    // ------------------------------------------------------------------------------------------

    /** A job with a base, a secret and an own override; the same content for every job made by this. */
    private WorkflowJob job(String name) throws Exception {
        WorkflowJob job = newPipelineJob(j, name);
        attachOverride(job, "{\"Own\":\"own-value\"}", chainOf(BaseConfigReference.active("p3-base")), null);
        return job;
    }

    /** The plugin's log lines with the job name (also inside {@code job#build} identities) masked. */
    private String normalized(WorkflowRun run, String jobName) throws Exception {
        return stepLogLines(j.getLog(run), jobName).replace(jobName, "@JOB@");
    }

    private void seedWorld() throws Exception {
        seedCredential("p3-cred", "P3S3cret");
        seedBaseWithSecret("p3-base", "Db.Password", "p3-cred",
                "{\"Db\":{\"Host\":\"p3-host\",\"Password\":\"" + PLACEHOLDER + "\"}}");
    }

    private static String[] deployScript(boolean legacy, String redeployFrom) {
        String setupArgs = "file: '" + FILE + "'" + (redeployFrom == null ? "" : ", redeployFromRun: '" + redeployFrom + "'");
        return new String[] {
                legacy ? legacySetup(setupArgs) : setup(setupArgs),
                writeText(FILE, TEMPLATE),
                legacy ? legacyValidate("") : validate(""),
                legacy ? legacySubstitute("") : substitute(""),
                echoBytes(BIN, FILE)
        };
    }

    @Test
    public void oldAndNewStepNames_deployIdenticalBytes_andIdenticalLogLines_acrossLiveAndRedeploy() throws Exception {
        seedWorld();
        WorkflowJob oldJob = job("p3-old");
        WorkflowJob newJob = job("p3-new");

        WorkflowRun old1 = run(j, oldJob, Result.SUCCESS, deployScript(true, null));
        WorkflowRun new1 = run(j, newJob, Result.SUCCESS, deployScript(false, null));
        // The base moves on, then each job redeploys its own build #1: the frozen binding must replay.
        ChainTestKit.addBaseVersion("p3-base",
                "{\"Db\":{\"Host\":\"p3-host-v2\",\"Password\":\"" + PLACEHOLDER + "\"}}", "bump", true);
        WorkflowRun old2 = run(j, oldJob, Result.SUCCESS, deployScript(true, "1"));
        WorkflowRun new2 = run(j, newJob, Result.SUCCESS, deployScript(false, "1"));

        assertArrayEquals(logBytes(j, old1, BIN), logBytes(j, new1, BIN), "live deploy: same bytes");
        assertArrayEquals(logBytes(j, old2, BIN), logBytes(j, new2, BIN), "redeploy: same bytes");
        assertEquals("host=p3-host pw=P3S3cret own=own-value",
                new String(logBytes(j, new2, BIN), java.nio.charset.StandardCharsets.UTF_8),
                "redeploy of build #1 replays the config build #1 shipped with");
        assertEquals(normalized(old1, "p3-old"), normalized(new1, "p3-new"));
        assertEquals(normalized(old2, "p3-old"), normalized(new2, "p3-new"));
        assertTrue(stepLogLines(j.getLog(new1), "p3-new").contains("[configTemplateSync] Validation passed"),
                "log prefix is unchanged: " + stepLogLines(j.getLog(new1), "p3-new"));
        // The step banner of the new name is the new name.
        j.assertLogContains("[Pipeline] configChainSubstitute", new1);
        j.assertLogContains("[Pipeline] configTemplateSubstitute", old1);
    }

    @Test
    public void oldAndNewStepNames_failTheSameWay_onMissingKeys() throws Exception {
        seedWorld();
        WorkflowJob oldJob = job("p3-fail-old");
        WorkflowJob newJob = job("p3-fail-new");
        String bad = "writeFile file: 'app.cfg', text: '#{Db.Host}# #{No.Such}#'";

        WorkflowRun oldRun = run(j, oldJob, Result.FAILURE, legacySetup("file: 'app.cfg'"), bad, legacyValidate(""));
        WorkflowRun newRun = run(j, newJob, Result.FAILURE, setup("file: 'app.cfg'"), bad, validate(""));

        String oldLines = stepLogLines(j.getLog(oldRun), "p3-fail-old");
        assertFalse(oldLines.isEmpty());
        assertEquals(oldLines, stepLogLines(j.getLog(newRun), "p3-fail-new"));
        assertTrue(oldLines.contains("Missing config keys"), oldLines);
    }

    @Test
    public void explicitUseBaseCall_isIdenticalUnderBothNames() throws Exception {
        seedBase("p3-explicit", "{\"A\":{\"B\":\"c\"}}");
        WorkflowJob oldJob = newPipelineJob(j, "p3-ex-old");
        WorkflowJob newJob = newPipelineJob(j, "p3-ex-new");
        String body = "useBase: true, configKey: 'p3-explicit'";

        WorkflowRun o = run(j, oldJob, Result.SUCCESS, writeText(FILE, "x=#{A.B}#"),
                legacySubstitute("file: '" + FILE + "', " + body), echoBytes(BIN, FILE));
        WorkflowRun n = run(j, newJob, Result.SUCCESS, writeText(FILE, "x=#{A.B}#"),
                substitute("file: '" + FILE + "', " + body), echoBytes(BIN, FILE));

        assertArrayEquals(logBytes(j, o, BIN), logBytes(j, n, BIN));
        assertEquals("x=c", new String(logBytes(j, n, BIN), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void mixedNamesInOneBuild_workInBothDirections() throws Exception {
        seedWorld();
        WorkflowJob oldFirst = job("p3-mix-1");
        WorkflowJob newFirst = job("p3-mix-2");

        // Old setup (stored build state) read by new steps.
        WorkflowRun a = run(j, oldFirst, Result.SUCCESS, legacySetup("file: '" + FILE + "'"),
                writeText(FILE, TEMPLATE), validate(""), substitute(""), echoBytes(BIN, FILE));
        // New setup read by old steps.
        WorkflowRun b = run(j, newFirst, Result.SUCCESS, setup("file: '" + FILE + "'"),
                writeText(FILE, TEMPLATE), legacyValidate(""), legacySubstitute(""), echoBytes(BIN, FILE));

        assertArrayEquals(logBytes(j, a, BIN), logBytes(j, b, BIN));
        assertEquals("host=p3-host pw=P3S3cret own=own-value",
                new String(logBytes(j, a, BIN), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void newSetupStep_refusedInsideParallel_namingTheNewNames_oldKeepsItsOwnText() throws Exception {
        seedWorld();
        WorkflowJob newJob = job("p3-par-new");
        WorkflowJob oldJob = job("p3-par-old");

        WorkflowRun n = ChainTestKit.runScript(j, newJob, Result.FAILURE,
                "node { parallel a: { " + setup("file: 'x'") + " }, b: { echo 'b' } }");
        WorkflowRun o = ChainTestKit.runScript(j, oldJob, Result.FAILURE,
                "node { parallel a: { " + legacySetup("file: 'x'") + " }, b: { echo 'b' } }");

        j.assertLogContains("[configTemplateSync] setupConfigChain() is not supported inside a parallel {} branch", n);
        j.assertLogContains("Call configChainValidate/configChainSubstitute with", n);
        j.assertLogContains("[configTemplateSync] setupConfigTemplate() is not supported inside a parallel {} branch", o);
        j.assertLogContains("Call configTemplateValidate/configTemplateSubstitute with", o);
    }

    // ------------------------------------------------------------------------------------------
    // Snippet Generator
    // ------------------------------------------------------------------------------------------

    private static Set<String> functionNames(Snippetizer snippetizer, boolean advanced) {
        Set<String> names = new TreeSet<>();
        for (Snippetizer.QuasiDescriptor q : snippetizer.getQuasiDescriptors(advanced)) {
            if (q.real instanceof StepDescriptor sd && sd.getFunctionName().matches("(config|setupConfig)(Chain|Template).*")) {
                names.add(sd.getFunctionName());
            }
        }
        return names;
    }

    @Test
    public void snippetGenerator_listsNewNamesNormally_andOldNamesOnlyAsAdvancedDeprecated() throws Exception {
        Snippetizer snippetizer = j.jenkins.getExtensionList(Snippetizer.class).get(0);

        assertEquals(Set.of("configChainSubstitute", "configChainValidate", "setupConfigChain"),
                functionNames(snippetizer, false), "normal (non-advanced) entries");
        assertEquals(Set.of("configTemplateSubstitute", "configTemplateValidate", "setupConfigTemplate"),
                functionNames(snippetizer, true), "advanced/deprecated entries");

        for (String old : List.of("Substitute", "Validate", "Setup")) {
            StepDescriptor d = j.jenkins.getExtensionList(StepDescriptor.class).stream()
                    .filter(x -> x.getFunctionName().equalsIgnoreCase(
                            old.equals("Setup") ? "setupConfigTemplate" : "configTemplate" + old))
                    .findFirst().orElseThrow();
            assertTrue(d.isAdvanced());
            assertTrue(d.getDisplayName().contains("deprecated"), d.getDisplayName());
        }
    }

    @Test
    public void snippetGenerator_generatesNewNameSnippets() throws Exception {
        ConfigChainSubstituteStep substituteStep = new ConfigChainSubstituteStep();
        substituteStep.setFile("app.cfg");
        substituteStep.setUseBase(true);
        substituteStep.setConfigKey("shared");
        substituteStep.setEncoding("UTF-8");
        assertEquals("configChainSubstitute configKey: 'shared', encoding: 'UTF-8', file: 'app.cfg', useBase: true",
                Snippetizer.object2Groovy(substituteStep));

        ConfigChainValidateStep validateStep = new ConfigChainValidateStep();
        validateStep.setFile("app.cfg");
        assertEquals("configChainValidate file: 'app.cfg'", Snippetizer.object2Groovy(validateStep));

        SetupConfigChainStep setupStep = new SetupConfigChainStep();
        setupStep.setFile("app.cfg");
        setupStep.setRedeployFromRun("3");
        assertEquals("setupConfigChain file: 'app.cfg', redeployFromRun: '3'", Snippetizer.object2Groovy(setupStep));
    }

    // ------------------------------------------------------------------------------------------
    // URLs
    // ------------------------------------------------------------------------------------------

    private WebResponse request(HttpMethod method, String relativeUrl) throws Exception {
        j.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(new WebRequest(new URL(wc.getContextPath() + relativeUrl), method));
        return page.getWebResponse();
    }

    private String location(WebResponse response) throws Exception {
        String value = response.getResponseHeaderValue("Location");
        assertTrue(value != null, "no Location header, status " + response.getStatusCode());
        // Compare the path only: the redirect may be absolute.
        return value.replaceFirst("^https?://[^/]+", "").replaceFirst("^" + Pattern.quote(contextPath()), "");
    }

    private String contextPath() throws Exception {
        String path = j.getURL().getPath();
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    @Test
    public void newUrls_serveTheGlobalAndTheJobPage() throws Exception {
        seedBase("p3-url", "{\"a\":1}");
        newPipelineJob(j, "p3-urljob");

        assertEquals(200, request(HttpMethod.GET, "configChains/").getStatusCode());
        assertEquals(200, request(HttpMethod.GET, "manage/configChains/").getStatusCode());
        assertEquals(200, request(HttpMethod.GET, "configChains/p3-url/").getStatusCode());
        assertEquals(200, request(HttpMethod.GET, "job/p3-urljob/configChains/").getStatusCode());
    }

    @Test
    public void oldGetUrls_redirectToTheNewUrls_keepingPathSuffixAndQuery() throws Exception {
        seedBase("p3-redir", "{\"a\":1}");
        newPipelineJob(j, "p3-redirjob");

        WebResponse list = request(HttpMethod.GET, "configTemplates/");
        assertEquals(302, list.getStatusCode());
        assertEquals("/configChains/", location(list));

        WebResponse bare = request(HttpMethod.GET, "configTemplates");
        assertEquals(302, bare.getStatusCode());
        // Stapler first appends the missing slash itself; the browser then follows on to the new URL
        // (see oldGetUrl_followedByABrowser_endsOnTheNewPage).
        assertTrue(location(bare).equals("/configTemplates/") || location(bare).startsWith("/configChains"),
                location(bare));

        WebResponse withQuery = request(HttpMethod.GET, "configTemplates/?deleted=some-key");
        assertEquals(302, withQuery.getStatusCode());
        assertEquals("/configChains/?deleted=some-key", location(withQuery));

        WebResponse editor = request(HttpMethod.GET, "configTemplates/p3-redir/");
        assertEquals(302, editor.getStatusCode());
        assertEquals("/configChains/p3-redir/", location(editor));

        WebResponse manage = request(HttpMethod.GET, "manage/configTemplates/p3-redir/?restored=x");
        assertEquals(302, manage.getStatusCode());
        assertEquals("/manage/configChains/p3-redir/?restored=x", location(manage));

        WebResponse jobPage = request(HttpMethod.GET, "job/p3-redirjob/configTemplates/");
        assertEquals(302, jobPage.getStatusCode());
        assertEquals("/job/p3-redirjob/configChains/", location(jobPage));

        WebResponse jobDeep = request(HttpMethod.GET, "job/p3-redirjob/configTemplates/some/where?x=1&y=2");
        assertEquals(302, jobDeep.getStatusCode());
        assertEquals("/job/p3-redirjob/configChains/some/where?x=1&y=2", location(jobDeep));
    }

    @Test
    public void oldGetUrl_followedByABrowser_endsOnTheNewPage() throws Exception {
        seedBase("p3-follow", "{\"a\":1}");
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        assertTrue(wc.goTo("configTemplates/p3-follow/").getUrl().getPath().endsWith("/configChains/p3-follow/"));
    }

    @Test
    public void oldPostUrls_answer404_neverARedirect_andDoNotRunTheAction() throws Exception {
        seedBase("p3-post", "{\"a\":1}");
        newPipelineJob(j, "p3-postjob");

        for (String url : List.of(
                "configTemplates/p3-post/submitSave",
                "configTemplates/p3-post/activateVersion",
                "manage/configTemplates/p3-post/submitSave",
                "job/p3-postjob/configTemplates/submitSave",
                "job/p3-postjob/configTemplates/activateVersion",
                "configTemplates/",
                "job/p3-postjob/configTemplates/")) {
            WebResponse reply = request(HttpMethod.POST, url);
            assertEquals(404, reply.getStatusCode(), "POST " + url);
            assertTrue(reply.getResponseHeaderValue("Location") == null, "POST " + url + " must not redirect");
        }
        // The old POST path did not save anything.
        assertEquals(1, new io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository()
                .findCommon("p3-post").getVersions().size());
    }

    @Test
    public void oldUrls_areNotListedAsEntries_onManageJenkins_orOnTheJobSidebar() throws Exception {
        newPipelineJob(j, "p3-sidebar");
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);

        String manage = wc.goTo("manage").getWebResponse().getContentAsString();
        assertTrue(manage.contains("Config Chains"));
        assertFalse(manage.contains("href=\"configTemplates"), "no tile for the old URL");
        assertFalse(manage.contains("/configTemplates"), "no link to the old URL");

        String job = wc.goTo("job/p3-sidebar/").getWebResponse().getContentAsString();
        assertTrue(job.contains("configChains"));
        assertFalse(job.contains("configTemplates"), "no sidebar entry for the old URL");
    }

    // ------------------------------------------------------------------------------------------
    // Suppression durability (S16)
    // ------------------------------------------------------------------------------------------

    @Test
    public void everyKeyLikeStringField_inMainSources_carriesThePlaintextStorageSuppression() throws Exception {
        Pattern field = Pattern.compile(
                "^\\s+(?:(?:private|protected|public|static|final|transient|volatile)\\s+)+String\\s+"
                        + "(configKey|projectKey|storageKey)\\b[^(]*;\\s*$");
        Path root = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(root), "run from the module directory");
        int checked = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = field.matcher(lines.get(i));
                    if (!m.matches()) {
                        continue;
                    }
                    checked++;
                    boolean suppressed = (i >= 1 && lines.get(i - 1).contains("lgtm[jenkins/plaintext-storage]"))
                            || lines.get(i).contains("lgtm[jenkins/plaintext-storage]");
                    assertTrue(suppressed, file + ":" + (i + 1) + " " + m.group(1) + " lacks the suppression");
                }
            }
        }
        assertTrue(checked >= 14, "expected the known fields to be found, found " + checked);
    }
}
