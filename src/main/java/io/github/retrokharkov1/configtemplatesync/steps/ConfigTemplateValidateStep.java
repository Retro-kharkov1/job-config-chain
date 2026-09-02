package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pipeline step {@code configTemplateValidate(projectKey, environment, file)} (FR-17–FR-20, UF-3).
 *
 * <p>Resolves the effective (merged common+env) configuration for the given projectKey+environment,
 * flattens it to dotted-path keys, extracts the actual {@code #{...}#} tokens present in
 * {@code file}, and fails the build naming every token with no matching effective-config key
 * ("missing"). A key with no matching token ("orphaned") only produces a non-fatal warning.</p>
 */
public class ConfigTemplateValidateStep extends Step {

    private final String projectKey;
    private final String environment;
    private final String file;

    @DataBoundConstructor
    public ConfigTemplateValidateStep(String projectKey, String environment, String file) {
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

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, projectKey, environment, file);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String projectKey;
        private final String environment;
        private final String file;

        Execution(StepContext context, String projectKey, String environment, String file) {
            super(context);
            this.projectKey = projectKey;
            this.environment = environment;
            this.file = file;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);

            ConfigSetRepository repository = StepSupport.newRepository();
            ConfigSet env = StepSupport.requireEnv(repository, projectKey, environment);
            ConfigSetVersion envVersion = env.getActiveVersion();

            // Live re-resolution of every reference in the chain (including PINNED ones — pinning is
            // a property of the version, not of this step). A referenced project/version that does
            // not exist surfaces as an AbortException from inside resolveEffective itself, per
            // reference — requireCommon's old blanket "the one common set for projectKey exists"
            // precondition no longer applies in general, since the chain may reference zero, one, or
            // several common sets, not necessarily projectKey's own.
            List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(projectKey, envVersion);
            StepSupport.ResolvedEffective resolved = StepSupport.resolveEffective(repository, chain, envVersion);

            String targetContent = readTargetFile(workspace);

            StepSupport.validateOrThrow(resolved.mergedConfig, targetContent, listener);
            listener.getLogger().println(
                    "[configTemplateSync] Validation passed for projectKey '" + projectKey
                            + "', environment '" + environment + "'");
            return null;
        }

        private String readTargetFile(FilePath workspace) throws IOException, InterruptedException {
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
            return Collections.unmodifiableSet(context);
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
