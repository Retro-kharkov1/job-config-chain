package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.FreeStyleProject;
import hudson.model.JobPropertyDescriptor;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Proves {@link JobConfigTemplateProperty} works as a real, persisted {@link hudson.model.JobProperty}
 * — round-trips through {@code config.xml} via XStream exactly like every other {@code JobProperty}
 * (including its append-only version history and secrets manifest), and is never offered on the
 * generic {@code /job/&lt;name&gt;/configure} form (mirrors the old {@code ConfigTemplatesJobProperty}'s
 * equivalent guarantee, which this class replaces).
 */
public class JobConfigTemplatePropertyPersistenceTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void addedViaAddProperty_survivesAConfigXmlReloadRoundTrip() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-config-template-round-trip");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.putSecretManifestEntry("db.password", "cred-a");
        int v1 = property.addVersion("{\"a\":1}", "first version", "alice", 1L, Collections.emptyList(), "JSON");
        property.activate(v1);
        project.addProperty(property);
        project.save();

        FreeStyleProject reloaded =
                jenkins.jenkins.getItemByFullName(project.getFullName(), FreeStyleProject.class);
        assertNotNull("job must still exist after save", reloaded);

        JobConfigTemplateProperty reloadedProperty = reloaded.getProperty(JobConfigTemplateProperty.class);
        assertNotNull("JobConfigTemplateProperty must round-trip through config.xml via "
                        + "Job#addProperty/XStream", reloadedProperty);
        assertEquals(1, reloadedProperty.getVersions().size());
        assertEquals("{\"a\":1}", reloadedProperty.getVersion(v1).getContentJson());
        assertEquals(v1, reloadedProperty.getActiveVersionNumber());
        assertEquals("cred-a", reloadedProperty.getSecretsManifest().get("db.password"));
    }

    @Test
    public void descriptorIsApplicable_alwaysReturnsFalse() {
        JobConfigTemplateProperty.DescriptorImpl descriptor = new JobConfigTemplateProperty.DescriptorImpl();

        assertFalse("this descriptor must never be surfaced by Job/configure.jelly's f:descriptorList",
                descriptor.isApplicable(FreeStyleProject.class));
        assertEquals("Config Templates", descriptor.getDisplayName());
    }

    @Test
    public void descriptorIsNotInThePropertyDescriptorsJobConfigureRenders() throws Exception {
        jenkins.createFreeStyleProject("job-config-template-not-offered-on-configure");

        List<JobPropertyDescriptor> descriptors =
                JobPropertyDescriptor.getPropertyDescriptors(FreeStyleProject.class);

        boolean present = descriptors.stream()
                .anyMatch(JobConfigTemplateProperty.DescriptorImpl.class::isInstance);
        assertFalse("JobConfigTemplateProperty's descriptor must not appear in the list "
                        + "Job/configure.jelly's h.getJobPropertyDescriptors(it) iterates",
                present);
    }
}
