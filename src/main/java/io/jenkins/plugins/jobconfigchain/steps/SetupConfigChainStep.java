package io.jenkins.plugins.jobconfigchain.steps;

import hudson.Extension;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pipeline step {@code setupConfigChain(file:, useBase:, configKey:, version:, redeployFromRun:)}: the
 * public name of what older Jenkinsfiles call {@code setupConfigTemplate}.
 *
 * <p>This class only carries the parameters. The work is done by the very same
 * {@link SetupConfigTemplateStep.Execution} the deprecated alias uses (reached, not copied), so the
 * build-scoped state it stores is identical for both names and is read back by either the validate or
 * the substitute name; see {@link SetupConfigTemplateStep} for the full semantics.</p>
 */
public class SetupConfigChainStep extends Step {

    private String file;
    private String redeployFromRun;
    private Boolean useBase;
    // Identifier of a config set/chain, not a credential or secret.
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private String configKey;
    private Integer version;

    @DataBoundConstructor
    public SetupConfigChainStep() {
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
    @SuppressWarnings("deprecation")
    public StepExecution start(StepContext context) {
        return new SetupConfigTemplateStep.Execution(context, file, redeployFromRun, useBase != null && useBase,
                configKey, version, true);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "setupConfigChain";
        }

        @Override
        public String getDisplayName() {
            return io.jenkins.plugins.jobconfigchain.ui.Messages.Step_Setup_DisplayName();
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
