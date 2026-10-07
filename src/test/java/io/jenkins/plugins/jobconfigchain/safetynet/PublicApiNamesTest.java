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
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public names of the plugin: the {@code configChain*}/{@code setupConfigChain} steps and the
 * {@code /configChains} URLs work end to end, and nothing under the pre-release names
 * ({@code configTemplate*} steps, {@code /configTemplates} URLs) is registered any more - the plugin was never
 * hosted, so no compatibility layer is kept.
 */
@WithJenkins
public class PublicApiNamesTest {

    private static final String FILE = "app.cfg";
    private static final String BIN = "BIN";
    private static final String TEMPLATE = "host=#{Db.Host}# pw=#{Db.Password}# own=#{Own}#";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    // ------------------------------------------------------------------------------------------
    // Pipeline steps
    // ------------------------------------------------------------------------------------------

    /** A job with a base, a secret and an own override. */
    private WorkflowJob job(String name) throws Exception {
        WorkflowJob job = newPipelineJob(j, name);
        attachOverride(job, "{\"Own\":\"own-value\"}", chainOf(BaseConfigReference.active("p3-base")), null);
        return job;
    }

    private void seedWorld() throws Exception {
        seedCredential("p3-cred", "P3S3cret");
        seedBaseWithSecret("p3-base", "Db.Password", "p3-cred",
                "{\"Db\":{\"Host\":\"p3-host\",\"Password\":\"" + PLACEHOLDER + "\"}}");
    }

    private static String[] deployScript(String redeployFrom) {
        String setupArgs = "file: '" + FILE + "'" + (redeployFrom == null ? "" : ", redeployFromRun: '" + redeployFrom + "'");
        return new String[] {
                setup(setupArgs),
                writeText(FILE, TEMPLATE),
                validate(""),
                substitute(""),
                echoBytes(BIN, FILE)
        };
    }

    @Test
    public void deploy_liveThenRedeploy_replaysTheBuildTheConfigShippedWith() throws Exception {
        seedWorld();
        WorkflowJob deployJob = job("p3-new");

        WorkflowRun live = run(j, deployJob, Result.SUCCESS, deployScript(null));
        // The base moves on, then the job redeploys its own build #1: the frozen binding must replay.
        ChainTestKit.addBaseVersion("p3-base",
                "{\"Db\":{\"Host\":\"p3-host-v2\",\"Password\":\"" + PLACEHOLDER + "\"}}", "bump", true);
        WorkflowRun redeploy = run(j, deployJob, Result.SUCCESS, deployScript("1"));

        assertEquals("host=p3-host pw=P3S3cret own=own-value",
                new String(logBytes(j, live, BIN), StandardCharsets.UTF_8), "live deploy");
        assertEquals("host=p3-host pw=P3S3cret own=own-value",
                new String(logBytes(j, redeploy, BIN), StandardCharsets.UTF_8),
                "redeploy of build #1 replays the config build #1 shipped with");
        assertTrue(stepLogLines(j.getLog(live), "p3-new").contains("[configTemplateSync] Validation passed"),
                "log prefix is unchanged: " + stepLogLines(j.getLog(live), "p3-new"));
        j.assertLogContains("[Pipeline] configChainSubstitute", live);
    }

    @Test
    public void validate_failsOnMissingKeys_namingThem() throws Exception {
        seedWorld();
        WorkflowJob failJob = job("p3-fail-new");
        String bad = "writeFile file: 'app.cfg', text: '#{Db.Host}# #{No.Such}#'";

        WorkflowRun failed = run(j, failJob, Result.FAILURE, setup("file: 'app.cfg'"), bad, validate(""));

        String lines = stepLogLines(j.getLog(failed), "p3-fail-new");
        assertTrue(lines.contains("Missing config keys"), lines);
        assertTrue(lines.contains("#{No.Such}#"), lines);
    }

    @Test
    public void explicitUseBaseCall_substitutesFromTheNamedGlobalSet() throws Exception {
        seedBase("p3-explicit", "{\"A\":{\"B\":\"c\"}}");
        WorkflowJob explicitJob = newPipelineJob(j, "p3-ex-new");

        WorkflowRun n = run(j, explicitJob, Result.SUCCESS, writeText(FILE, "x=#{A.B}#"),
                substitute("file: '" + FILE + "', useBase: true, configKey: 'p3-explicit'"), echoBytes(BIN, FILE));

        assertEquals("x=c", new String(logBytes(j, n, BIN), StandardCharsets.UTF_8));
    }

    @Test
    public void setupStep_refusedInsideParallel_namingTheStepNames() throws Exception {
        seedWorld();
        WorkflowJob parJob = job("p3-par-new");

        WorkflowRun n = ChainTestKit.runScript(j, parJob, Result.FAILURE,
                "node { parallel a: { " + setup("file: 'x'") + " }, b: { echo 'b' } }");

        j.assertLogContains("[configTemplateSync] setupConfigChain() is not supported inside a parallel {} branch", n);
        j.assertLogContains("Call configChainValidate/configChainSubstitute with", n);
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
    public void snippetGenerator_listsTheThreeSteps_andRegistersNoPreReleaseName() throws Exception {
        Snippetizer snippetizer = j.jenkins.getExtensionList(Snippetizer.class).get(0);

        assertEquals(Set.of("configChainSubstitute", "configChainValidate", "setupConfigChain"),
                functionNames(snippetizer, false), "normal (non-advanced) entries");
        assertEquals(Set.of(), functionNames(snippetizer, true), "no advanced/deprecated alias entries");
        assertTrue(j.jenkins.getExtensionList(StepDescriptor.class).stream()
                        .noneMatch(d -> d.getFunctionName().matches("(configTemplate|setupConfigTemplate).*")),
                "no configTemplate* step is registered");
    }

    @Test
    public void snippetGenerator_generatesTheStepSnippets() throws Exception {
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
    public void preReleaseUrls_areGone_neitherServedNorRedirected() throws Exception {
        seedBase("p3-gone", "{\"a\":1}");
        newPipelineJob(j, "p3-gonejob");

        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
            for (String url : List.of(
                    "configTemplates/",
                    "configTemplates/p3-gone/",
                    "configTemplates/p3-gone/submitSave",
                    "manage/configTemplates/p3-gone/",
                    "job/p3-gonejob/configTemplates/",
                    "job/p3-gonejob/configTemplates/submitSave")) {
                WebResponse reply = request(method, url);
                assertEquals(404, reply.getStatusCode(), method + " " + url);
                assertTrue(reply.getResponseHeaderValue("Location") == null, method + " " + url + " must not redirect");
            }
        }
        // Nothing was saved through the old path.
        assertEquals(1, new io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository()
                .findCommon("p3-gone").getVersions().size());
    }

    @Test
    public void manageJenkinsAndJobSidebar_listOnlyTheConfigChainsEntries() throws Exception {
        newPipelineJob(j, "p3-sidebar");
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);

        String manage = wc.goTo("manage").getWebResponse().getContentAsString();
        assertTrue(manage.contains("Config Chains"));
        assertFalse(manage.contains("/configTemplates"), "no link to a pre-release URL");

        String job = wc.goTo("job/p3-sidebar/").getWebResponse().getContentAsString();
        assertTrue(job.contains("configChains"));
        assertFalse(job.contains("configTemplates"), "no sidebar entry for a pre-release URL");
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
        assertTrue(checked >= 8, "expected the known fields to be found, found " + checked);
    }
}
