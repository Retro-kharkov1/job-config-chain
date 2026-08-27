package io.github.retrokharkov1.configtemplatesync;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code configTemplateValidate(commonConfigSet: '...', envConfigSet: '...', file: '...')}
 *
 * <p>Merges the active version of the "common" ConfigSet with the active version of the "env"
 * ConfigSet (env wins on key conflict — see {@link EffectiveConfigResolver}), flattens the merged
 * tree + the merged secrets manifest into {@code STORE_KEYS}, extracts {@code #{...}#} tokens from
 * the target workspace file into {@code REPO_TOKENS}, and enforces:
 * <ul>
 *   <li>{@code REPO_TOKENS - STORE_KEYS} -&gt; build FAILS, naming every missing key</li>
 *   <li>{@code STORE_KEYS - REPO_TOKENS} -&gt; non-fatal {@code [WARN] Orphan store keys: ...} log line</li>
 * </ul>
 */
public class ConfigTemplateValidateStep extends Step implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final String commonConfigSet;
    private final String envConfigSet;
    private final String file;

    @DataBoundConstructor
    public ConfigTemplateValidateStep(String commonConfigSet, String envConfigSet, String file) {
        this.commonConfigSet = commonConfigSet;
        this.envConfigSet = envConfigSet;
        this.file = file;
    }

    public String getCommonConfigSet() {
        return commonConfigSet;
    }

    public String getEnvConfigSet() {
        return envConfigSet;
    }

    public String getFile() {
        return file;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;
        private final ConfigTemplateValidateStep step;

        Execution(ConfigTemplateValidateStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);

            ConfigSetRepository repository = ConfigSetRepository.onMaster();
            EffectiveConfigResolver.Effective effective = EffectiveConfigResolver.resolve(
                    repository, step.commonConfigSet, step.envConfigSet);

            Set<String> storeKeys = new HashSet<>(ConfigJsonTree.flatten(effective.mergedJson).keySet());
            storeKeys.addAll(effective.mergedSecretsManifest.keySet());

            FilePath target = workspace.child(step.file);
            if (!target.exists()) {
                throw new IllegalStateException("File not found in workspace: " + step.file);
            }
            String fileText = target.readToString();
            List<String> repoTokens = ConfigJsonTree.extractTokens(fileText);

            Set<String> missing = new TreeSet<>();
            for (String token : repoTokens) {
                if (!storeKeys.contains(token)) {
                    missing.add(token);
                }
            }
            Set<String> orphans = new TreeSet<>(storeKeys);
            orphans.removeAll(new HashSet<>(repoTokens));

            if (!orphans.isEmpty()) {
                listener.getLogger().println("[WARN] Orphan store keys: " + String.join(", ", orphans));
            }

            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "configTemplateValidate: " + step.file + " references token(s) with no matching "
                                + "ConfigSet entry (common='" + step.commonConfigSet + "', env='"
                                + step.envConfigSet + "'): " + String.join(", ", missing));
            }

            listener.getLogger().println(
                    "[configTemplateValidate] OK — " + repoTokens.size() + " token(s) in " + step.file
                            + " all resolved against ConfigSets '" + step.commonConfigSet + "' + '"
                            + step.envConfigSet + "'.");
            return null;
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
            return "Validate #{Path}# tokens against a Config Template Sync ConfigSet pair";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            Set<Class<?>> ctx = new HashSet<>();
            Collections.addAll(ctx, TaskListener.class, FilePath.class);
            return ctx;
        }
    }
}
