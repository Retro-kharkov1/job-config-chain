package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.model.Result;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoText;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logValue;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.run;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBaseWithSecret;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedCredential;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.setup;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.validate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety net for business process 4 — deploy: the pipeline steps (setup / validate / substitute)
 * validate and substitute the target config file in the workspace, with secret placeholders resolved
 * from Credentials (pipeline-steps.md, token-syntax.md).
 *
 * <p>Characterization tests: green on current code, must stay green after the fixes. Already
 * covered elsewhere (not repeated): happy paths and missing / orphaned keys in all three content
 * formats, remaining-token detection, secret resolve / missing credential, setup state precedence
 * and parallel-branch refusal (ConfigChainSubstituteStepTest, ConfigChainValidateStepTest,
 * SetupConfigChainStepTest). This class adds the end-to-end combinations and the gaps: job's OWN
 * secrets manifest, manifest precedence, env override rules, missing target file, file untouched on
 * failure.</p>
 */
@WithJenkins
public class DeployPipelineSafetyNetTest {

    private static final String FILE = "app.cfg";
    private static final String OUT = "TXT";
    private static final String ERR = "ERR";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    public void setupThenValidateThenSubstitute_withZeroArgCalls_producesTheFullFileFromBaseOverrideAndSecret()
            throws Exception {
        seedCredential("dp-db-pass", "Pa55w0rd");
        seedBaseWithSecret("dp-base", "Db.Password", "dp-db-pass",
                "{\"Db\":{\"Host\":\"base-host\",\"Password\":\"" + PLACEHOLDER + "\"},\"App\":{\"Name\":\"demo\"}}");
        WorkflowJob job = newPipelineJob(j, "dp-full");
        attachOverride(job, "{\"App\":{\"Name\":\"demo-override\"},\"Env\":{\"Region\":\"eu\"}}",
                chainOf(BaseConfigReference.active("dp-base")), null);

        WorkflowRun run = runOk(j, job,
                setup("file: '" + FILE + "'"),
                writeText(FILE, "host=#{Db.Host}# pw=#{Db.Password}# name=#{App.Name}# region=#{Env.Region}#"),
                validate(""),
                substitute(""),
                echoText(OUT, FILE));

        assertEquals("host=base-host pw=Pa55w0rd name=demo-override region=eu", logValue(j, run, OUT));
        j.assertLogContains("Validation passed", run);
    }

    @Test
    public void nonSecretEnvVarNamedLikeTheDottedPathWins_butASecretPathNeverTakesAnEnvVar() throws Exception {
        seedCredential("dp-env-cred", "cred-value");
        seedBaseWithSecret("dp-env-base", "Db.Pw", "dp-env-cred",
                "{\"Db\":{\"Host\":\"base-host\",\"Pw\":\"" + PLACEHOLDER + "\"}}");
        WorkflowJob job = newPipelineJob(j, "dp-env");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("dp-env-base")), null);

        WorkflowRun run = runOk(j, job,
                writeText(FILE, "host=#{Db.Host}# pw=#{Db.Pw}#"),
                "withEnv(['Db.Host=env-host', 'Db.Pw=env-pw']) {",
                "  " + substitute("file: '" + FILE + "'"),
                "}",
                echoText(OUT, FILE));

        assertEquals("host=env-host pw=cred-value", logValue(j, run, OUT));
    }

    @Test
    public void jobsOwnSecretsManifestResolvesCredentials_andBeatsTheBaseManifestOnTheSamePath() throws Exception {
        seedCredential("dp-base-cred", "from-base-cred");
        seedCredential("dp-own-cred", "from-own-cred");
        seedBaseWithSecret("dp-sec-base", "Api.Key", "dp-base-cred",
                "{\"Api\":{\"Key\":\"" + PLACEHOLDER + "\"}}");
        WorkflowJob job = newPipelineJob(j, "dp-own-secrets");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.putSecretManifestEntry("Api.Key", "dp-own-cred");
        property.putSecretManifestEntry("Own.Token", "dp-base-cred");
        int v = property.addVersion("{\"Api\":{\"Key\":\"" + PLACEHOLDER + "\"},\"Own\":{\"Token\":\""
                        + PLACEHOLDER + "\"}}", "seed", ChainTestKit.AUTHOR, 1L,
                chainOf(BaseConfigReference.active("dp-sec-base")), null);
        property.activate(v);
        job.addProperty(property);

        WorkflowRun run = runOk(j, job,
                writeText(FILE, "key=#{Api.Key}# token=#{Own.Token}#"),
                substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));

        assertEquals("key=from-own-cred token=from-base-cred", logValue(j, run, OUT));
    }

    @Test
    public void failedSubstituteLeavesTheTargetFileUntouched_andNamesTheMissingKey() throws Exception {
        seedBase("dp-fail-base", "{\"a\":\"1\"}");
        WorkflowJob job = newPipelineJob(j, "dp-fail");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("dp-fail-base")), null);

        WorkflowRun run = runOk(j, job,
                writeText(FILE, "a=#{a}# n=#{Nope}#"),
                "try { " + substitute("file: '" + FILE + "'") + " } catch (e) { echo \"" + ERR + ":${e.message}\" }",
                echoText(OUT, FILE));

        assertEquals("a=#{a}# n=#{Nope}#", logValue(j, run, OUT), "the file must not be partially substituted");
        assertTrue(logValue(j, run, ERR).contains("Missing config keys"));
        assertTrue(logValue(j, run, ERR).contains("#{Nope}#"));
    }

    @Test
    public void missingTargetFile_failsLoudlyInBothSteps() throws Exception {
        seedBase("dp-nofile-base", "{\"a\":\"1\"}");
        WorkflowJob job = newPipelineJob(j, "dp-nofile");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("dp-nofile-base")), null);

        WorkflowRun validateRun = run(j, job, Result.FAILURE, validate("file: 'absent.cfg'"));
        j.assertLogContains("Target config file not found: absent.cfg", validateRun);

        WorkflowRun substituteRun = run(j, job, Result.FAILURE, substitute("file: 'absent.cfg'"));
        j.assertLogContains("Target config file not found: absent.cfg", substituteRun);
    }

    @Test
    public void stepFunctionNamesAreRegistered() throws Exception {
        // Guards the one-line constants in ChainTestKit: if a step is renamed, this fails first and
        // names the constant to update, instead of every pipeline test failing with "No such DSL method".
        seedBase("dp-names-base", "{\"a\":\"1\"}");
        WorkflowJob job = newPipelineJob(j, "dp-names");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("dp-names-base")), null);

        WorkflowRun run = runOk(j, job, writeText(FILE, "a=#{a}#"), validate("file: '" + FILE + "'"),
                substitute("file: '" + FILE + "'"));

        j.assertLogContains("Validation passed", run);
        j.assertLogContains("Substituted " + FILE, run);
    }
}
