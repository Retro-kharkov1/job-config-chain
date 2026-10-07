package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.model.Result;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runScript;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBaseWithSecret;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedCredential;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.setup;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.stepLogLines;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.validate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build state across a Jenkins restart, with the public step names: the build-scoped setup state a finished build
 * stores in build.xml is still there after a restart, a redeploy of that build replays the config it shipped with
 * byte for byte, and Pipeline builds suspended mid-run (right after setup, and after setup plus validate) resume and
 * finish with exactly the output of an uninterrupted run.
 *
 * <p>Replaces the old upgrade-from-a-pinned-build suite: the plugin has no earlier release to upgrade from, so what
 * matters is that the plugin's own persisted state survives a restart of the same version.</p>
 */
public class RestartDurabilityTest {

    private static final String FILE = "app.cfg";
    private static final String BIN = "BIN";
    private static final String CRED = "rd-cred";
    private static final String BASE = "rd-base";
    private static final String OWN = "{\"Own\":\"rd-own\"}";
    private static final String TEMPLATE = "host=#{Db.Host}# pw=#{Db.Password}# own=#{Own}#";
    private static final String BASE_V1 = "{\"Db\":{\"Host\":\"rd-host-v1\",\"Password\":\"" + PLACEHOLDER + "\"}}";
    private static final String BASE_V2 = "{\"Db\":{\"Host\":\"rd-host-v2\",\"Password\":\"" + PLACEHOLDER + "\"}}";
    private static final String GO = "go";
    private static final String GATE = "waitUntil(initialRecurrencePeriod: 10000) { currentBuild.description == '" + GO + "' }";
    private static final String GATE_POLL_MESSAGE = "Will try again";

    // The long first recurrence period keeps the build idle (between polls) while Jenkins shuts down, so the program
    // is persisted at a clean point instead of in the middle of evaluating the condition.

    /** Simple name of the invisible Run action the setup step persists in build.xml. */
    private static final String SETUP_ACTION_CLASS_SIMPLE_NAME = "ConfigTemplateSetupAction";

    // The sessions share the JVM, so a value captured in one session is readable in the next.
    private static byte[] build1Bytes;

    @RegisterExtension
    final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private static String nodeBlock(String... lines) {
        return "node {\n" + String.join("\n", lines) + "\n}";
    }

    private static String liveDeployScript() {
        return nodeBlock(
                setup("file: '" + FILE + "'"),
                writeText(FILE, TEMPLATE),
                validate(""),
                substitute(""),
                echoBytes(BIN, FILE));
    }

    private static String redeployOfBuild1Script() {
        return nodeBlock(
                setup("file: '" + FILE + "', redeployFromRun: '1'"),
                writeText(FILE, TEMPLATE),
                validate("version: 1"),
                substitute(""),
                echoBytes(BIN, FILE));
    }

    /** Paused right after setup; validate and substitute still to run. */
    private static String inflight1Script() {
        return setup("file: '" + FILE + "'") + "\n"
                + GATE + "\n"
                + nodeBlock(
                        writeText(FILE, TEMPLATE),
                        validate(""),
                        substitute(""),
                        echoBytes(BIN, FILE));
    }

    /** Setup and validate done in a finished node block; paused before substitute. */
    private static String inflight2Script() {
        return nodeBlock(
                        setup("file: '" + FILE + "'"),
                        writeText(FILE, TEMPLATE),
                        validate(""))
                + "\n" + GATE + "\n"
                + nodeBlock(
                        writeText(FILE, TEMPLATE),
                        substitute(""),
                        echoBytes(BIN, FILE));
    }

    private static void seedWorld() throws Exception {
        seedCredential(CRED, "RdS3cret");
        seedBaseWithSecret(BASE, "Db.Password", CRED, BASE_V1);
    }

    private static WorkflowJob jobWithOverride(JenkinsRule r, String name) throws Exception {
        WorkflowJob job = newPipelineJob(r, name);
        attachOverride(job, OWN, chainOf(BaseConfigReference.active(BASE)), null);
        return job;
    }

    private static void startAndAwaitPause(JenkinsRule r, WorkflowJob job, String script) throws Exception {
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage(GATE_POLL_MESSAGE, run);
    }

    private static String normalized(JenkinsRule r, WorkflowRun run, String jobName) throws Exception {
        return stepLogLines(r.getLog(run), jobName).replace(jobName, "@JOB@");
    }

    @Test
    void finishedBuild_keepsItsSetupState_andARedeployAfterRestartReplaysItByteForByte() throws Throwable {
        sessions.then(r -> {
            seedWorld();
            WorkflowJob job = jobWithOverride(r, "rd-job");
            WorkflowRun build1 = runScript(r, job, Result.SUCCESS, liveDeployScript());
            build1Bytes = logBytes(r, build1, BIN);
            assertEquals("host=rd-host-v1 pw=RdS3cret own=rd-own", new String(build1Bytes,
                    java.nio.charset.StandardCharsets.UTF_8));
        });
        sessions.then(r -> {
            WorkflowJob job = r.jenkins.getItemByFullName("rd-job", WorkflowJob.class);
            assertNotNull(job, "the job must load after the restart");
            WorkflowRun build1 = job.getBuildByNumber(1);
            assertNotNull(build1, "the finished build must load");
            assertEquals(Result.SUCCESS, build1.getResult());
            assertTrue(build1.getActions().stream()
                            .anyMatch(a -> SETUP_ACTION_CLASS_SIMPLE_NAME.equals(a.getClass().getSimpleName())),
                    "build #1 must still carry its stored setup action (build.xml)");

            // The base moves on; a redeploy of build #1 must still ship what build #1 shipped.
            ChainTestKit.addBaseVersion(BASE, BASE_V2, "bump host", true);
            WorkflowRun live = runScript(r, job, Result.SUCCESS, liveDeployScript());
            assertEquals("host=rd-host-v2 pw=RdS3cret own=rd-own",
                    new String(logBytes(r, live, BIN), java.nio.charset.StandardCharsets.UTF_8),
                    "a new live deploy sees the bumped base");
            WorkflowRun redeploy = runScript(r, job, Result.SUCCESS, redeployOfBuild1Script());
            assertArrayEquals(build1Bytes, logBytes(r, redeploy, BIN),
                    "a redeploy of build #1 must equal what build #1 originally shipped");
        });
    }

    @Test
    void buildSuspendedRightAfterSetup_resumesAndMatchesAnUninterruptedRun() throws Throwable {
        resumeAfterRestart("rd-inflight-1", inflight1Script());
    }

    @Test
    void buildSuspendedAfterSetupAndValidate_resumesAndMatchesAnUninterruptedRun() throws Throwable {
        resumeAfterRestart("rd-inflight-2", inflight2Script());
    }

    private void resumeAfterRestart(String jobName, String pausedScript) throws Throwable {
        sessions.then(r -> {
            seedWorld();
            startAndAwaitPause(r, jobWithOverride(r, jobName), pausedScript);
            // Returning ends the session: Jenkins shuts down with the build suspended, which writes its
            // program.dat / flow nodes.
        });
        sessions.then(r -> {
            WorkflowJob job = r.jenkins.getItemByFullName(jobName, WorkflowJob.class);
            assertNotNull(job, "the job must load after the restart");
            WorkflowRun run = job.getLastBuild();
            assertNotNull(run, "the suspended build must load");
            assertTrue(run.isBuilding(), "the build was suspended mid-run and must still be in progress");

            run.setDescription(GO);
            long deadline = System.currentTimeMillis() + 120_000;
            while (run.isBuilding() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
            }
            assertTrue(!run.isBuilding(), "resumed build did not finish; console: " + r.getLog(run));
            r.assertBuildStatusSuccess(run);

            // Reference: the same steps, never interrupted, on an identical job.
            WorkflowJob control = jobWithOverride(r, jobName + "-control");
            WorkflowRun reference = runScript(r, control, Result.SUCCESS, nodeBlock(
                    setup("file: '" + FILE + "'"),
                    writeText(FILE, TEMPLATE),
                    validate(""),
                    substitute(""),
                    echoBytes(BIN, FILE)));
            assertArrayEquals(logBytes(r, reference, BIN), logBytes(r, run, BIN),
                    "the resumed build must deploy the same bytes as an uninterrupted run");
            assertEquals(normalized(r, reference, jobName + "-control"), normalized(r, run, jobName),
                    "the resumed build must print the same plugin console lines");
            assertEquals("host=rd-host-v1 pw=RdS3cret own=rd-own",
                    new String(logBytes(r, run, BIN), java.nio.charset.StandardCharsets.UTF_8));
        });
    }
}
