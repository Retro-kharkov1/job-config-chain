package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Associates a {@link Job} with a Config Templates project (and, optionally, one of its
 * environments), so job-specific tooling (e.g. {@link ConfigTemplatesJobAction}) can deep-link
 * straight into that project's Config Set instead of the plugin's generic root list.
 *
 * <p>Extension point: {@link hudson.model.JobProperty} (https://javadoc.jenkins.io/hudson/model/JobProperty.html)
 * — "Jenkins plugins can add extra properties to a Job through this."</p>
 *
 * <p><b>Does NOT render on {@code /job/&lt;name&gt;/configure} (FR-75).</b> This is deliberate,
 * not an oversight: {@code DescriptorImpl#isApplicable(Class)} below is hard-wired to always
 * return {@code false}, which removes this descriptor from
 * {@code JobPropertyDescriptor.getPropertyDescriptors(Class)} — the exact list
 * {@code hudson/model/Job/configure.jelly}'s {@code <f:descriptorList field="properties"
 * descriptors="${h.getJobPropertyDescriptors(it)}"/>} iterates to build the "Config Templates
 * Association" row-group. The association is instead edited on the job's own dedicated page,
 * {@code /job/&lt;name&gt;/configTemplates} (see {@link ConfigTemplatesJobAction}), reached from
 * the job's own "Config Templates" sidebar item rather than from the generic Configure form. Do
 * NOT "fix" {@code isApplicable} back to {@code true} or re-add a {@code config.jelly} next to
 * this descriptor to restore the old Configure-page block — that would reintroduce the exact
 * surface FR-75 requires removed.</p>
 *
 * <p>None of this affects persistence: {@code Job#addProperty(ConfigTemplatesJobProperty)} and
 * {@code Job#getProperty(ConfigTemplatesJobProperty.class)} call straight into
 * {@code DescribableList}/XStream (de)serialization by class name, with zero
 * {@code JobPropertyDescriptor} lookup involved, so this property still persists and round-trips
 * through {@code config.xml} under {@code $JENKINS_HOME} exactly as before. The
 * {@link DataBoundConstructor} and {@code doCheckProjectKey} live validation also remain fully
 * functional — they now back the new page's association form instead of a
 * {@code config.jelly} fragment.</p>
 *
 * <p>{@code environment} is intentionally optional: per this plugin's existing common/env
 * distinction (see {@code ConfigSetRole}), a blank environment means the association is to the
 * project's COMMON Config Set only.</p>
 */
public class ConfigTemplatesJobProperty extends JobProperty<Job<?, ?>> {

    private final String projectKey;
    private final String environment;

    @DataBoundConstructor
    public ConfigTemplatesJobProperty(String projectKey, String environment) {
        this.projectKey = trimToEmpty(projectKey);
        this.environment = trimToEmpty(environment);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getEnvironment() {
        return environment;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Config Templates Association";
        }

        /**
         * Always {@code false} (FR-75): this is what removes {@link ConfigTemplatesJobProperty}
         * from {@link JobPropertyDescriptor#getPropertyDescriptors(Class)}, and therefore from
         * {@code hudson/model/Job/configure.jelly}'s {@code <f:descriptorList field="properties"
         * descriptors="${h.getJobPropertyDescriptors(it)}"/>}, for every job type on every
         * render. The descriptor remains a real, discoverable {@code @Extension} — this method
         * only gates where it is OFFERED FOR EDITING, not whether it exists as an extension
         * point. See the class javadoc for why the association now lives on its own page
         * instead.
         */
        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return false;
        }

        /**
         * Non-blocking check (owner decision): a job may legitimately be wired to a
         * {@code projectKey} before that Config Set has been created yet (or after it was
         * deleted), so this is a {@link FormValidation#warning(String)}, never an
         * {@link FormValidation#error(String)} — it must never block saving the job config.
         */
        public FormValidation doCheckProjectKey(@QueryParameter String value) {
            String projectKey = trimToEmpty(value);
            if (projectKey.isEmpty()) {
                return FormValidation.ok();
            }
            if (new ConfigSetRepository().findCommon(projectKey) == null) {
                return FormValidation.warning(
                        "No Config Set named '" + projectKey + "' exists yet (it can be created later).");
            }
            return FormValidation.ok();
        }
    }
}
