package io.jenkins.plugins.jobconfigchain.safetynet;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigDeploymentBinding;
import io.jenkins.plugins.jobconfigchain.model.ResolvedBaseVersion;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigDeploymentBindingRepository;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.addBaseVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.addOverrideVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoText;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logValue;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBaseWithSecret;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedCredential;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.setup;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.validate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Safety net for business process 5 — redeploy of an older build replays the exact config that
 * build originally shipped with (build-version-pinned replay; pipeline-steps.md "Build-identity
 * pinning", user-flows.md rollback flow).
 *
 * <p>Existing coverage (ConfigChainSubstituteStepTest fr25/fr54/fr54b/fr103) proves replay of a
 * changed BASE across jobs and across builds. This class adds the combination the owner's rule is
 * really about: after the bases, the job's OWN override versions AND the job's base chain itself have
 * all changed, build #1 is still replayed byte-identically — including a secret placeholder resolved
 * from Credentials, a pinned and an active chain reference, and the replay driven through
 * {@code setup(redeployFromRun)} — and the original build's recorded binding is not disturbed.</p>
 *
 * <p>Characterization test: green on current code, must stay green after the fixes.</p>
 */
@WithJenkins
public class RedeployReplaySafetyNetTest {

    private static final String FILE = "app.cfg";
    private static final String OUT = "TXT";
    private static final String TEMPLATE = "host=#{Db.Host}# port=#{Db.Port}# own=#{Own}# pw=#{Pw}#";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    public void redeployOfBuild1_replaysBasesOwnOverrideChainAndSecretExactly_afterEverythingChanged()
            throws Exception {
        seedCredential("rd-cred", "S3cr3t");
        seedBaseWithSecret("rd-a", "Pw", "rd-cred",
                "{\"Db\":{\"Host\":\"a1-host\"},\"Pw\":\"" + PLACEHOLDER + "\"}");
        seedBase("rd-b", "{\"Db\":{\"Port\":1111}}");
        WorkflowJob job = newPipelineJob(j, "rd-job");
        attachOverride(job, "{\"Own\":\"own-v1\"}",
                chainOf(BaseConfigReference.active("rd-a"), BaseConfigReference.pinned("rd-b", 1)), null);

        // Build #1: the deploy that will later be redeployed.
        WorkflowRun build1 = runOk(j, job, writeText(FILE, TEMPLATE), substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));
        String shippedByBuild1 = logValue(j, build1, OUT);
        assertEquals("host=a1-host port=1111 own=own-v1 pw=S3cr3t", shippedByBuild1);

        // Everything that can change, changes: both bases get a newer active version, the job's own
        // override gets a newer active version.
        addBaseVersion("rd-a", "{\"Db\":{\"Host\":\"a2-host\"},\"Pw\":\"" + PLACEHOLDER + "\"}", "a2", true);
        addBaseVersion("rd-b", "{\"Db\":{\"Port\":2222}}", "b2", true);
        addOverrideVersion(job, "{\"Own\":\"own-v2\"}",
                chainOf(BaseConfigReference.active("rd-a"), BaseConfigReference.pinned("rd-b", 1)), true);

        // Build #2: an ordinary deploy now ships the NEW config (so replay below is not vacuous).
        WorkflowRun build2 = runOk(j, job, writeText(FILE, TEMPLATE), substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));
        String shippedByBuild2 = logValue(j, build2, OUT);
        assertEquals("host=a2-host port=1111 own=own-v2 pw=S3cr3t", shippedByBuild2);
        assertNotEquals(shippedByBuild1, shippedByBuild2);

        // The job's base chain itself is then re-pointed (own v3 with a different chain).
        addOverrideVersion(job, "{\"Own\":\"own-v3\"}", chainOf(BaseConfigReference.active("rd-b")), true);

        // Build #3: redeploy of build 1 via the step parameter.
        WorkflowRun build3 = runOk(j, job, writeText(FILE, TEMPLATE),
                substitute("file: '" + FILE + "', redeployFromRun: '1'"), echoText(OUT, FILE));
        assertEquals(shippedByBuild1, logValue(j, build3, OUT),
                "redeploy of build 1 must replay exactly what build 1 shipped");
        j.assertLogContains("is pinned to base chain", build3);

        // Build #4: the same replay driven through setup (stored redeployFromRun), validate pinned to
        // the job version build 1 used, then zero-arg substitute.
        WorkflowRun build4 = runOk(j, job,
                setup("file: '" + FILE + "', redeployFromRun: '1'"),
                writeText(FILE, TEMPLATE),
                validate("version: 1"),
                substitute(""),
                echoText(OUT, FILE));
        assertEquals(shippedByBuild1, logValue(j, build4, OUT));

        // Recorded bindings: build 1 untouched by the replays; replays forward-chain their own binding.
        ConfigDeploymentBindingRepository bindings = new ConfigDeploymentBindingRepository();
        List<ResolvedBaseVersion> frozenChain1 = List.of(new ResolvedBaseVersion("rd-a", 1),
                new ResolvedBaseVersion("rd-b", 1));
        ConfigDeploymentBinding binding1 = bindings.find(build1.getExternalizableId());
        assertNotNull(binding1);
        assertEquals(frozenChain1, binding1.getResolvedBaseChain());
        assertEquals(1, binding1.getOwnConfigVersionNumber());

        ConfigDeploymentBinding binding2 = bindings.find(build2.getExternalizableId());
        assertNotNull(binding2);
        assertEquals(List.of(new ResolvedBaseVersion("rd-a", 2), new ResolvedBaseVersion("rd-b", 1)),
                binding2.getResolvedBaseChain());
        assertEquals(2, binding2.getOwnConfigVersionNumber());

        ConfigDeploymentBinding binding3 = bindings.find(build3.getExternalizableId());
        assertNotNull(binding3);
        assertEquals(frozenChain1, binding3.getResolvedBaseChain());
        assertEquals(1, binding3.getOwnConfigVersionNumber());
    }
}
