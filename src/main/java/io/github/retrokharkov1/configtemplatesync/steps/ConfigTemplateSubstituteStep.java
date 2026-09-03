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
 * Pipeline step {@code configTemplateSubstitute(projectKey, environment, file, redeployFromRun,
 * useBase, version)} (FR-21–FR-27, FR-54, FR-79–FR-85, FR-96–FR-103, UF-4/UF-6).
 *
 * <p>Per OQ-7, this step re-runs the same flatten-and-compare drift check {@code
 * configTemplateValidate} performs, as a defensive re-check, rather than trusting that validate
 * already ran in this Jenkinsfile.</p>
 *
 * <p>Per FR-96/FR-97, every successful real substitution derives its own Run-identity key from
 * {@code run.getExternalizableId()} and unconditionally (default-on, no opt-in) creates/updates a
 * Deployment Binding under that key — <b>unless</b> an explicit {@code version} parameter is
 * supplied, which suppresses the binding lookup/write entirely for that call (FR-98). A narrow,
 * explicitly-supplied {@code redeployFromRun} parameter (FR-100–FR-103) redirects the binding
 * LOOKUP to a different Run's identity, for the deliberate cross-Run rollback/redeploy case
 * (UF-6) that an automatically-derived, single-Run-scoped key cannot serve on its own (FR-99).</p>
 *
 * <p>Per FR-13/FR-21, any dotted path declared secret in the common and/or env Config Set's secrets
 * manifest has its real value resolved <b>exclusively</b> from the Jenkins credential ID declared
 * for that path — via {@link com.cloudbees.plugins.credentials.CredentialsProvider#findCredentialById}
 * scoped to this build's {@link Run} — never from an env var the calling Jenkinsfile happens to have
 * set. If the declared credential ID does not resolve, the build fails loudly (NFR-7) naming the
 * missing credential; it never falls back to a placeholder or empty value. Non-secret paths keep the
 * pre-existing behavior: an env var whose name matches the dotted path wins if present, otherwise
 * the flattened effective-config value is used.</p>
 *
 * <p>{@code projectKey}/{@code environment}/{@code file} may be omitted from the call entirely if a
 * prior {@code setupConfigTemplate} call in the same build already supplied them (FR-90–FR-95);
 * explicit call-site values always win per parameter (FR-93).</p>
 */
public class ConfigTemplateSubstituteStep extends Step {

    private String projectKey;
    private String environment;
    private String file;
    private String redeployFromRun;
    private Boolean useBase;
    private Integer version;

    @DataBoundConstructor
    public ConfigTemplateSubstituteStep() {
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

    /** FR-100: bare integer (current job) or {@code <jobFullName>#<buildNumber>} (cross-job). */
    public String getRedeployFromRun() {
        return redeployFromRun;
    }

    @DataBoundSetter
    public void setRedeployFromRun(String redeployFromRun) {
        this.redeployFromRun = redeployFromRun;
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
        return new Execution(context, projectKey, environment, file, redeployFromRun, useBase, version);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String projectKey;
        private final String environment;
        private final String file;
        private final String redeployFromRun;
        private final Boolean useBase;
        private final Integer version;

        Execution(StepContext context, String projectKey, String environment, String file,
                  String redeployFromRun, Boolean useBase, Integer version) {
            super(context);
            this.projectKey = projectKey;
            this.environment = environment;
            this.file = file;
            this.redeployFromRun = redeployFromRun;
            this.useBase = useBase;
            this.version = version;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);
            EnvVars envVars = getContext().get(EnvVars.class);
            Run<?, ?> run = getContext().get(Run.class);

            StepSupport.EffectiveParams params = StepSupport.mergeWithSetupState(
                    run, projectKey, environment, file, redeployFromRun, useBase, version);

            ConfigSetRepository configSetRepository = StepSupport.newRepository();
            ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();

            TreeNode effective;
            ContentType effectiveType;
            List<ResolvedBaseVersion> resolvedBaseChain;
            List<ConfigSet> resolvedBaseConfigSets;
            ConfigSetVersion envVersion = null;
            boolean pinned = false;
            boolean skipBindingWrite;

            boolean hasVersion = params.version != null;
            boolean hasRedeployFromRun = params.redeployFromRun != null && !params.redeployFromRun.trim().isEmpty();
            String ownIdentity = run.getExternalizableId(); // FR-96

            // FR-81: useBase=true bypasses the env Config Set and its base-chain resolution machinery
            // ENTIRELY, for every invocation shape — not only when `version` is also supplied. `env`
            // stays null throughout this method whenever useBase=true (FR-83: no env manifest merge).
            ConfigSet env = params.useBase ? null : StepSupport.requireEnv(configSetRepository,
                    params.projectKey, params.environment);

            if (hasVersion) {
                // ── Branch A (FR-79–82, FR-98, FR-101's top precedence) ──────────────────────────
                if (params.useBase) {
                    StepSupport.ResolvedEffective resolved =
                            StepSupport.resolveUseBaseOnly(configSetRepository, params.projectKey, params.version);
                    effective = resolved.mergedConfig;
                    effectiveType = resolved.contentType;
                    resolvedBaseChain = resolved.resolvedBaseChain;
                    resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                } else {
                    envVersion = env.getVersion(params.version);
                    if (envVersion == null) {
                        throw new AbortException("Env Config Set for projectKey '" + params.projectKey
                                + "', environment '" + params.environment + "' has no version " + params.version
                                + " to resolve (explicit 'version' parameter)");
                    }
                    List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(params.projectKey, envVersion);
                    StepSupport.ResolvedEffective resolved =
                            StepSupport.resolveEffective(configSetRepository, chain, envVersion, env);
                    effective = resolved.mergedConfig;
                    effectiveType = resolved.contentType;
                    resolvedBaseChain = resolved.resolvedBaseChain;
                    resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                }
                skipBindingWrite = true; // FR-98
                if (hasRedeployFromRun) {
                    listener.getLogger().println("[configTemplateSync] 'redeployFromRun' ('" + params.redeployFromRun
                            + "') was supplied together with an explicit 'version' — per FR-101, 'version' takes "
                            + "full precedence and 'redeployFromRun' is ignored for this call.");
                }
            } else if (hasRedeployFromRun) {
                // ── Branch B (FR-100–103, FR-101's middle precedence) ─────────────────────────────
                String targetIdentity = resolveTargetIdentity(run, params.redeployFromRun);
                ConfigDeploymentBinding targetBinding =
                        bindingRepository.find(params.projectKey, params.environment, targetIdentity);
                if (targetBinding != null) {
                    FrozenChain frozen = resolveFrozenChain(configSetRepository, targetBinding, targetIdentity);
                    resolvedBaseChain = frozen.resolvedBaseChain;
                    resolvedBaseConfigSets = frozen.resolvedBaseConfigSets;
                    effectiveType = frozen.effectiveType;
                    envVersion = env == null ? null : env.getVersion(targetBinding.getEnvVersionNumber());
                    effective = EffectiveConfigResolver.resolveChain(effectiveType,
                            baseContentsOf(effectiveType, frozen), envVersion == null ? null : envVersion.getContentJson());
                    pinned = true;
                    listener.getLogger().println("[configTemplateSync] redeployFromRun '" + params.redeployFromRun
                            + "' (resolved target '" + targetIdentity + "') is pinned to base chain ["
                            + joinChain(resolvedBaseChain) + "] / env v" + targetBinding.getEnvVersionNumber());
                } else {
                    // FR-103: fail-loud-but-non-blocking — warn by name, then fall back to live resolution.
                    listener.getLogger().println("[configTemplateSync][WARN] redeployFromRun '"
                            + params.redeployFromRun + "' (resolved target '" + targetIdentity
                            + "') has no deployment binding; falling back to the currently active/pinned "
                            + "base chain for projectKey '" + params.projectKey + "', environment '"
                            + params.environment + "'.");
                    if (params.useBase) {
                        StepSupport.ResolvedEffective resolved =
                                StepSupport.resolveUseBaseOnly(configSetRepository, params.projectKey, null);
                        effective = resolved.mergedConfig;
                        effectiveType = resolved.contentType;
                        resolvedBaseChain = resolved.resolvedBaseChain;
                        resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                    } else {
                        envVersion = env.getActiveVersion();
                        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(params.projectKey, envVersion);
                        StepSupport.ResolvedEffective resolved =
                                StepSupport.resolveEffective(configSetRepository, chain, envVersion, env);
                        effective = resolved.mergedConfig;
                        effectiveType = resolved.contentType;
                        resolvedBaseChain = resolved.resolvedBaseChain;
                        resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                        logResolvedChain(listener, chain, resolvedBaseChain);
                    }
                }
                skipBindingWrite = false; // FR-97/FR-102: redeployFromRun never suppresses the own-run write
            } else {
                // ── Branch C (FR-25/26/27, FR-96/97's ordinary case, FR-101's default) ───────────
                ConfigDeploymentBinding ownBinding =
                        bindingRepository.find(params.projectKey, params.environment, ownIdentity);
                if (ownBinding != null) {
                    FrozenChain frozen = resolveFrozenChain(configSetRepository, ownBinding, ownIdentity);
                    resolvedBaseChain = frozen.resolvedBaseChain;
                    resolvedBaseConfigSets = frozen.resolvedBaseConfigSets;
                    effectiveType = frozen.effectiveType;
                    envVersion = env == null ? null : env.getVersion(ownBinding.getEnvVersionNumber());
                    effective = EffectiveConfigResolver.resolveChain(effectiveType,
                            baseContentsOf(effectiveType, frozen), envVersion == null ? null : envVersion.getContentJson());
                    pinned = true;
                    listener.getLogger().println(
                            "[configTemplateSync] This run's own prior binding is pinned to base chain ["
                                    + joinChain(resolvedBaseChain) + "] / env v" + ownBinding.getEnvVersionNumber());
                } else if (params.useBase) {
                    // FR-27-equivalent for useBase mode: this Run's very first real substitution —
                    // expected, no warning. FR-81: resolve directly against COMMON, no env involved.
                    StepSupport.ResolvedEffective resolved =
                            StepSupport.resolveUseBaseOnly(configSetRepository, params.projectKey, null);
                    effective = resolved.mergedConfig;
                    effectiveType = resolved.contentType;
                    resolvedBaseChain = resolved.resolvedBaseChain;
                    resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                } else {
                    // FR-27: this Run's very first real substitution under its own identity —
                    // expected, NOT a warned-about fallback.
                    envVersion = env.getActiveVersion();
                    List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(params.projectKey, envVersion);
                    StepSupport.ResolvedEffective resolved =
                            StepSupport.resolveEffective(configSetRepository, chain, envVersion, env);
                    effective = resolved.mergedConfig;
                    effectiveType = resolved.contentType;
                    resolvedBaseChain = resolved.resolvedBaseChain;
                    resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                }
                skipBindingWrite = false; // FR-97: always writes/updates the own-Run binding, default-on
            }

            FilePath target = workspace.child(params.file);
            if (!target.exists()) {
                throw new AbortException("Target config file not found: " + params.file);
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
                    "[configTemplateSync] Substituted " + params.file + " for projectKey '" + params.projectKey
                            + "', environment '" + params.environment + "' using base chain [" + joinChain(resolvedBaseChain)
                            + "] / env v" + (envVersion == null ? "(none)" : envVersion.getVersionNumber())
                            + (pinned ? " (pinned)" : ""));

            if (!skipBindingWrite) {
                // FR-97/FR-102: ALWAYS keyed by the CURRENT run's own identity, never by
                // redeployFromRun's target — this is what "forward-chains" a fresh, individually-
                // replayable binding for THIS run even when its content was pinned from a redeploy
                // target (Branch B's found-binding case).
                int envVersionNumber = envVersion == null ? 0 : envVersion.getVersionNumber();
                bindingRepository.save(params.projectKey, params.environment, ownIdentity,
                        resolvedBaseChain, envVersionNumber, System.currentTimeMillis());
            }

            return null;
        }

        /** FR-26/FR-103: per-reference fallback log line naming what each chain entry resolved to. */
        private static void logResolvedChain(TaskListener listener, List<BaseConfigReference> chain,
                                              List<ResolvedBaseVersion> resolvedBaseChain) {
            for (int i = 0; i < chain.size(); i++) {
                BaseConfigReference ref = chain.get(i);
                ResolvedBaseVersion rv = resolvedBaseChain.get(i);
                String how = ref.getPinMode() == PinMode.PINNED ? "PINNED" : "was ACTIVE";
                listener.getLogger().println(
                        "[configTemplateSync] projectKey '" + ref.getProjectKey() + "' resolved to v"
                                + rv.getVersionNumber() + " (" + how + ")");
            }
        }

        /** FR-100: bare integer -> current job's own externalizableId shape; already-"#"-shaped -> used as-is. */
        private static String resolveTargetIdentity(Run<?, ?> run, String redeployFromRun) {
            if (redeployFromRun.indexOf('#') >= 0) {
                return redeployFromRun;
            }
            return run.getParent().getFullName() + "#" + redeployFromRun.trim();
        }

        /** Groups a frozen-binding-chain resolution's outputs, shared by Branches B and C's found-binding paths. */
        private static final class FrozenChain {
            final List<ResolvedBaseVersion> resolvedBaseChain;
            final List<ConfigSet> resolvedBaseConfigSets;
            final ContentType effectiveType;

            FrozenChain(List<ResolvedBaseVersion> resolvedBaseChain, List<ConfigSet> resolvedBaseConfigSets,
                        ContentType effectiveType) {
                this.resolvedBaseChain = resolvedBaseChain;
                this.resolvedBaseConfigSets = resolvedBaseConfigSets;
                this.effectiveType = effectiveType;
            }
        }

        /**
         * Resolves a {@link ConfigDeploymentBinding}'s frozen chain directly, by (projectKey,
         * versionNumber) lookups — no ACTIVE/PINNED branching needed, the binding already recorded
         * concrete version numbers. A pinned project/version that's since vanished fails loud rather
         * than silently substituting wrong/empty content.
         */
        private static FrozenChain resolveFrozenChain(ConfigSetRepository configSetRepository,
                ConfigDeploymentBinding binding, String identityLabel) throws AbortException {
            List<ResolvedBaseVersion> resolvedBaseChain = new ArrayList<>();
            List<ConfigSet> resolvedBaseConfigSets = new ArrayList<>();
            for (ResolvedBaseVersion rv : binding.getResolvedBaseChain()) {
                ConfigSet baseConfigSet = configSetRepository.findCommon(rv.getProjectKey());
                if (baseConfigSet == null) {
                    throw new AbortException("No common Config Set found for projectKey '"
                            + rv.getProjectKey() + "' (frozen in deployment binding for '" + identityLabel + "')");
                }
                ConfigSetVersion baseVersion = baseConfigSet.getVersion(rv.getVersionNumber());
                if (baseVersion == null) {
                    throw new AbortException("Common Config Set '" + rv.getProjectKey()
                            + "' has no version " + rv.getVersionNumber()
                            + " (frozen in deployment binding for '" + identityLabel + "')");
                }
                resolvedBaseChain.add(rv);
                resolvedBaseConfigSets.add(baseConfigSet);
            }
            ContentType effectiveType = resolvedBaseConfigSets.isEmpty()
                    ? ContentType.JSON : resolvedBaseConfigSets.get(0).getContentType();
            return new FrozenChain(resolvedBaseChain, resolvedBaseConfigSets, effectiveType);
        }

        private static List<TreeNode> baseContentsOf(ContentType effectiveType, FrozenChain frozen) {
            List<TreeNode> baseContents = new ArrayList<>();
            for (int i = 0; i < frozen.resolvedBaseChain.size(); i++) {
                ConfigSetVersion baseVersion = frozen.resolvedBaseConfigSets.get(i)
                        .getVersion(frozen.resolvedBaseChain.get(i).getVersionNumber());
                baseContents.add(TreeFormats.forType(effectiveType).parse(baseVersion.getContentJson()));
            }
            return baseContents;
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
