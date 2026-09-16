package io.jenkins.plugins.jobconfigchain.steps;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pipeline step {@code setupConfigTemplate(file:, useBase:, configKey:, version:, redeployFromRun:)}
 * — see pipeline-steps.md's "setupConfigTemplate build-scoped convenience step" section. Stores its
 * parameters scoped to the current build ({@link Run}) so a subsequent
 * same-build, zero/partial-argument {@code configTemplateValidate()}/{@code configTemplateSubstitute()}
 * call can read them back — purely additive convenience: a Jenkinsfile that
 * never calls this step behaves with zero change.
 *
 * <p><b>Parameter shape (2026-09-14):</b> no {@code projectKey}/{@code environment} parameter exists
 * here either, for the same reason there is none on the two steps this configures — see
 * pipeline-steps.md's "Pipeline call resolution — the final parameter model". {@code configKey} is
 * only meaningful together with {@code useBase: true}.</p>
 */
public class SetupConfigTemplateStep extends Step {

    private String file;
    private String redeployFromRun;
    private Boolean useBase;
    private String configKey;
    private Integer version;

    @DataBoundConstructor
    public SetupConfigTemplateStep() {
    }

    public String getFile() {
        return file;
    }

    @DataBoundSetter
    public void setFile(String file) {
        this.file = file;
    }

    public String getRedeployFromRun() {
        return redeployFromRun;
    }

    @DataBoundSetter
    public void setRedeployFromRun(String redeployFromRun) {
        this.redeployFromRun = redeployFromRun;
    }

    public boolean isUseBase() {
        return Boolean.TRUE.equals(useBase);
    }

    @DataBoundSetter
    public void setUseBase(boolean useBase) {
        this.useBase = useBase;
    }

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
        return new Execution(context, file, redeployFromRun, useBase != null && useBase, configKey, version);
    }

    static class Execution extends SynchronousStepExecution<Void> {
        // Deliberately SynchronousStepExecution, NOT SynchronousNonBlockingStepExecution like the
        // other two steps: this step does no file/credential/network I/O (it only appends an
        // in-memory Action to the running Run), so there is nothing worth handing off to the
        // async-step thread pool for — running it on the CPS execution thread directly is both
        // simpler and correct.

        private static final long serialVersionUID = 1L;

        private final String file;
        private final String redeployFromRun;
        private final boolean useBase;
        private final String configKey;
        private final Integer version;

        Execution(StepContext context, String file, String redeployFromRun, boolean useBase, String configKey,
                  Integer version) {
            super(context);
            this.file = file;
            this.redeployFromRun = redeployFromRun;
            this.useBase = useBase;
            this.configKey = configKey;
            this.version = version;
        }

        @Override
        protected Void run() throws Exception {
            FlowNode flowNode = getContext().get(FlowNode.class);
            String branch = ParallelBranchGuard.enclosingParallelBranchName(flowNode);
            if (branch != null) {
                // See pipeline-steps.md's "Forbidden inside parallel {}" rule.
                throw new AbortException("[configTemplateSync] setupConfigTemplate() is not supported "
                        + "inside a parallel {} branch ('" + branch + "') — its build-scoped state would be "
                        + "ambiguous across concurrently-running branches. Call configTemplateValidate/"
                        + "configTemplateSubstitute with their own full explicit parameters inside "
                        + "parallel {} instead.");
            }
            Run<?, ?> run = getContext().get(Run.class);
            // addOrReplaceAction, not addAction: a build MAY legitimately call setupConfigTemplate()
            // more than once (e.g. re-pointing to a different configKey partway through a
            // Jenkinsfile) — a second call must REPLACE the stored state, not leave two
            // ConfigTemplateSetupAction instances on the Run where run.getAction(Class) resolution
            // order would be an unspecified surprise.
            run.addOrReplaceAction(new ConfigTemplateSetupAction(file, redeployFromRun, useBase, configKey,
                    version));
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "setupConfigTemplate";
        }

        @Override
        public String getDisplayName() {
            return "Store config-template parameters for the current build";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            Set<Class<?>> context = new LinkedHashSet<>();
            context.add(Run.class);
            context.add(FlowNode.class);
            return Collections.unmodifiableSet(context);
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
