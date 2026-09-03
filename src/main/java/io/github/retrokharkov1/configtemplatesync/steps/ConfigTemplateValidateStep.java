package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;

import java.util.List;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pipeline step {@code configTemplateValidate(projectKey, environment, file, useBase, version)}
 * (FR-17–FR-20, FR-79–FR-85, UF-3).
 *
 * <p>Resolves the effective (merged common+env) configuration for the given projectKey+environment,
 * flattens it to dotted-path keys, extracts the actual {@code #{...}#} tokens present in
 * {@code file}, and fails the build naming every token with no matching effective-config key
 * ("missing"). A key with no matching token ("orphaned") only produces a non-fatal warning.</p>
 *
 * <p>{@code projectKey}/{@code environment}/{@code file} may be omitted from the call entirely if a
 * prior {@code setupConfigTemplate} call in the same build already supplied them (FR-90–FR-95);
 * explicit call-site values always win per parameter (FR-93).</p>
 */
public class ConfigTemplateValidateStep extends Step {

    private String projectKey;
    private String environment;
    private String file;
    private Boolean useBase;
    private Integer version;

    @DataBoundConstructor
    public ConfigTemplateValidateStep() {
    }

    public String getProjectKey() {
        return projectKey;
    }

    @DataBoundSetter
    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getEnvironment() {
        return environment;
    }

    @DataBoundSetter
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getFile() {
        return file;
    }

    @DataBoundSetter
    public void setFile(String file) {
        this.file = file;
    }

    /**
     * Public boolean-returning accessor. Internally {@link #useBase} is boxed and starts {@code
     * null} so FR-93's per-parameter precedence merge can tell "not supplied on this call" from
     * "explicitly {@code false} on this call" (load-bearing — do not change back to a primitive).
     */
    public boolean isUseBase() {
        return Boolean.TRUE.equals(useBase);
    }

    @DataBoundSetter
    public void setUseBase(boolean useBase) {
        this.useBase = useBase;
    }

    public Integer getVersion() {
        return version;
    }

    @DataBoundSetter
    public void setVersion(int version) {
        this.version = version;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, projectKey, environment, file, useBase, version);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String projectKey;
        private final String environment;
        private final String file;
        private final Boolean useBase;
        private final Integer version;

        Execution(StepContext context, String projectKey, String environment, String file,
                  Boolean useBase, Integer version) {
            super(context);
            this.projectKey = projectKey;
            this.environment = environment;
            this.file = file;
            this.useBase = useBase;
            this.version = version;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);
            Run<?, ?> run = getContext().get(Run.class);

            StepSupport.EffectiveParams params = StepSupport.mergeWithSetupState(
                    run, projectKey, environment, file, null, useBase, version);

            ConfigSetRepository repository = StepSupport.newRepository();

            StepSupport.ResolvedEffective resolved;
            if (params.useBase) {
                // FR-81/FR-85: bypass the env Config Set and its base-chain machinery entirely.
                resolved = StepSupport.resolveUseBaseOnly(repository, params.projectKey, params.version);
            } else {
                ConfigSet env = StepSupport.requireEnv(repository, params.projectKey, params.environment);
                ConfigSetVersion envVersion;
                if (params.version != null) {
                    // FR-80: pin the env Config Set to this exact version.
                    envVersion = env.getVersion(params.version);
                    if (envVersion == null) {
                        throw new AbortException("Env Config Set for projectKey '" + params.projectKey
                                + "', environment '" + params.environment + "' has no version " + params.version
                                + " to resolve (explicit 'version' parameter)");
                    }
                } else {
                    envVersion = env.getActiveVersion();
                }

                // Live re-resolution of every reference in the chain (including PINNED ones — pinning
                // is a property of the version, not of this step). A referenced project/version that
                // does not exist surfaces as an AbortException from inside resolveEffective itself,
                // per reference.
                List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(params.projectKey, envVersion);
                resolved = StepSupport.resolveEffective(repository, chain, envVersion, env);
            }

            String targetContent = readTargetFile(workspace, params.file);

            StepSupport.validateOrThrow(resolved.mergedConfig, targetContent, listener);
            listener.getLogger().println(
                    "[configTemplateSync] Validation passed for projectKey '" + params.projectKey
                            + "', environment '" + params.environment + "'");
            return null;
        }

        private String readTargetFile(FilePath workspace, String file) throws IOException, InterruptedException {
            FilePath target = workspace.child(file);
            if (!target.exists()) {
                throw new AbortException("Target config file not found: " + file);
            }
            return target.readToString();
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "configTemplateValidate";
        }

        @Override
        public String getDisplayName() {
            return "Validate config template drift against a Config Project/environment";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            Set<Class<?>> context = new LinkedHashSet<>();
            context.add(TaskListener.class);
            context.add(FilePath.class);
            context.add(Run.class);
            return Collections.unmodifiableSet(context);
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
