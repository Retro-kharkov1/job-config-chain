package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Arrays;
import java.util.List;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.propertyOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.saveJobVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED TEST for review finding C11 — EXPECTED TO FAIL on the current code, expected to PASS once the
 * job-property persistence fix lands.
 *
 * <p><b>The defect:</b> {@code JobConfigTemplateProperty.DescriptorImpl#isApplicable} returns
 * {@code false}, so the property's descriptor is not in the list Jenkins rebuilds the job's
 * properties from when the job's /configure form is submitted. Submitting that form (even
 * unchanged) therefore rebuilds the property list WITHOUT the property: the job silently loses its
 * whole override history. The same happens when a stale /configure page is submitted after the
 * override was changed through the plugin's own save path.</p>
 *
 * <p><b>Why these tests are red now and what turns them green:</b> they assert the property, all
 * its versions (content, note, base chain), the active pointer, content type and secrets manifest
 * survive {@code configRoundtrip}, survive a reload from disk, and that a version added after the
 * form was loaded is NOT rolled back / lost by submitting the stale form. A fix that merely keeps the
 * instance captured when the form was rendered would still fail the race test; the live property
 * must be preserved.</p>
 *
 * <p>Fixed in P1 (descriptor {@code isApplicable=true}, {@code reconfigure} returns the live
 * instance); the former red tag is removed so these now run in the normal suite. Do not weaken these
 * assertions.</p>
 */
@WithJenkins
public class JobPropertyConfigureRoundtripC11RedTest {

    private static final String V1 = "{\"a\":1}";
    private static final String V2 = "{\"a\":2,\"Db\":{\"Password\":\"" + PLACEHOLDER + "\"}}";
    private static final String V3 = "{\"a\":3}";
    private static final List<BaseConfigReference> CHAIN = Arrays.asList(
            BaseConfigReference.active("c11-base"), BaseConfigReference.pinned("c11-base-2", 3));

    /** Builds an override with three versions, version 2 active, a secret binding and a base chain. */
    private static JobConfigTemplateProperty threeVersionProperty() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion(V1, "note-1", "alice", 1L, CHAIN, "JSON");
        property.addVersion(V2, "note-2", "bob", 2L, CHAIN, null);
        property.addVersion(V3, "note-3", "carol", 3L, CHAIN, null);
        property.activate(2);
        property.putSecretManifestEntry("Db.Password", "cred-a");
        return property;
    }

    private static void assertIntact(JobConfigTemplateProperty property, String when) {
        assertNotNull(property, when + ": the job's override property must survive");
        assertEquals(3, property.getVersions().size(), when + ": all versions must survive");
        assertEquals(V1, property.getVersion(1).getContentJson(), when);
        assertEquals(V2, property.getVersion(2).getContentJson(), when);
        assertEquals(V3, property.getVersion(3).getContentJson(), when);
        assertEquals("note-2", property.getVersion(2).getNote(), when);
        assertEquals("bob", property.getVersion(2).getAuthor(), when);
        assertEquals(CHAIN, property.getVersion(2).getBaseChain(), when + ": base chain must survive");
        assertEquals(2, property.getActiveVersionNumber(), when + ": active pointer must survive");
        assertEquals(ContentType.JSON, property.getContentType(), when);
        assertEquals("cred-a", property.getSecretsManifest().get("Db.Password"), when);
    }

    @Test
    public void configRoundtrip_freestyleJob_keepsPropertyAndAllVersions_alsoAfterReloadFromDisk(JenkinsRule j)
            throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("c11-freestyle");
        job.addProperty(threeVersionProperty());
        job.save();
        assertIntact(propertyOf(j, "c11-freestyle"), "before the configure submit (test setup sanity)");

        j.configRoundtrip(job);

        assertIntact(propertyOf(j, "c11-freestyle"), "after configRoundtrip, in memory");
        assertTrue(job.getConfigFile().asString().contains(JobConfigTemplateProperty.class.getName()),
                "after configRoundtrip the property must still be written to config.xml");
        j.jenkins.reload();
        assertIntact(propertyOf(j, "c11-freestyle"), "after configRoundtrip and reload from disk");
    }

    @Test
    public void configRoundtrip_pipelineJob_keepsPropertyAndAllVersions_alsoAfterReloadFromDisk(JenkinsRule j)
            throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "c11-pipeline");
        job.setDefinition(new CpsFlowDefinition("echo 'x'", true));
        job.addProperty(threeVersionProperty());
        job.save();

        j.configRoundtrip(job);

        assertIntact(propertyOf(j, "c11-pipeline"), "after configRoundtrip, in memory");
        j.jenkins.reload();
        assertIntact(propertyOf(j, "c11-pipeline"), "after configRoundtrip and reload from disk");
    }

    @Test
    public void staleConfigureFormSubmittedAfterANewVersionWasSaved_keepsTheNewerVersion(JenkinsRule j)
            throws Exception {
        j.jenkins.setCrumbIssuer(null);
        FreeStyleProject job = j.createFreeStyleProject("c11-race");
        JobConfigTemplateProperty initial = new JobConfigTemplateProperty();
        initial.addVersion(V1, "note-1", "alice", 1L, CHAIN, "JSON");
        initial.activate(1);
        job.addProperty(initial);
        job.save();

        // 1. An admin opens the job's /configure page (the form now holds a snapshot of the job).
        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage configurePage = webClient.getPage(job, "configure");
        HtmlForm staleForm = configurePage.getFormByName("config");

        // 2. Meanwhile a new version is saved through the plugin's own save path and activated.
        assertTrue(saveJobVersion(j, "c11-race", V3, "newer-while-form-open",
                CHAIN.toArray(new BaseConfigReference[0]), true).redirected());
        assertEquals(2, propertyOf(j, "c11-race").getVersions().size());

        // 3. The stale /configure form is submitted.
        j.submit(staleForm);

        JobConfigTemplateProperty after = propertyOf(j, "c11-race");
        assertNotNull(after, "submitting the stale configure form must not drop the override");
        assertEquals(2, after.getVersions().size(), "the version saved while the form was open must survive");
        assertEquals(V3, after.getVersion(2).getContentJson());
        assertEquals("newer-while-form-open", after.getVersion(2).getNote());
        assertEquals(2, after.getActiveVersionNumber(), "the newer active pointer must not be rolled back");

        j.jenkins.reload();
        JobConfigTemplateProperty reloaded = propertyOf(j, "c11-race");
        assertNotNull(reloaded, "override must also be on disk");
        assertEquals(2, reloaded.getVersions().size());
    }
}
