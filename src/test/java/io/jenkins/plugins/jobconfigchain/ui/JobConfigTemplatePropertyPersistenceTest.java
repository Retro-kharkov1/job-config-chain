package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.DescriptorVisibilityFilter;
import hudson.model.FreeStyleProject;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import hudson.model.JobPropertyDescriptor;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JobConfigTemplateProperty} works as a real, persisted {@link hudson.model.JobProperty}
 * — round-trips through {@code config.xml} via XStream exactly like every other {@code JobProperty}
 * (including its append-only version history and secrets manifest), and is never offered on the
 * generic {@code /job/&lt;name&gt;/configure} form as a visible block (hidden by a visibility filter; the
 * descriptor itself stays applicable so Configure-&gt;Save keeps the property).
 */
@WithJenkins
public class JobConfigTemplatePropertyPersistenceTest {

    @Test
    public void addedViaAddProperty_survivesAConfigXmlReloadRoundTrip(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-config-template-round-trip");
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.putSecretManifestEntry("db.password", "cred-a");
        int v1 = property.addVersion("{\"a\":1}", "first version", "alice", 1L, Collections.emptyList(), "JSON");
        property.activate(v1);
        project.addProperty(property);
        project.save();

        FreeStyleProject reloaded =
                jenkins.jenkins.getItemByFullName(project.getFullName(), FreeStyleProject.class);
        assertNotNull(reloaded, "job must still exist after save");

        JobConfigTemplateProperty reloadedProperty = reloaded.getProperty(JobConfigTemplateProperty.class);
        assertNotNull(reloadedProperty, "JobConfigTemplateProperty must round-trip through config.xml via "
                        + "Job#addProperty/XStream");
        assertEquals(1, reloadedProperty.getVersions().size());
        assertEquals("{\"a\":1}", reloadedProperty.getVersion(v1).getContentJson());
        assertEquals(v1, reloadedProperty.getActiveVersionNumber());
        assertEquals("cred-a", reloadedProperty.getSecretsManifest().get("db.password"));
    }

    @Test
    public void descriptorIsApplicable_soConfigureSaveKeepsTheProperty() {
        JobConfigTemplateProperty.DescriptorImpl descriptor = new JobConfigTemplateProperty.DescriptorImpl();

        assertTrue(descriptor.isApplicable(FreeStyleProject.class),
                "must be applicable, otherwise Job Configure->Save silently drops the property (C11)");
        assertEquals("Config Templates", descriptor.getDisplayName());
    }

    @Test
    public void descriptorIsHiddenFromTheDescriptorListsJobConfigureRenders(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("job-config-template-not-offered-on-configure");

        // The list Job/configure.jelly iterates is getPropertyDescriptors filtered by the visibility filters.
        List<JobPropertyDescriptor> descriptors = DescriptorVisibilityFilter.apply(project,
                JobPropertyDescriptor.getPropertyDescriptors(FreeStyleProject.class));

        boolean present = descriptors.stream()
                .anyMatch(JobConfigTemplateProperty.DescriptorImpl.class::isInstance);
        assertFalse(present, "JobConfigTemplateProperty's descriptor must not be rendered on the Configure page");
    }

    @Test
    public void newInstance_returnsNull_andReconfigureReturnsTheLiveInstance() throws Exception {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        assertNull(new JobConfigTemplateProperty.DescriptorImpl().newInstance((StaplerRequest2) null, new JSONObject()));
        assertSame(property, property.reconfigure((StaplerRequest2) null, null));
        assertSame(property, property.reconfigure((StaplerRequest2) null, new JSONObject()));
    }
}
