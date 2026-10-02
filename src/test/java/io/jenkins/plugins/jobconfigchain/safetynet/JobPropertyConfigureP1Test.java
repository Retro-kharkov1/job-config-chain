package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.postForm;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.propertyOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.saveJobVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan P1 tests 1-6 (C11) beyond {@link JobPropertyConfigureRoundtripC11RedTest}. */
@WithJenkins
public class JobPropertyConfigureP1Test {

    private static final Pattern PROPERTY_XML = Pattern.compile(
            "<io\\.jenkins\\.plugins\\.jobconfigchain\\.ui\\.JobConfigTemplateProperty[ >].*?"
                    + "</io\\.jenkins\\.plugins\\.jobconfigchain\\.ui\\.JobConfigTemplateProperty>", Pattern.DOTALL);

    private static JobConfigTemplateProperty seeded() {
        JobConfigTemplateProperty p = new JobConfigTemplateProperty();
        p.addVersion("{\"a\":1}", "n1", "alice", 1L, chainOf(BaseConfigReference.active("p1-base")), "JSON");
        p.addVersion("{\"a\":2,\"Db\":{\"Password\":\"" + ChainTestKit.PLACEHOLDER + "\"}}", "n2", "bob", 2L,
                chainOf(BaseConfigReference.pinned("p1-base", 2)), null);
        p.activate(1);
        p.putSecretManifestEntry("Db.Password", "cred-a");
        return p;
    }

    private static String propertyXml(hudson.model.Job<?, ?> job) throws Exception {
        Matcher m = PROPERTY_XML.matcher(job.getConfigFile().asString());
        assertTrue(m.find(), "config.xml must contain the property");
        return m.group();
    }

    // 1 + 2
    @Test
    public void htmlUnitConfigureSave_keepsEverything_andPropertyXmlIsByteIdentical(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("p1-xml");
        job.addProperty(seeded());
        job.save();
        String before = propertyXml(job);

        HtmlPage page = j.createWebClient().getPage(job, "configure");
        j.submit(page.getFormByName("config"));
        assertEquals(before, propertyXml(job), "property XML must not change across Configure->Save");

        j.configRoundtrip(job);
        assertEquals(before, propertyXml(job), "property XML must not change across configRoundtrip");
        j.jenkins.reload();
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("p1-xml", FreeStyleProject.class);
        assertEquals(before, propertyXml(reloaded));
        JobConfigTemplateProperty p = propertyOf(j, "p1-xml");
        assertEquals(2, p.getVersions().size());
        assertEquals(1, p.getActiveVersionNumber());
        assertEquals("cred-a", p.getSecretsManifest().get("Db.Password"));
    }

    // 3
    @Test
    public void pipelineJobWithNoOtherProperty_saveStillPreservesProperty(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "p1-only");
        job.setDefinition(new CpsFlowDefinition("echo 'x'", true));
        job.addProperty(seeded());
        job.save();
        assertEquals(1, job.getAllProperties().size(), "ours must be the only property");

        HtmlPage page = j.createWebClient().getPage(job, "configure");
        HtmlForm form = page.getFormByName("config");
        assertTrue(form.asXml().contains("name=\"properties\""),
                "the form must post a 'properties' key even with nothing visible");
        j.submit(form);

        assertNotNull(propertyOf(j, "p1-only"));
        assertEquals(2, propertyOf(j, "p1-only").getVersions().size());
    }

    // 4
    @Test
    public void concurrentVersionSavesRacingConfigureSave_loseNothing(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("p1-concurrent");
        job.addProperty(seeded());
        job.save();
        int saves = 8;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread saver = new Thread(() -> {
            try {
                for (int i = 0; i < saves; i++) {
                    assertTrue(saveJobVersion(j, "p1-concurrent", "{\"a\":" + (10 + i) + "}", "race-" + i,
                            new BaseConfigReference[0], false).redirected());
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        saver.start();
        try {
            while (saver.isAlive()) {
                j.configRoundtrip(job);
            }
            j.configRoundtrip(job);
        } finally {
            saver.join();
        }
        assertNull(failure.get(), () -> "saver failed: " + failure.get());
        assertEquals(2 + saves, propertyOf(j, "p1-concurrent").getVersions().size(),
                "every saved version must survive the concurrent Configure->Save rounds");
    }

    // 5
    @Test
    public void propertiesStepAfterSave_keepsOurProperty_andGeneratorYieldsNoPropertyNoException(JenkinsRule j)
            throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "p1-step");
        job.setDefinition(new CpsFlowDefinition(
                "properties([[$class: 'BuildDiscarderProperty', strategy: "
                        + "[$class: 'LogRotator', numToKeepStr: '5']]])", true));
        j.buildAndAssertSuccess(job); // creates the tracker action
        job.addProperty(seeded());
        job.save();

        j.configRoundtrip(job);
        j.buildAndAssertSuccess(job);
        assertNotNull(propertyOf(j, "p1-step"), "properties([...]) with a tracker must keep our property");
        assertEquals(2, propertyOf(j, "p1-step").getVersions().size());

        // Pipeline Syntax generator with our descriptor selected: no property, no exception.
        String clazz = JobConfigTemplateProperty.class.getName().replace('.', '-');
        String json = "{\"stapler-class\":\"org.jenkinsci.plugins.workflow.multibranch.JobPropertyStep\","
                + "\"$class\":\"org.jenkinsci.plugins.workflow.multibranch.JobPropertyStep\","
                + "\"propertiesMap\":{\"" + clazz + "\":{\"stapler-class\":\""
                + JobConfigTemplateProperty.class.getName() + "\",\"$class\":\""
                + JobConfigTemplateProperty.class.getName() + "\"}}}";
        ChainTestKit.HttpReply reply = postForm(j, "pipeline-syntax/generateSnippet", "json", json);
        assertEquals(200, reply.status(), reply.body());
        assertFalse(reply.body().contains("JobConfigTemplateProperty"), reply.body());
    }

    // 5b: legacy branch (E5) is documented in the plan as pre-existing and unchanged by P1; not asserted here.

    // 6
    @Test
    public void configurePageRendersNoBlockForTheProperty_andNoEntrySubmitStillPreserves(JenkinsRule j)
            throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("p1-hidden");
        job.addProperty(seeded());
        job.save();

        HtmlPage page = j.createWebClient().getPage(job, "configure");
        String html = page.asXml();
        assertFalse(html.contains("JobConfigTemplateProperty"), "no control/block named after the property");
        assertFalse(html.contains("Config Templates"), "no visible block titled after the property");

        j.submit(page.getFormByName("config"));
        assertEquals(2, propertyOf(j, "p1-hidden").getVersions().size());
    }
}
