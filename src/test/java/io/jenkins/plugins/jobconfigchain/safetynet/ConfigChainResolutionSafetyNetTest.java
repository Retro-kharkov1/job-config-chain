package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.model.Result;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.addBaseVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoText;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logValue;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.run;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Safety net for business process 1 — config chain resolution: base config(s) -> job override ->
 * effective config (merge semantics and base-chain references). Observed only through what a deploy
 * produces (pipeline run output), so the tests survive internal class/step renames.
 *
 * <p>All tests here are characterization tests: they pass on the current code and must keep passing
 * unchanged after the review/security fixes. Doc references: base-chains.md, job-scoped-config.md,
 * multi-format-content.md.</p>
 *
 * <p>Already covered elsewhere (not repeated): single-base fold, own-override + base in one call,
 * every row of the resolution matrix, mismatched content types, missing project / pinned version,
 * XML and YAML variants of the happy path (see ConfigChainSubstituteStepTest, StepSupportTest,
 * EffectiveConfigResolverTest, TreeMergePatchTest).</p>
 */
@WithJenkins
public class ConfigChainResolutionSafetyNetTest {

    private static final String FILE = "app.cfg";
    private static final String OUT = "TXT";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    public void laterBaseWinsOnConflict_overrideWinsOverAllBases_untouchedSiblingsSurviveDeepMerge() throws Exception {
        seedBase("res-a", "{\"Db\":{\"Host\":\"a-host\",\"Port\":1},\"Flag\":true,\"OnlyA\":\"a\"}");
        seedBase("res-b", "{\"Db\":{\"Host\":\"b-host\"},\"OnlyB\":\"b\"}");
        WorkflowJob job = newPipelineJob(j, "res-layers");
        attachOverride(job, "{\"Db\":{\"Port\":2},\"Own\":\"o\"}",
                chainOf(BaseConfigReference.active("res-a"), BaseConfigReference.active("res-b")), null);

        WorkflowRun run = runOk(j, job,
                writeText(FILE, "host=#{Db.Host}# port=#{Db.Port}# flag=#{Flag}# a=#{OnlyA}# b=#{OnlyB}# own=#{Own}#"),
                substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));

        assertEquals("host=b-host port=2 flag=true a=a b=b own=o", logValue(j, run, OUT));
    }

    @Test
    public void chainOrderDecidesTheWinner_reversingTheChainFlipsTheResult() throws Exception {
        seedBase("res-x", "{\"k\":\"from-x\"}");
        seedBase("res-y", "{\"k\":\"from-y\"}");
        WorkflowJob xThenY = newPipelineJob(j, "res-order-xy");
        attachOverride(xThenY, "{}",
                chainOf(BaseConfigReference.active("res-x"), BaseConfigReference.active("res-y")), null);
        WorkflowJob yThenX = newPipelineJob(j, "res-order-yx");
        attachOverride(yThenX, "{}",
                chainOf(BaseConfigReference.active("res-y"), BaseConfigReference.active("res-x")), null);

        WorkflowRun runXY = runOk(j, xThenY, writeText(FILE, "k=#{k}#"), substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));
        WorkflowRun runYX = runOk(j, yThenX, writeText(FILE, "k=#{k}#"), substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));

        assertEquals("k=from-y", logValue(j, runXY, OUT));
        assertEquals("k=from-x", logValue(j, runYX, OUT));
    }

    @Test
    public void pinnedReferenceIgnoresLaterActiveVersion_activeReferenceFollowsIt() throws Exception {
        seedBase("res-pin", "{\"k\":\"v1\"}");
        int v2 = addBaseVersion("res-pin", "{\"k\":\"v2\"}", "second", true);
        assertEquals(2, v2);
        WorkflowJob pinnedJob = newPipelineJob(j, "res-pinned");
        attachOverride(pinnedJob, "{}", chainOf(BaseConfigReference.pinned("res-pin", 1)), null);
        WorkflowJob activeJob = newPipelineJob(j, "res-active");
        attachOverride(activeJob, "{}", chainOf(BaseConfigReference.active("res-pin")), null);

        WorkflowRun pinnedRun = runOk(j, pinnedJob, writeText(FILE, "k=#{k}#"),
                substitute("file: '" + FILE + "'"), echoText(OUT, FILE));
        WorkflowRun activeRun = runOk(j, activeJob, writeText(FILE, "k=#{k}#"),
                substitute("file: '" + FILE + "'"), echoText(OUT, FILE));

        assertEquals("k=v1", logValue(j, pinnedRun, OUT));
        assertEquals("k=v2", logValue(j, activeRun, OUT));
    }

    @Test
    public void overrideNullDeletesInheritedKey_soTheTokenForItBecomesAMissingKey() throws Exception {
        seedBase("res-null", "{\"a\":\"1\",\"b\":\"2\"}");
        WorkflowJob job = newPipelineJob(j, "res-null-delete");
        attachOverride(job, "{\"b\":null}", chainOf(BaseConfigReference.active("res-null")), null);

        WorkflowRun kept = runOk(j, job, writeText(FILE, "a=#{a}#"), substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));
        assertEquals("a=1", logValue(j, kept, OUT));
        assertFalse(j.getLog(kept).contains("Orphaned config keys"),
                "the deleted key must not linger as an orphaned key");

        WorkflowRun failed = run(j, job, Result.FAILURE, writeText(FILE, "a=#{a}# b=#{b}#"),
                substitute("file: '" + FILE + "'"));
        j.assertLogContains("Missing config keys", failed);
        j.assertLogContains("#{b}#", failed);
    }
}
