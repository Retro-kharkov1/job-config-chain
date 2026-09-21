package io.jenkins.plugins.jobconfigchain.steps;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.jobconfigchain.merge.EffectiveConfigResolver;
import io.jenkins.plugins.jobconfigchain.merge.TokenExtractor;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths;
import io.jenkins.plugins.jobconfigchain.model.ConfigDeploymentBinding;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.JobConfigTemplateVersion;
import io.jenkins.plugins.jobconfigchain.model.ResolvedBaseVersion;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigDeploymentBindingRepository;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
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
import java.util.stream.Collectors;

/**
 * Pipeline step {@code configTemplateSubstitute(file, useBase, configKey, version, redeployFromRun)}
 * — see pipeline-steps.md's "Substitution (deploy-time)" and "Build-identity pinning (rollback
 * safety)" sections, and user-flows.md's deploy-substitute and build-rollback-replay flows.
 *
 * <p>Every call resolves against the CALLING JOB's own attached local config
 * ({@code JobConfigTemplateProperty}) by construction, unless {@code useBase: true} + {@code
 * configKey} explicitly redirects to a named global COMMON Config Set — see pipeline-steps.md's
 * "Pipeline call resolution — the final parameter model" for the full 7-row resolution matrix.
 * There is no {@code projectKey}/{@code environment} parameter; that calling form was retired in
 * full (2026-09-14), not deprecated.</p>
 *
 * <p>This step re-runs the same flatten-and-compare drift check {@code
 * configTemplateValidate} performs, as a defensive re-check (see pipeline-steps.md's Validation
 * section), rather than trusting that validate already ran in this Jenkinsfile.</p>
 *
 * <p>Every successful real substitution derives its own Run-identity key from
 * {@code run.getExternalizableId()} and unconditionally (default-on, no opt-in) creates/updates a
 * Deployment Binding under that key — <b>unless</b> an explicit {@code version} parameter is
 * supplied, which suppresses the binding lookup/write entirely for that call. A narrow,
 * explicitly-supplied {@code redeployFromRun} parameter redirects the binding
 * LOOKUP to a different Run's identity, for the deliberate cross-Run rollback/redeploy case (see
 * user-flows.md's flow describing an operator rolling back a server to an older build and needing
 * the matching config to come back automatically) that an automatically-derived, single-Run-scoped
 * key cannot serve on its own. A Deployment Binding is now keyed purely by that Run-identity string
 * (2026-09-14) — there is no longer a separate Config-Key/environment dimension, since every call
 * resolves per-Job by construction.</p>
 *
 * <p>Any dotted path declared secret in a resolved base Config Set's and/or the
 * Job's own secrets manifest has its real value resolved <b>exclusively</b> from the Jenkins
 * credential ID declared for that path — via {@link com.cloudbees.plugins.credentials.CredentialsProvider#findCredentialById}
 * scoped to this build's {@link Run} — never from an env var the calling Jenkinsfile happens to have
 * set. If the declared credential ID does not resolve, the build fails loudly (see nfr.md's "Fail
 * loud, never fail silent" rule) naming the missing credential; it never falls back to a placeholder
 * or empty value. Non-secret paths keep the pre-existing behavior: an env var whose name matches the
 * dotted path wins if present, otherwise the flattened effective-config value is used.</p>
 *
 * <p>{@code file}/{@code useBase}/{@code configKey}/{@code version} may be omitted from the call
 * entirely if a prior {@code setupConfigTemplate} call in the same build already supplied them (see
 * pipeline-steps.md's "setupConfigTemplate build-scoped convenience step"); explicit call-site
 * values always win per parameter. Only {@code file} remains mandatory.</p>
 */
public class ConfigTemplateSubstituteStep extends Step {

    private String file;
    private String redeployFromRun;
    private Boolean useBase;
    private String configKey;
    private Integer version;

    @DataBoundConstructor
    public ConfigTemplateSubstituteStep() {
    }

    public String getFile() {
        return file;
    }

    @DataBoundSetter
    public void setFile(String file) {
        this.file = file;
    }

    /** Bare integer (current job) or {@code <jobFullName>#<buildNumber>} (cross-job). */
    public String getRedeployFromRun() {
        return redeployFromRun;
    }

    @DataBoundSetter
    public void setRedeployFromRun(String redeployFromRun) {
        this.redeployFromRun = redeployFromRun;
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
        return new Execution(context, file, redeployFromRun, useBase, configKey, version);
    }

    static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String file;
        private final String redeployFromRun;
        private final Boolean useBase;
        private final String configKey;
        private final Integer version;

        Execution(StepContext context, String file, String redeployFromRun, Boolean useBase, String configKey,
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
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);
            EnvVars envVars = getContext().get(EnvVars.class);
            Run<?, ?> run = getContext().get(Run.class);
            Job<?, ?> job = run.getParent(); // Run#getParent() already returns the owning Job — no
            // getRequiredContext() change needed (tech-lead scoping decision, 2026-09-14,
            // pipeline-steps.md §2).

            StepSupport.EffectiveParams params = StepSupport.mergeWithSetupState(
                    run, file, redeployFromRun, useBase, configKey, version);

            ConfigSetRepository configSetRepository = StepSupport.newRepository();
            ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
            JobConfigTemplateProperty prop = job.getProperty(JobConfigTemplateProperty.class);

            TreeNode effective;
            ContentType effectiveType;
            List<ResolvedBaseVersion> resolvedBaseChain;
            List<ConfigSet> resolvedBaseConfigSets;
            int ownConfigVersionNumber;
            boolean pinned = false;
            boolean skipBindingWrite;

            boolean hasVersion = params.version != null;
            boolean hasRedeployFromRun = params.redeployFromRun != null && !params.redeployFromRun.trim().isEmpty();
            String ownIdentity = run.getExternalizableId(); // automatic Run-identity key, see pipeline-steps.md's Build-identity pinning section

            if (hasVersion) {
                // ── Branch A (explicit version — top precedence per pipeline-steps.md's Precedence ordering) ──
                StepSupport.ResolvedEffective resolved = StepSupport.resolveJobScoped(
                        configSetRepository, job, params.useBase, params.configKey, params.version);
                effective = resolved.mergedConfig;
                effectiveType = resolved.contentType;
                resolvedBaseChain = resolved.resolvedBaseChain;
                resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                ownConfigVersionNumber = ownConfigVersionNumber(prop, params);
                skipBindingWrite = true; // explicit version suppresses binding lookup/write for this call
                if (hasRedeployFromRun) {
                    listener.getLogger().println("[configTemplateSync] 'redeployFromRun' ('" + params.redeployFromRun
                            + "') was supplied together with an explicit 'version' — 'version' takes "
                            + "full precedence and 'redeployFromRun' is ignored for this call.");
                }
            } else if (hasRedeployFromRun) {
                // ── Branch B (redeployFromRun — middle precedence per pipeline-steps.md's Precedence ordering) ──
                String targetIdentity = resolveTargetIdentity(run, params.redeployFromRun);
                ConfigDeploymentBinding targetBinding = bindingRepository.find(targetIdentity);
                if (targetBinding != null) {
                    FrozenChain frozen = resolveFrozenChain(configSetRepository, targetBinding, targetIdentity);
                    resolvedBaseChain = frozen.resolvedBaseChain;
                    resolvedBaseConfigSets = frozen.resolvedBaseConfigSets;
                    effectiveType = frozen.effectiveType;
                    ownConfigVersionNumber = targetBinding.getOwnConfigVersionNumber();
                    String overlayPatch = overlayPatchForFrozenReplay(prop, params, ownConfigVersionNumber);
                    effective = EffectiveConfigResolver.resolveChain(effectiveType,
                            baseContentsOf(effectiveType, frozen), overlayPatch);
                    pinned = true;
                    listener.getLogger().println("[configTemplateSync] redeployFromRun '" + params.redeployFromRun
                            + "' (resolved target '" + targetIdentity + "') is pinned to base chain ["
                            + joinChain(resolvedBaseChain) + "] / own config v" + ownConfigVersionNumber);
                } else {
                    // Fail-loud-but-non-blocking — warn by name, then fall back to live resolution.
                    listener.getLogger().println("[configTemplateSync][WARN] redeployFromRun '"
                            + params.redeployFromRun + "' (resolved target '" + targetIdentity
                            + "') has no deployment binding; falling back to the currently active/pinned "
                            + "base chain for Job '" + job.getFullName() + "'.");
                    StepSupport.ResolvedEffective resolved = StepSupport.resolveJobScoped(
                            configSetRepository, job, params.useBase, params.configKey, null);
                    effective = resolved.mergedConfig;
                    effectiveType = resolved.contentType;
                    resolvedBaseChain = resolved.resolvedBaseChain;
                    resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                    ownConfigVersionNumber = ownConfigVersionNumber(prop, params);
                    listener.getLogger().println(
                            "[configTemplateSync] Live-resolved base chain [" + joinChain(resolvedBaseChain) + "]");
                }
                skipBindingWrite = false; // redeployFromRun never suppresses the own-run write
            } else {
                // ── Branch C (ordinary case — no explicit version, no redeployFromRun) ───────────
                ConfigDeploymentBinding ownBinding = bindingRepository.find(ownIdentity);
                if (ownBinding != null) {
                    FrozenChain frozen = resolveFrozenChain(configSetRepository, ownBinding, ownIdentity);
                    resolvedBaseChain = frozen.resolvedBaseChain;
                    resolvedBaseConfigSets = frozen.resolvedBaseConfigSets;
                    effectiveType = frozen.effectiveType;
                    ownConfigVersionNumber = ownBinding.getOwnConfigVersionNumber();
                    String overlayPatch = overlayPatchForFrozenReplay(prop, params, ownConfigVersionNumber);
                    effective = EffectiveConfigResolver.resolveChain(effectiveType,
                            baseContentsOf(effectiveType, frozen), overlayPatch);
                    pinned = true;
                    listener.getLogger().println(
                            "[configTemplateSync] This run's own prior binding is pinned to base chain ["
                                    + joinChain(resolvedBaseChain) + "] / own config v" + ownConfigVersionNumber);
                } else {
                    // This Run's very first real substitution under its own identity —
                    // expected, NOT a warned-about fallback.
                    StepSupport.ResolvedEffective resolved = StepSupport.resolveJobScoped(
                            configSetRepository, job, params.useBase, params.configKey, null);
                    effective = resolved.mergedConfig;
                    effectiveType = resolved.contentType;
                    resolvedBaseChain = resolved.resolvedBaseChain;
                    resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
                    ownConfigVersionNumber = ownConfigVersionNumber(prop, params);
                }
                skipBindingWrite = false; // always writes/updates the own-Run binding, default-on
            }

            FilePath target = workspace.child(params.file);
            if (!target.exists()) {
                throw new AbortException("Target config file not found: " + params.file);
            }
            String originalContent = target.readToString();

            // Defensive re-check, reusing the exact same drift comparison as validate, rather
            // than trusting that configTemplateValidate already ran earlier in this pipeline. When
            // `pinned` (a frozen Deployment Binding replay, Branches B/C's found-binding paths), the
            // resolution-mode phrase uses the replayed `ownConfigVersionNumber` — not `params.version`,
            // which is null on this path — and gets the `(frozen deployment binding replay)` suffix
            // (pipeline-steps.md §5), so the message never implies an explicit `version` was passed on
            // this call when it was actually replayed from a prior build's frozen binding.
            boolean ownConfigConsultedForPhrase = !(params.useBase && params.configKey != null
                    && !params.configKey.trim().isEmpty());
            Integer versionForPhrase = (pinned && ownConfigConsultedForPhrase)
                    ? Integer.valueOf(ownConfigVersionNumber) : params.version;
            String resolutionMode = StepSupport.describeResolutionMode(
                    params.useBase, params.configKey, versionForPhrase, pinned);
            StepSupport.validateOrThrow(effective, originalContent, listener, job.getFullName(), params.file,
                    resolutionMode);

            // The Job's own secrets manifest is never merged in when useBase=true — mirrors
            // the pre-2026-09-14 "no env manifest merge" rule exactly, just against the Job's own
            // (non-versioned) manifest instead of an env Config Set's.
            Map<String, String> ownManifest = (params.useBase || prop == null) ? null : prop.getSecretsManifest();
            Map<String, String> secretsManifest = StepSupport.mergedSecretsManifest(resolvedBaseConfigSets, ownManifest);
            String substituted = substitute(effective, originalContent, envVars, secretsManifest, run);

            if (TokenExtractor.containsAnyToken(substituted)) {
                Set<String> remaining = TokenExtractor.extractTokenPaths(substituted);
                String wrapped = remaining.stream().map(k -> "#{" + k + "}#")
                        .collect(Collectors.joining(", ", "[", "]"));
                throw new AbortException(
                        "[configTemplateSync] Substitution incomplete — tokens remain unresolved: " + wrapped);
            }

            target.write(substituted, null);
            listener.getLogger().println(
                    "[configTemplateSync] Substituted " + params.file + " for Job '" + job.getFullName()
                            + "' using base chain [" + joinChain(resolvedBaseChain)
                            + "] / own config v" + ownConfigVersionNumber
                            + (pinned ? " (pinned)" : ""));

            if (!skipBindingWrite) {
                // ALWAYS keyed by the CURRENT run's own identity, never by
                // redeployFromRun's target — this is what "forward-chains" a fresh, individually-
                // replayable binding for THIS run even when its content was pinned from a redeploy
                // target (Branch B's found-binding case).
                bindingRepository.save(ownIdentity, resolvedBaseChain, ownConfigVersionNumber,
                        System.currentTimeMillis());
            }

            return null;
        }

        /**
         * The Job's own resolved config version number, for binding-write purposes — {@code 0} when
         * the Job's own config was not consulted at all (resolution-matrix rows 6/7,
         * {@code useBase=true}+{@code configKey}), mirroring
         * {@link StepSupport#resolveJobScoped}'s own resolution rules. Safe to call only AFTER
         * {@code resolveJobScoped} has already succeeded for the same parameters (so an explicit
         * {@code version} that doesn't exist has already been rejected there).
         */
        private static int ownConfigVersionNumber(JobConfigTemplateProperty prop, StepSupport.EffectiveParams params) {
            if (params.useBase && params.configKey != null && !params.configKey.trim().isEmpty()) {
                return 0; // rows 6/7 — Job's own config never consulted.
            }
            if (prop == null) {
                return 0; // state 1 — nothing configured.
            }
            JobConfigTemplateVersion v = params.version != null ? prop.getVersion(params.version) : prop.getActiveVersion();
            return v == null ? 0 : v.getVersionNumber();
        }

        /**
         * The Job's own version content to re-apply as the overlay patch on a frozen-binding replay
         * (Branches B/C's found-binding paths) — {@code null} when {@code useBase=true} (overlay
         * always omitted, matrix rows 4/5/6/7) or when the Job's own config was not consulted at
         * binding-write time ({@code ownConfigVersionNumber == 0}).
         */
        private static String overlayPatchForFrozenReplay(JobConfigTemplateProperty prop,
                StepSupport.EffectiveParams params, int ownConfigVersionNumber) {
            if (params.useBase || prop == null || ownConfigVersionNumber == 0) {
                return null;
            }
            JobConfigTemplateVersion v = prop.getVersion(ownConfigVersionNumber);
            return v == null ? null : v.getContentJson();
        }

        /** Bare integer -> current job's own externalizableId shape; already-"#"-shaped -> used as-is. */
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
                ConfigSetVersion baseVersion =
                        baseConfigSet.getVersion(rv.getVersionNumber());
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
                ConfigSetVersion baseVersion =
                        frozen.resolvedBaseConfigSets.get(i)
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
                    // A manifest-declared secret path's real value is resolved EXCLUSIVELY
                    // from the declared Jenkins credential ID — never from an env var, never the raw
                    // (placeholder) JSON leaf. Fails the build loudly (nfr.md's "Fail loud, never fail
                    // silent" rule) if the credential is missing/inaccessible rather than silently
                    // substituting anything else.
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
            return io.jenkins.plugins.jobconfigchain.ui.Messages.Step_Substitute_DisplayName();
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
