package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.retrokharkov1.configtemplatesync.merge.EffectiveConfigResolver;
import io.github.retrokharkov1.configtemplatesync.merge.TokenExtractor;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormats;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreePaths;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.model.PinMode;
import io.github.retrokharkov1.configtemplatesync.model.ResolvedBaseVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigDeploymentBindingRepository;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>Per FR-13/FR-21, any dotted path declared secret in the common and/or env Config Set's secrets
 * manifest has its real value resolved <b>exclusively</b> from the Jenkins credential ID declared
 * for that path — via {@link com.cloudbees.plugins.credentials.CredentialsProvider#findCredentialById}
 * scoped to this build's {@link Run} — never from an env var the calling Jenkinsfile happens to have
 * set. If the declared credential ID does not resolve, the build fails loudly (NFR-7) naming the
 * missing credential; it never falls back to a placeholder or empty value. Non-secret paths keep the
 * pre-existing behavior: an env var whose name matches the dotted path wins if present, otherwise
 * the flattened effective-config value is used.</p>
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
            Run<?, ?> run = getContext().get(Run.class);

            ConfigSetRepository configSetRepository = StepSupport.newRepository();
            ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();

            ConfigSet env = StepSupport.requireEnv(configSetRepository, projectKey, environment);

            TreeNode effective;
            ContentType effectiveType;
            List<ResolvedBaseVersion> resolvedBaseChain;
            List<ConfigSet> resolvedBaseConfigSets;
            ConfigSetVersion envVersion;
            boolean pinned = false;
            boolean hasBuildVersion = buildVersion != null && !buildVersion.trim().isEmpty();
            ConfigDeploymentBinding existingBinding = hasBuildVersion
                    ? bindingRepository.find(projectKey, environment, buildVersion)
                    : null;

            if (existingBinding != null) {
                // Branch 1: buildVersion given, binding exists -> resolve the FROZEN chain directly,
                // by (projectKey, versionNumber) lookups. No ACTIVE/PINNED branching needed — the
                // binding already recorded concrete version numbers. A pinned project/version that's
                // since vanished must fail loud rather than silently substitute wrong/empty content.
                // The binding was only ever created by a prior resolveEffective run (FR-62), which
                // already enforced cross-chain content-type consistency at freeze time — so the first
                // resolved base's own ContentType is reused directly here, not re-checked.
                resolvedBaseChain = new ArrayList<>();
                resolvedBaseConfigSets = new ArrayList<>();
                for (ResolvedBaseVersion rv : existingBinding.getResolvedBaseChain()) {
                    ConfigSet baseConfigSet = configSetRepository.findCommon(rv.getProjectKey());
                    if (baseConfigSet == null) {
                        throw new AbortException("No common Config Set found for projectKey '"
                                + rv.getProjectKey() + "' (frozen in deployment binding for buildVersion '"
                                + buildVersion + "')");
                    }
                    ConfigSetVersion baseVersion = baseConfigSet.getVersion(rv.getVersionNumber());
                    if (baseVersion == null) {
                        throw new AbortException("Common Config Set '" + rv.getProjectKey()
                                + "' has no version " + rv.getVersionNumber()
                                + " (frozen in deployment binding for buildVersion '" + buildVersion + "')");
                    }
                    resolvedBaseChain.add(rv);
                    resolvedBaseConfigSets.add(baseConfigSet);
                }
                effectiveType = resolvedBaseConfigSets.isEmpty()
                        ? ContentType.JSON : resolvedBaseConfigSets.get(0).getContentType();
                List<TreeNode> baseContents = new ArrayList<>();
                for (int i = 0; i < resolvedBaseChain.size(); i++) {
                    ConfigSetVersion baseVersion = resolvedBaseConfigSets.get(i)
                            .getVersion(resolvedBaseChain.get(i).getVersionNumber());
                    baseContents.add(TreeFormats.forType(effectiveType).parse(baseVersion.getContentJson()));
                }
                envVersion = env.getVersion(existingBinding.getEnvVersionNumber());
                pinned = true;
                effective = EffectiveConfigResolver.resolveChain(effectiveType, baseContents,
                        envVersion == null ? null : envVersion.getContentJson());
                listener.getLogger().println(
                        "[configTemplateSync] buildVersion '" + buildVersion
                                + "' is pinned to base chain [" + joinChain(resolvedBaseChain)
                                + "] / env v" + existingBinding.getEnvVersionNumber());
            } else {
                envVersion = env.getActiveVersion();
                List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(projectKey, envVersion);
                StepSupport.ResolvedEffective resolved =
                        StepSupport.resolveEffective(configSetRepository, chain, envVersion);
                effective = resolved.mergedConfig;
                effectiveType = resolved.contentType;
                resolvedBaseChain = resolved.resolvedBaseChain;
                resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;

                if (hasBuildVersion) {
                    // Branch 2: buildVersion given, no binding -> live resolution, with per-reference
                    // fallback logging (FR-26).
                    listener.getLogger().println(
                            "[configTemplateSync][WARN] No deployment binding found for buildVersion '"
                                    + buildVersion + "' (projectKey '" + projectKey + "', environment '"
                                    + environment + "'); falling back to the currently active/pinned "
                                    + "base chain.");
                    for (int i = 0; i < chain.size(); i++) {
                        BaseConfigReference ref = chain.get(i);
                        ResolvedBaseVersion rv = resolvedBaseChain.get(i);
                        String how = ref.getPinMode() == PinMode.PINNED ? "PINNED" : "was ACTIVE";
                        listener.getLogger().println(
                                "[configTemplateSync] projectKey '" + ref.getProjectKey() + "' resolved to v"
                                        + rv.getVersionNumber() + " (" + how + ")");
                    }
                }
                // Branch 3 (no buildVersion at all) falls through here identically, minus the extra
                // per-reference logging above (FR-27, unchanged default path).
            }

            FilePath target = workspace.child(file);
            if (!target.exists()) {
                throw new AbortException("Target config file not found: " + file);
            }
            String originalContent = target.readToString();

            // OQ-7: defensive re-check, reusing the exact same drift comparison as validate, rather
            // than trusting that configTemplateValidate already ran earlier in this pipeline.
            StepSupport.validateOrThrow(effective, originalContent, listener);

            Map<String, String> secretsManifest = StepSupport.mergedSecretsManifest(resolvedBaseConfigSets, env);
            String substituted = substitute(effective, originalContent, envVars, secretsManifest, run);

            if (TokenExtractor.containsAnyToken(substituted)) {
                Set<String> remaining = TokenExtractor.extractTokenPaths(substituted);
                throw new AbortException(
                        "[configTemplateSync] Substitution incomplete — tokens remain unresolved: " + remaining);
            }

            target.write(substituted, null);
            listener.getLogger().println(
                    "[configTemplateSync] Substituted " + file + " for projectKey '" + projectKey
                            + "', environment '" + environment + "' using base chain [" + joinChain(resolvedBaseChain)
                            + "] / env v" + (envVersion == null ? "(none)" : envVersion.getVersionNumber())
                            + (pinned ? " (pinned)" : ""));

            // FR-23: only a successful REAL substitution run with a buildVersion supplied
            // creates/updates a deployment binding — never a validate-only or dry-run call. Runs
            // unconditionally in all 3 branches whenever buildVersion is present, including the
            // pinned-and-unchanged branch 1 case, which is then a no-op-content re-save that only
            // refreshes deployedAtUtcEpochMillis (preserves the existing "always touch the binding's
            // timestamp on a successful real substitution" behavior).
            if (hasBuildVersion) {
                int envVersionNumber = envVersion == null ? 0 : envVersion.getVersionNumber();
                bindingRepository.save(projectKey, environment, buildVersion,
                        resolvedBaseChain, envVersionNumber, System.currentTimeMillis());
            }

            return null;
        }

        private static String joinChain(List<ResolvedBaseVersion> resolvedBaseChain) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < resolvedBaseChain.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(resolvedBaseChain.get(i).toString());
            }
            return sb.toString();
        }

        private String substitute(TreeNode effective, String content, EnvVars envVars,
                                   Map<String, String> secretsManifest, Run<?, ?> run) throws AbortException {
            Map<String, TreeNode> flattened = TreePaths.flatten(effective);
            String result = content;
            for (Map.Entry<String, TreeNode> entry : flattened.entrySet()) {
                String dottedPath = entry.getKey();
                String token = "#{" + dottedPath + "}#";
                if (!result.contains(token)) {
                    continue;
                }
                String value;
                String credentialId = secretsManifest.get(dottedPath);
                if (credentialId != null) {
                    // FR-13/FR-21: a manifest-declared secret path's real value is resolved EXCLUSIVELY
                    // from the declared Jenkins credential ID — never from an env var, never the raw
                    // (placeholder) JSON leaf. Fails the build loudly (NFR-7) if the credential is
                    // missing/inaccessible rather than silently substituting anything else.
                    value = StepSupport.resolveSecretOrThrow(dottedPath, credentialId, run);
                } else if (envVars != null && envVars.containsKey(dottedPath)) {
                    // Non-secret paths keep the pre-existing override behavior: a value already present
                    // in the calling Jenkinsfile's environment wins over the flattened JSON leaf.
                    value = envVars.get(dottedPath);
                } else {
                    value = TreePaths.leafAsString(entry.getValue());
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
            context.add(Run.class);
            return Collections.unmodifiableSet(context);
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
