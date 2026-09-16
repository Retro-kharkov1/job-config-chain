package io.jenkins.plugins.jobconfigchain.steps;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;

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
 * Pipeline step {@code configTemplateValidate(file, useBase, configKey, version)} — see
 * pipeline-steps.md's "Validation (drift detection)" section, and user-flows.md's deploy-validate
 * flow.
 *
 * <p>Every call resolves against the CALLING JOB's own attached local config
 * ({@code JobConfigTemplateProperty}) by construction, unless {@code useBase: true} + {@code
 * configKey} explicitly redirects to a named global COMMON Config Set — see pipeline-steps.md's
 * "Pipeline call resolution — the final parameter model" for the full 7-row resolution matrix.
 * There is no {@code projectKey}/{@code environment} parameter; that calling form was retired in
 * full (2026-09-14), not deprecated.</p>
 *
 * <p>Resolves the effective configuration per the resolution matrix, flattens it to dotted-path
 * keys, extracts the actual {@code #{...}#} tokens present in {@code file}, and fails the build
 * naming every token with no matching effective-config key ("missing"). A key with no matching
 * token ("orphaned") only produces a non-fatal warning.</p>
 *
 * <p>{@code file}/{@code useBase}/{@code configKey}/{@code version} may be omitted from the call
 * entirely if a prior {@code setupConfigTemplate} call in the same build already supplied them (see
 * pipeline-steps.md's "setupConfigTemplate build-scoped convenience step"); explicit call-site
 * values always win per parameter. Only {@code file} remains mandatory.</p>
 */
public class ConfigTemplateValidateStep extends Step {

    private String file;
    private Boolean useBase;
    private String configKey;
    private Integer version;

    @DataBoundConstructor
    public ConfigTemplateValidateStep() {
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
     * null} so the per-parameter precedence merge (pipeline-steps.md's "Explicit call-site
     * parameters override stored setup state" rule) can tell "not supplied on this call" from
     * "explicitly {@code false} on this call" (load-bearing — do not change back to a primitive).
     */
    public boolean isUseBase() {
        return Boolean.TRUE.equals(useBase);
    }

    @DataBoundSetter
    public void setUseBase(boolean useBase) {
        this.useBase = useBase;
    }

    /** Only valid together with {@code useBase: true} (matrix rows 6/7) — see class javadoc. */
    public String getConfigKey() {
        return configKey;
    }

    @DataBoundSetter
    public void setConfigKey(String configKey) {
        this.configKey = configKey;
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
        return new Execution(context, file, useBase, configKey, version);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String file;
        private final Boolean useBase;
        private final String configKey;
        private final Integer version;

        Execution(StepContext context, String file, Boolean useBase, String configKey, Integer version) {
            super(context);
            this.file = file;
            this.useBase = useBase;
            this.configKey = configKey;
            this.version = version;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);
            Run<?, ?> run = getContext().get(Run.class);
            Job<?, ?> job = run.getParent(); // Run#getParent() already returns the owning Job — no
            // getRequiredContext() change needed (tech-lead scoping decision, 2026-09-14,
            // pipeline-steps.md §2).

            StepSupport.EffectiveParams params = StepSupport.mergeWithSetupState(
                    run, file, null, useBase, configKey, version);

            ConfigSetRepository repository = StepSupport.newRepository();

            StepSupport.ResolvedEffective resolved = StepSupport.resolveJobScoped(
                    repository, job, params.useBase, params.configKey, params.version);

            String targetContent = readTargetFile(workspace, params.file);

            String resolutionMode = StepSupport.describeResolutionMode(params.useBase, params.configKey, params.version);
            StepSupport.validateOrThrow(resolved.mergedConfig, targetContent, listener,
                    job.getFullName(), params.file, resolutionMode);
            listener.getLogger().println(
                    "[configTemplateSync] Validation passed for Job '" + job.getFullName() + "'");
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
            return "Validate config template drift for the calling Job's own Config Templates";
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
