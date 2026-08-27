package io.github.retrokharkov1.configtemplatesync;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;

/**
 * {@code configTemplateSubstitute(commonConfigSet: '...', envConfigSet: '...', file: '...',
 * buildVersion: '...')}
 *
 * <p>Merges the active "common" + "env" ConfigSet versions (env wins on conflict, see
 * {@link EffectiveConfigResolver} — the "merge general+env -&gt; then apply secrets by key"
 * ordering), flattens the merged tree to {@code KEY=VALUE} pairs, and does the same
 * {@code text.replace("#{"+key+"}#", value)} substitution loop the existing hand-written
 * PowerShell stage does — across this step's own flattened pairs AND whatever the calling
 * Jenkinsfile has already exported into the step's environment (so a Jenkinsfile can compose this
 * with its own {@code withCredentials} block to supply the secret/build-stamp values named in the
 * ConfigSet's secrets manifest; this step does not itself resolve credentials).
 *
 * <p>Preserves the existing safety guard: if any {@code #\{...\}#} token remains in the file after
 * substitution, the build fails with "Unsubstituted tokens remain".
 *
 * <p><b>Build-version config pinning (optional {@code buildVersion} param, see
 * {@link ConfigDeploymentBinding}):</b> when set and a binding already exists for that exact
 * {@code buildVersion} (keyed by {@code envConfigSet}), the merge uses the PINNED common+env
 * version numbers from that binding instead of "currently active" — so redeploying an older build
 * replays the config it actually shipped with. When omitted, or no existing binding matches, the
 * default unchanged behavior applies (current active versions). After any successful real
 * substitution with a {@code buildVersion} set, the binding for that build version is created or
 * updated to record the version numbers actually used.
 */
public class ConfigTemplateSubstituteStep extends Step implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final String commonConfigSet;
    private final String envConfigSet;
    private final String file;
    private String buildVersion;

    @DataBoundConstructor
    public ConfigTemplateSubstituteStep(String commonConfigSet, String envConfigSet, String file) {
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

    public String getBuildVersion() {
        return buildVersion;
    }

    @DataBoundSetter
    public void setBuildVersion(String buildVersion) {
        this.buildVersion = buildVersion;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;
        private final ConfigTemplateSubstituteStep step;

        Execution(ConfigTemplateSubstituteStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);
            Run<?, ?> run = getContext().get(Run.class);
            EnvVars callerEnv = new EnvVars();
            if (run != null) {
                callerEnv.overrideAll(run.getEnvironment(listener));
            }
            // Also merge in whatever the Jenkinsfile has exported as pipeline `env.*`/withCredentials
            // bindings visible at call time (secrets, AppVersion/AppCommit/AppBuildDate, etc.) — this
            // step reads them, it does not resolve credentials itself.
            EnvVars stepEnv = getContext().get(EnvVars.class);
            if (stepEnv != null) {
                callerEnv.overrideAll(stepEnv);
            }

            ConfigSetRepository repository = ConfigSetRepository.onMaster();
            boolean hasCommon = step.commonConfigSet != null && !step.commonConfigSet.trim().isEmpty();
            boolean hasBuildVersion = step.buildVersion != null && !step.buildVersion.trim().isEmpty();

            ConfigDeploymentBindingRepository bindingRepository = ConfigDeploymentBindingRepository.onMaster();
            ConfigDeploymentBinding pinnedBinding = hasBuildVersion
                    ? bindingRepository.find(step.envConfigSet, step.buildVersion)
                    : null;

            EffectiveConfigResolver.Effective effective;
            Integer commonVersionNumberUsed;
            int envVersionNumberUsed;

            if (pinnedBinding != null) {
                commonVersionNumberUsed = pinnedBinding.getCommonVersionNumber();
                envVersionNumberUsed = pinnedBinding.getEnvVersionNumber();
                effective = EffectiveConfigResolver.resolveAtVersions(repository, step.commonConfigSet,
                        commonVersionNumberUsed, step.envConfigSet, envVersionNumberUsed);
                listener.getLogger().println("[configTemplateSubstitute] buildVersion='" + step.buildVersion
                        + "' is pinned to common=v" + commonVersionNumberUsed + ", env=v" + envVersionNumberUsed
                        + " (recorded " + new java.util.Date(pinnedBinding.getDeployedAtUtc()) + ") — replaying it.");
            } else {
                effective = EffectiveConfigResolver.resolve(repository, step.commonConfigSet, step.envConfigSet);
                commonVersionNumberUsed = hasCommon
                        ? repository.getConfigSet(step.commonConfigSet).getActiveVersion().getVersionNumber()
                        : null;
                envVersionNumberUsed = repository.getConfigSet(step.envConfigSet).getActiveVersion().getVersionNumber();
            }

            Map<String, String> pairs = ConfigJsonTree.flatten(effective.mergedJson);

            FilePath target = workspace.child(step.file);
            if (!target.exists()) {
                throw new IllegalStateException("File not found in workspace: " + step.file);
            }
            String text = target.readToString();

            int substituted = 0;
            for (Map.Entry<String, String> pair : pairs.entrySet()) {
                String token = "#{" + pair.getKey() + "}#";
                if (text.contains(token)) {
                    text = text.replace(token, Matcher.quoteReplacement(pair.getValue()));
                    substituted++;
                }
            }
            // Values already resolved by the caller's own withCredentials/deploy-version.env
            // mechanisms (secrets + AppVersion/AppCommit/AppBuildDate) — pulled from the calling
            // build's environment, not resolved by this step.
            for (String key : new TreeSet<>(callerEnv.keySet())) {
                String token = "#{" + key + "}#";
                if (text.contains(token)) {
                    text = text.replace(token, Matcher.quoteReplacement(callerEnv.get(key)));
                    substituted++;
                }
            }

            target.write(text, StandardCharsets.UTF_8.name());
            listener.getLogger().println("[configTemplateSubstitute] Substituted " + substituted
                    + " token(s) in " + step.file + ".");

            Set<String> remaining = new HashSet<>(ConfigJsonTree.extractTokens(text));
            if (!remaining.isEmpty()) {
                throw new IllegalStateException(
                        "configTemplateSubstitute: Unsubstituted tokens remain in " + step.file + ": "
                                + String.join(", ", remaining));
            }

            // This was a real, successful substitution (never recorded on a validate-only run, since
            // configTemplateValidate is a separate step) -> record/update the build-version binding.
            if (hasBuildVersion) {
                bindingRepository.record(step.envConfigSet, step.buildVersion,
                        commonVersionNumberUsed, envVersionNumberUsed);
                listener.getLogger().println("[configTemplateSubstitute] Recorded binding: buildVersion='"
                        + step.buildVersion + "' -> common=v" + commonVersionNumberUsed
                        + ", env=v" + envVersionNumberUsed + " for '" + step.envConfigSet + "'.");
            }
            return null;
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
            return "Substitute #{Path}# tokens from a Config Template Sync ConfigSet pair";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            Set<Class<?>> ctx = new HashSet<>();
            Collections.addAll(ctx, TaskListener.class, FilePath.class, Run.class, EnvVars.class);
            return ctx;
        }
    }
}
