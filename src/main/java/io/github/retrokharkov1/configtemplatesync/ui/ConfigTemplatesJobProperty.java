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
 * <p>Renders its own section on {@code /job/&lt;name&gt;/configure} via a {@code config.jelly}
 * next to this class (the standard Descriptor/{@code config.jelly} convention — see
 * https://www.jenkins.io/doc/developer/forms/structured-form-submission/), and persists via
 * this class's {@link DataBoundConstructor} exactly like every other structured-form-bound
 * Describable in Jenkins core.</p>
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
