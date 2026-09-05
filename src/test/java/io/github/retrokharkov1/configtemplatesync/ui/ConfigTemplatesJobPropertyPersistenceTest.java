package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.FreeStyleProject;
import hudson.model.JobPropertyDescriptor;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Proves {@link ConfigTemplatesJobProperty} still fully works as a persisted {@link
 * hudson.model.JobProperty} (FR-75's acceptance criteria) even though FR-75 removes it from the
 * {@code /job/&lt;name&gt;/configure} form.
 *
 * <p>This class used to be {@code ConfigTemplatesJobPropertyFormPersistenceTest} and drove the
 * real {@code /configure} HTML form (see {@code docs/development/job-property-form-persistence-root-cause.md}
 * for the bug that test originally characterized). That form no longer exists — FR-75 hard-wires
 * {@code DescriptorImpl.isApplicable} to {@code false}, so this descriptor is no longer offered by
 * {@code Job/configure.jelly}'s {@code f:descriptorList} at all, and its {@code config.jelly} has
 * been deleted. The old bug (structured-form-submission JSON never being populated for this
 * descriptor) is therefore moot: there is no longer any HTML form to submit it through. The
 * "real form round-trip" guarantee for THIS association now lives on the job's own Config
 * Templates page instead (see the design's T6, {@code ConfigTemplatesJobPageTest}) — this class
 * instead locks in the two guarantees that remain true today: (1) direct construction +
 * {@code Job#addProperty(...)} still round-trips through {@code config.xml} via XStream exactly
 * like every other {@code JobProperty}, and (2) the descriptor is verifiably gone from the set
 * {@code Job/configure.jelly} renders.</p>
 */
public class ConfigTemplatesJobPropertyPersistenceTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void addedViaAddProperty_survivesAConfigXmlReloadRoundTrip() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("property-persistence-round-trip");
        project.addProperty(new ConfigTemplatesJobProperty("test-app", "dev"));
        project.save();

        FreeStyleProject reloaded =
                jenkins.jenkins.getItemByFullName(project.getFullName(), FreeStyleProject.class);
        assertNotNull("job must still exist after save", reloaded);

        ConfigTemplatesJobProperty property = reloaded.getProperty(ConfigTemplatesJobProperty.class);
        assertNotNull("ConfigTemplatesJobProperty must round-trip through config.xml via "
                        + "Job#addProperty/XStream, independently of any JobPropertyDescriptor form",
                property);
        assertEquals("test-app", property.getProjectKey());
        assertEquals("dev", property.getEnvironment());
    }

    @Test
    public void descriptorIsApplicable_alwaysReturnsFalse() {
        ConfigTemplatesJobProperty.DescriptorImpl descriptor = new ConfigTemplatesJobProperty.DescriptorImpl();

        assertFalse("FR-75: DescriptorImpl.isApplicable must always return false so this "
                        + "descriptor is never surfaced by Job/configure.jelly's f:descriptorList",
                descriptor.isApplicable(FreeStyleProject.class));
    }

    @Test
    public void descriptorIsNotInThePropertyDescriptorsJobConfigureRenders() throws Exception {
        jenkins.createFreeStyleProject("property-not-offered-on-configure");

        List<JobPropertyDescriptor> descriptors =
                JobPropertyDescriptor.getPropertyDescriptors(FreeStyleProject.class);

        boolean present = descriptors.stream()
                .anyMatch(ConfigTemplatesJobProperty.DescriptorImpl.class::isInstance);
        assertFalse("ConfigTemplatesJobProperty's descriptor must not appear in the list "
                        + "Job/configure.jelly's h.getJobPropertyDescriptors(it) iterates — "
                        + "the block must be fully gone from /job/<name>/configure",
                present);
    }
}
