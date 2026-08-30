package io.github.retrokharkov1.configtemplatesync.steps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.TaskListener;
import io.github.retrokharkov1.configtemplatesync.merge.JsonPaths;
import io.github.retrokharkov1.configtemplatesync.merge.TokenExtractor;
import io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigDeploymentBindingRepository;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline step {@code configTemplateSubstitute(projectKey, environment, file, buildVersion)}
 * (FR-21–FR-27, UF-4/UF-6). {@code buildVersion} is optional.
 *
 * <p>Per OQ-7, this step re-runs the same flatten-and-compare drift check {@code
 * configTemplateValidate} performs, as a defensive re-check, rather than trusting that validate
 * already ran in this Jenkinsfile. Per OQ-3/FR-25-27, build-version pinning is opt-in: it is only
 * consulted when {@code buildVersion} is explicitly supplied.</p>
 *
 * <p>This step does not resolve Jenkins credentials itself — secret values are expected to already
 * be present in the calling Jenkinsfile's environment (e.g. via {@code withCredentials}) and are
 * merged in by dotted-path key before substitution.</p>
 */
public class ConfigTemplateSubstituteStep extends Step {

    private final String projectKey;
    private final String environment;
    private final String file;
    private String buildVersion;

    @DataBoundConstructor
    public ConfigTemplateSubstituteStep(String projectKey, String environment, String file) {
        this.projectKey = projectKey;
        this.environment = environment;
        this.file = file;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getFile() {
        return file;
    }

    public String getBuildVersion() {
        return buildVersion;
    }

    @DataBoundSetter
    public void setBuildVersion(String buildVersion) {
        this.buildVersion = buildVersion;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, projectKey, environment, file, buildVersion);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String projectKey;
        private final String environment;
        private final String file;
        private final String buildVersion;

        Execution(StepContext context, String projectKey, String environment, String file, String buildVersion) {
            super(context);
            this.projectKey = projectKey;
            this.environment = environment;
            this.file = file;
            this.buildVersion = buildVersion;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);
            EnvVars envVars = getContext().get(EnvVars.class);

            ConfigSetRepository configSetRepository = StepSupport.newRepository();
            ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();

            ConfigSet common = StepSupport.requireCommon(configSetRepository, projectKey);
            ConfigSet env = StepSupport.requireEnv(configSetRepository, projectKey, environment);

            ConfigSetVersion commonVersion;
            ConfigSetVersion envVersion;
            boolean pinned = false;
            ConfigDeploymentBinding existingBinding = null;

            if (buildVersion != null && !buildVersion.trim().isEmpty()) {
                existingBinding = bindingRepository.find(projectKey, environment, buildVersion);
                if (existingBinding != null) {
                    commonVersion = common.getVersion(existingBinding.getCommonVersionNumber());
                    envVersion = env.getVersion(existingBinding.getEnvVersionNumber());
                    pinned = true;
                    listener.getLogger().println(
                            "[configTemplateSync] buildVersion '" + buildVersion
                                    + "' is pinned to common v" + existingBinding.getCommonVersionNumber()
                                    + " / env v" + existingBinding.getEnvVersionNumber());
                } else {
                    // FR-26: fall back to active AND surface that this happened, explicitly.
                    listener.getLogger().println(
                            "[configTemplateSync][WARN] No deployment binding found for buildVersion '"
                                    + buildVersion + "' (projectKey '" + projectKey + "', environment '"
                                    + environment + "'); falling back to the currently active versions.");
                    commonVersion = common.getActiveVersion();
                    envVersion = env.getActiveVersion();
                }
            } else {
                // FR-27: no buildVersion given at all -> currently-active versions, no pinning behavior.
                commonVersion = common.getActiveVersion();
                envVersion = env.getActiveVersion();
            }

            if (commonVersion == null) {
                throw new AbortException(
                        "Common Config Set '" + projectKey + "' has no active (or pinned) version to substitute");
            }

            JsonObject effective = StepSupport.resolveEffective(commonVersion, envVersion);

            FilePath target = workspace.child(file);
            if (!target.exists()) {
                throw new AbortException("Target config file not found: " + file);
            }
            String originalContent = target.readToString();

            // OQ-7: defensive re-check, reusing the exact same drift comparison as validate, rather
            // than trusting that configTemplateValidate already ran earlier in this pipeline.
            StepSupport.validateOrThrow(effective, originalContent, listener);

            String substituted = substitute(effective, originalContent, envVars);

            if (TokenExtractor.containsAnyToken(substituted)) {
                Set<String> remaining = TokenExtractor.extractTokenPaths(substituted);
                throw new AbortException(
                        "[configTemplateSync] Substitution incomplete — tokens remain unresolved: " + remaining);
            }

            target.write(substituted, null);
            listener.getLogger().println(
                    "[configTemplateSync] Substituted " + file + " for projectKey '" + projectKey
                            + "', environment '" + environment + "' using common v" + commonVersion.getVersionNumber()
                            + " / env v" + (envVersion == null ? "(none)" : envVersion.getVersionNumber())
                            + (pinned ? " (pinned)" : ""));

            // FR-23: only a successful REAL substitution run with a buildVersion supplied
            // creates/updates a deployment binding — never a validate-only or dry-run call.
            if (buildVersion != null && !buildVersion.trim().isEmpty()) {
                int envVersionNumber = envVersion == null ? 0 : envVersion.getVersionNumber();
                bindingRepository.save(projectKey, environment, buildVersion,
                        commonVersion.getVersionNumber(), envVersionNumber, System.currentTimeMillis());
            }

            return null;
        }

        private String substitute(JsonObject effective, String content, EnvVars envVars) {
            Map<String, JsonElement> flattened = JsonPaths.flatten(effective);
            String result = content;
            for (Map.Entry<String, JsonElement> entry : flattened.entrySet()) {
                String dottedPath = entry.getKey();
                String token = "#{" + dottedPath + "}#";
                if (!result.contains(token)) {
                    continue;
                }
                String value;
                if (envVars != null && envVars.containsKey(dottedPath)) {
                    // Merge in a value already present in the calling Jenkinsfile's environment
                    // (e.g. supplied via withCredentials) — this step does not resolve credentials itself.
                    value = envVars.get(dottedPath);
                } else {
                    value = JsonPaths.leafAsString(entry.getValue());
                }
                result = result.replace(token, value);
            }
            return result;
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "configTemplateSubstitute";
        }

        @Override
        public String getDisplayName() {
            return "Substitute real values into a config template for a Config Project/environment";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            Set<Class<?>> context = new LinkedHashSet<>();
            context.add(TaskListener.class);
            context.add(FilePath.class);
            context.add(EnvVars.class);
            return Collections.unmodifiableSet(context);
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
