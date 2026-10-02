package io.jenkins.plugins.jobconfigchain.safetynet;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.activateJobVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoText;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logValue;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.propertyOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.saveJobVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety net for business process 3 — job-level override on top of the base (job-scoped-config.md):
 * save the job's own versions through the job's Config Templates page, activate / roll back, pin a
 * version from the pipeline, and observe the effect on the deploy output and on the persisted
 * property (also after a reload from disk).
 *
 * <p>Characterization tests: green on current code, must stay green after the fixes. Note this goes
 * through the plugin's OWN save path ({@code ChainTestKit#saveJobVersion}); the job /configure form
 * path is the subject of the red C11 test, not of this class.</p>
 */
@WithJenkins
public class JobOverrideFlowTest {

    private static final String BASE = "jo-base";
    private static final String JOB = "jo-job";
    private static final String FILE = "app.cfg";
    private static final String OUT = "TXT";
    private static final String TEMPLATE = "host=#{Svc.Host}# port=#{Svc.Port}#";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    private String deploy(WorkflowJob job, String substituteArgs) throws Exception {
        WorkflowRun run = runOk(j, job, writeText(FILE, TEMPLATE), substitute(substituteArgs),
                echoText(OUT, FILE));
        return logValue(j, run, OUT);
    }

    @Test
    public void jobOverrideSavedThroughItsPage_layersOnBase_versionsRollbackAndPinWork_andSurviveReload()
            throws Exception {
        seedBase(BASE, "{\"Svc\":{\"Host\":\"base-host\",\"Port\":80}}");
        WorkflowJob job = newPipelineJob(j, JOB);
        BaseConfigReference[] chain = {BaseConfigReference.active(BASE)};

        // First save on a job that has no override yet creates it, with the base chain recorded.
        assertNull(propertyOf(j, JOB));
        assertTrue(saveJobVersion(j, JOB, "{\"Svc\":{\"Port\":8080}}", "o1", chain, true).redirected());
        JobConfigTemplateProperty afterFirst = propertyOf(j, JOB);
        assertNotNull(afterFirst, "first save must create the job's override");
        assertEquals(1, afterFirst.getVersions().size());
        assertEquals(1, afterFirst.getActiveVersionNumber());
        assertEquals(1, afterFirst.getVersion(1).getBaseChain().size());
        assertEquals(BASE, afterFirst.getVersion(1).getBaseChain().get(0).getProjectKey());
        assertEquals("host=base-host port=8080", deploy(job, "file: '" + FILE + "'"));

        // Second version saved WITHOUT activate: appended, but the active one still wins.
        assertTrue(saveJobVersion(j, JOB, "{\"Svc\":{\"Port\":9090}}", "o2", chain, false).redirected());
        JobConfigTemplateProperty afterSecond = propertyOf(j, JOB);
        assertEquals(2, afterSecond.getVersions().size());
        assertEquals(1, afterSecond.getActiveVersionNumber());
        assertEquals("host=base-host port=8080", deploy(job, "file: '" + FILE + "'"));

        // Activate 2, then roll back to 1: deploy follows the active pointer.
        assertTrue(activateJobVersion(j, JOB, 2).json().getBoolean("ok"));
        assertEquals("host=base-host port=9090", deploy(job, "file: '" + FILE + "'"));
        assertTrue(activateJobVersion(j, JOB, 1).json().getBoolean("ok"));
        assertEquals("host=base-host port=8080", deploy(job, "file: '" + FILE + "'"));

        // An explicit pipeline pin of version 2 wins over the active pointer (still 1).
        assertEquals("host=base-host port=9090", deploy(job, "file: '" + FILE + "', version: 2"));

        // Refused saves leave history untouched.
        assertFalse(saveJobVersion(j, JOB, "{ not valid json", "bad", chain, false).redirected());
        assertFalse(saveJobVersion(j, JOB, "{\"Svc\":{\"Port\":1}}", "", chain, false).redirected());
        assertEquals(2, propertyOf(j, JOB).getVersions().size());

        // Everything above is persisted in the job's config.xml, not only held in memory.
        j.jenkins.reload();
        JobConfigTemplateProperty reloaded = propertyOf(j, JOB);
        assertNotNull(reloaded, "override must survive a reload from disk");
        assertEquals(2, reloaded.getVersions().size());
        assertEquals(1, reloaded.getActiveVersionNumber());
        assertEquals("{\"Svc\":{\"Port\":9090}}", reloaded.getVersion(2).getContentJson());
        assertEquals("o2", reloaded.getVersion(2).getNote());
    }
}
