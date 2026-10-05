package io.jenkins.plugins.jobconfigchain.steps;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
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
 * Pipeline step {@code configChainValidate(file, useBase, configKey, version, encoding)}: the public name
 * of what older Jenkinsfiles call {@code configTemplateValidate}.
 *
 * <p>This class only carries the parameters. The work is done by the very same
 * {@link ConfigTemplateValidateStep.Execution} the deprecated alias uses (reached, not copied), so the
 * result and every log line are identical for both names; see {@link ConfigTemplateValidateStep} for the
 * full parameter semantics.</p>
 */
public class ConfigChainValidateStep extends Step {

    private String file;
    private Boolean useBase;
    // Identifier of a config set/chain, not a credential or secret.
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private String configKey;
    private Integer version;
    private String encoding;

    @DataBoundConstructor
    public ConfigChainValidateStep() {
    }

    public String getFile() {
        return file;
    }

    @DataBoundSetter
    public void setFile(String file) {
        this.file = file;
    }

    /** Boxed internally so "not supplied on this call" differs from an explicit {@code false}. */
    public boolean isUseBase() {
        return Boolean.TRUE.equals(useBase);
    }

    @DataBoundSetter
    public void setUseBase(boolean useBase) {
        this.useBase = useBase;
    }

    /** Only valid together with {@code useBase: true}. */
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

    /** Charset of the target file; {@code null} = UTF-8 with fallback to the agent default. */
    public String getEncoding() {
        return encoding;
    }

    @DataBoundSetter
    public void setEncoding(String encoding) {
        this.encoding = encoding == null || encoding.trim().isEmpty() ? null : encoding.trim();
    }

    @Override
    @SuppressWarnings("deprecation")
    public StepExecution start(StepContext context) {
        return new ConfigTemplateValidateStep.Execution(context, file, useBase, configKey, version, encoding);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        /** Snippet Generator submit: untouched (empty) fields mean "not supplied". */
        @Override
        public Step newInstance(org.kohsuke.stapler.StaplerRequest2 req, net.sf.json.JSONObject formData)
                throws FormException {
            return super.newInstance(req, SnippetFormSupport.withoutBlankValues(formData));
        }

        @Override
        public String getFunctionName() {
            return "configChainValidate";
        }

        @Override
        public String getDisplayName() {
            return io.jenkins.plugins.jobconfigchain.ui.Messages.Step_Validate_DisplayName();
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
