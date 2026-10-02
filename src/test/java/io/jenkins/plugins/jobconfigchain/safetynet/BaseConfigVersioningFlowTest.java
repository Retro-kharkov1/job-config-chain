package io.jenkins.plugins.jobconfigchain.safetynet;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.activateBaseVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoText;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logValue;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.saveBaseVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety net for business process 2 — base config editing and versioning: save a new version,
 * history, activation and rollback (config-sets-and-versioning.md). Driven through the plugin's HTTP
 * endpoints (the same ones the editor page uses) and observed through persisted state and the deploy
 * output, so the tests survive class renames; URL/field names live in {@link ChainTestKit}.
 *
 * <p>Characterization tests: green on current code, must stay green after the fixes.</p>
 */
@WithJenkins
public class BaseConfigVersioningFlowTest {

    private static final String KEY = "bv-flow";
    private static final String FILE = "app.cfg";
    private static final String OUT = "TXT";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    private static ConfigSet reload() {
        return new ConfigSetRepository().findCommon(KEY);
    }

    private String deployAndRead(WorkflowJob job) throws Exception {
        WorkflowRun run = runOk(j, job, writeText(FILE, "k=#{k}#"), substitute("file: '" + FILE + "'"),
                echoText(OUT, FILE));
        return logValue(j, run, OUT);
    }

    @Test
    public void saveAppendsVersionWithoutMovingActivePointer_activateAndRollbackFlipItAndChangeTheDeploy()
            throws Exception {
        // First save creates the base and (with the activate flag) makes version 1 active.
        assertTrue(saveBaseVersion(j, KEY, "{\"k\":\"one\"}", "first", true).redirected());
        ConfigSet afterFirst = reload();
        assertNotNull(afterFirst, "first save must create the base config");
        assertEquals(1, afterFirst.getVersions().size());
        assertEquals(1, afterFirst.getActiveVersionNumber());

        WorkflowJob job = newPipelineJob(j, "bv-flow-job");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active(KEY)), null);
        assertEquals("k=one", deployAndRead(job));

        // Second save WITHOUT the activate flag: history grows, the active pointer does not move.
        assertTrue(saveBaseVersion(j, KEY, "{\"k\":\"two\"}", "second", false).redirected());
        ConfigSet afterSecond = reload();
        assertEquals(2, afterSecond.getVersions().size());
        assertEquals(1, afterSecond.getActiveVersionNumber());
        assertEquals("k=one", deployAndRead(job));

        // Activate version 2: the deploy follows the active pointer.
        assertTrue(activateBaseVersion(j, KEY, 2).json().getBoolean("ok"));
        assertEquals(2, reload().getActiveVersionNumber());
        assertEquals("k=two", deployAndRead(job));

        // Rollback to version 1: history is append-only, nothing is lost, the deploy follows again.
        assertTrue(activateBaseVersion(j, KEY, 1).json().getBoolean("ok"));
        ConfigSet afterRollback = reload();
        assertEquals(1, afterRollback.getActiveVersionNumber());
        assertEquals(2, afterRollback.getVersions().size());
        assertEquals("{\"k\":\"two\"}", afterRollback.getVersion(2).getContentJson());
        assertEquals("second", afterRollback.getVersion(2).getNote());
        assertEquals("k=one", deployAndRead(job));
    }

    @Test
    public void rejectedSaves_blankNoteInvalidJson_neverAppendAVersion() throws Exception {
        assertTrue(saveBaseVersion(j, KEY, "{\"k\":\"one\"}", "first", true).redirected());

        assertFalse(saveBaseVersion(j, KEY, "{\"k\":\"two\"}", "", false).redirected(),
                "a save without a change note must be refused");
        assertFalse(saveBaseVersion(j, KEY, "{ not valid json", "broken", false).redirected(),
                "syntactically invalid content must be refused");

        ConfigSet unchanged = reload();
        assertEquals(1, unchanged.getVersions().size(), "refused saves must not append history");
        assertEquals("{\"k\":\"one\"}", unchanged.getVersion(1).getContentJson());
    }

    @Test
    public void activatingAnUnknownVersion_reportsFailureAndKeepsTheActivePointer() throws Exception {
        assertTrue(saveBaseVersion(j, KEY, "{\"k\":\"one\"}", "first", true).redirected());

        assertFalse(activateBaseVersion(j, KEY, 99).json().getBoolean("ok"));

        assertEquals(1, reload().getActiveVersionNumber());
    }
}
