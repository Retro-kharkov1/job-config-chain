package io.github.retrokharkov1.configtemplatesync.steps;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.AbortException;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.retrokharkov1.configtemplatesync.merge.BaseChainResolver;
import io.github.retrokharkov1.configtemplatesync.merge.DriftChecker;
import io.github.retrokharkov1.configtemplatesync.merge.DriftResult;
import io.github.retrokharkov1.configtemplatesync.merge.EffectiveConfigResolver;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormats;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.model.JobConfigTemplateVersion;
import io.github.retrokharkov1.configtemplatesync.model.PinMode;
import io.github.retrokharkov1.configtemplatesync.model.ResolvedBaseVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import io.github.retrokharkov1.configtemplatesync.ui.JobConfigTemplateProperty;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logic shared by {@code configTemplateValidate} and the defensive re-check inside
 * {@code configTemplateSubstitute} (OQ-7) — kept here as plain, unit-testable Java so neither step
 * duplicates the flatten-and-compare implementation.
 */
final class StepSupport {

    private StepSupport() {
    }

    /**
     * Resolves the global COMMON Config Set named {@code configKey}, throwing a clear, fail-loud
     * message if it does not exist (NFR-7). Wording matches the tech-lead scoping decision
     * (2026-09-14, pipeline-steps.md §3(b)): named in {@code configKey} vocabulary, not the retired
     * {@code projectKey}-centric phrasing (the underlying field is the same string).
     */
    static ConfigSet requireCommon(ConfigSetRepository repository, String configKey) throws AbortException {
        ConfigSet common = repository.findCommon(configKey);
        if (common == null) {
            throw new AbortException(
                    "[configTemplateSync] No global COMMON Config Set found for configKey '" + configKey + "'.");
        }
        return common;
    }

    /**
     * Return type of {@link #resolveEffective}: the merged JSON, the concrete
     * {@link ResolvedBaseVersion} list to freeze into a {@code ConfigDeploymentBinding} (FR-23), and
     * the resolved {@link ConfigSet} objects in the same chain order (reused by
     * {@link #mergedSecretsManifest}, so the chain is fetched from the repository exactly once).
     */
    static final class ResolvedEffective {
        final TreeNode mergedConfig;
        final ContentType contentType;
        final List<ResolvedBaseVersion> resolvedBaseChain;
        final List<ConfigSet> resolvedBaseConfigSets;

        ResolvedEffective(TreeNode mergedConfig, ContentType contentType, List<ResolvedBaseVersion> resolvedBaseChain,
                           List<ConfigSet> resolvedBaseConfigSets) {
            this.mergedConfig = mergedConfig;
            this.contentType = contentType;
            this.resolvedBaseChain = resolvedBaseChain;
            this.resolvedBaseConfigSets = resolvedBaseConfigSets;
        }
    }

    /**
     * Resolves an ordered {@code baseChain} (FR-51) to its actual base contents, folds them with the
     * env version's content per {@link EffectiveConfigResolver#resolveChain}, and returns both the
     * merged result and the concrete {@link ResolvedBaseVersion}s to freeze into a deployment binding
     * (FR-23). Fails loud (NFR-7): throws {@link AbortException} naming the missing project/version
     * for the first unresolvable chain entry encountered, and — FR-62, the pipeline-path half of the
     * cross-chain type-consistency invariant (mirrors {@code ConfigSetPage#doSave}'s UI-path check) —
     * if the resolved chain's base Config Sets don't all share one {@link ContentType}.
     */
    static ResolvedEffective resolveEffective(ConfigSetRepository repository, List<BaseConfigReference> baseChain,
                                               ConfigSetVersion envVersion, ConfigSet env) throws AbortException {
        String envPatch = envVersion == null ? null : envVersion.getContentJson();
        // FR-87 gap fix: an empty resolved chain can legitimately occur (an explicitlyStandalone env
        // version has zero bases to fold) — fall back to the env Config Set's own already-stored
        // contentType, not a hardcoded ContentType.JSON, which would silently mis-type every
        // standalone env Config Set's effective content. `env` staying null is only ever a
        // theoretical case in this codebase (kept null-tolerant for symmetry with
        // mergedSecretsManifest's own null-tolerant `env` parameter).
        ContentType fallbackContentType = env != null ? env.getContentType() : ContentType.JSON;
        return resolveChainCore(repository, baseChain, envPatch, fallbackContentType);
    }

    /**
     * Job-scoped resolution entry point (tech-lead scoping decision, 2026-09-14,
     * pipeline-steps.md §2): implements matrix rows 1, 2, 4, 5, 6, 7 of the "Pipeline call
     * resolution" table. Row 3 ({@code configKey} without {@code useBase: true}) is a param-shape
     * error checked earlier, before this method is ever called — see {@link #mergeWithSetupState}.
     *
     * <p>{@code job.getProperty(JobConfigTemplateProperty.class) == null}, or a property with zero
     * saved versions, is a valid, non-error resolution target on every row that resolves against the
     * Job's own config: the effective configuration is simply empty (job-scoped-config.md's "state
     * 1"), never an error.</p>
     */
    static ResolvedEffective resolveJobScoped(ConfigSetRepository repository, Job<?, ?> job,
            boolean useBase, String configKey, Integer version) throws AbortException {
        if (useBase && configKey != null && !configKey.trim().isEmpty()) {
            // Rows 6/7: direct global COMMON lookup by name — the Job's own config is never
            // consulted here; ownConfigVersionNumber for the eventual binding write is 0, mirroring
            // today's exact useBase=true behavior.
            return resolveUseBaseOnly(repository, configKey, version);
        }

        JobConfigTemplateProperty prop = job.getProperty(JobConfigTemplateProperty.class);
        if (prop == null) {
            // State 1 (job-scoped-config.md): nothing configured yet, not an error.
            return emptyResolvedEffective(ContentType.JSON);
        }

        JobConfigTemplateVersion jobVersion = version != null ? prop.getVersion(version) : prop.getActiveVersion();
        if (version != null && jobVersion == null) {
            throw new AbortException("[configTemplateSync] No version " + version
                    + " found on this Job's own config (Job='" + job.getFullName() + "')");
        }
        if (jobVersion == null) {
            // No active version yet (property exists but zero saved versions) — still state 1.
            ContentType type = prop.getContentType() != null ? prop.getContentType() : ContentType.JSON;
            return emptyResolvedEffective(type);
        }

        // Rows 1/2/4/5: the version's OWN recorded baseChain, folded as-is — NO synthesized
        // FR-52-style default. This is the load-bearing difference from resolveEffective/
        // effectiveBaseChain's ConfigSet/env-specific back-compat behavior, which does not apply to
        // JobConfigTemplateVersion (see job-scoped-config.md).
        List<BaseConfigReference> baseChain = jobVersion.getBaseChain();
        // Rows 1/2 fold in this version's own content as the overlay patch; rows 4/5 (useBase=true)
        // omit it entirely — the sole behavioral difference between the two row pairs.
        String overlayPatch = useBase ? null : jobVersion.getContentJson();
        ContentType fallbackContentType = prop.getContentType() != null ? prop.getContentType() : ContentType.JSON;
        return resolveChainCore(repository, baseChain, overlayPatch, fallbackContentType);
    }

    private static ResolvedEffective emptyResolvedEffective(ContentType type) {
        return new ResolvedEffective(TreeFormats.forType(type).emptyObject(), type,
                Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Shared chain-folding core (tech-lead refactor recommendation, 2026-09-14,
     * pipeline-steps.md §2): resolves {@code baseChain} against the repository, enforces the
     * cross-chain content-type-consistency invariant (FR-61/FR-62), folds every resolved base
     * left-to-right, and applies {@code overlayPatchOrNull} as the final RFC 7396 merge patch. Used
     * by both {@link #resolveEffective} (ConfigSet/env-based) and {@link #resolveJobScoped}
     * (Job-based) so neither duplicates the type-consistency-check/fold logic.
     */
    private static ResolvedEffective resolveChainCore(ConfigSetRepository repository,
            List<BaseConfigReference> baseChain, String overlayPatchOrNull, ContentType fallbackContentType)
            throws AbortException {
        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, baseChain);
        List<ResolvedBaseVersion> resolvedBaseChain = new ArrayList<>();
        List<ConfigSet> resolvedBaseConfigSets = new ArrayList<>();
        Set<ContentType> distinctTypes = EnumSet.noneOf(ContentType.class);
        List<String> typeReport = new ArrayList<>();
        for (BaseChainResolver.ResolvedReference r : resolved) {
            if (r.configSet == null) {
                throw new AbortException("No common Config Set found for projectKey '"
                        + r.reference.getProjectKey() + "' (referenced by base chain)");
            }
            if (r.version == null) {
                String what = r.reference.getPinMode() == PinMode.PINNED
                        ? "version " + r.reference.getPinnedVersionNumber()
                        : "an active version";
                throw new AbortException("Base Config Set '" + r.reference.getProjectKey() + "' has no " + what
                        + " to resolve (referenced by base chain)");
            }
            distinctTypes.add(r.configSet.getContentType());
            typeReport.add(r.reference.getProjectKey() + " (" + r.configSet.getContentType() + ")");
            resolvedBaseConfigSets.add(r.configSet);
            resolvedBaseChain.add(new ResolvedBaseVersion(r.reference.getProjectKey(), r.version.getVersionNumber()));
        }
        if (distinctTypes.size() > 1) {
            throw new AbortException("[configTemplateSync] Mismatched content types in base chain — "
                    + String.join(", ", typeReport) + " must all share one content type.");
        }
        ContentType resolvedType = distinctTypes.isEmpty() ? fallbackContentType : distinctTypes.iterator().next();
        List<TreeNode> baseContents = new ArrayList<>();
        for (BaseChainResolver.ResolvedReference r : resolved) {
            baseContents.add(TreeFormats.forType(resolvedType).parse(r.version.getContentJson()));
        }
        TreeNode merged = EffectiveConfigResolver.resolveChain(resolvedType, baseContents, overlayPatchOrNull);
        return new ResolvedEffective(merged, resolvedType, Collections.unmodifiableList(resolvedBaseChain),
                Collections.unmodifiableList(resolvedBaseConfigSets));
    }

    /**
     * FR-52's backward-compatibility default: an env version's own declared {@code baseChain} wins if
     * non-empty, otherwise synthesize a single {@link BaseConfigReference#active} entry pointing at
     * this projectKey's own common Config Set — exactly today's implicit single-base behavior,
     * generalized.
     */
    static List<BaseConfigReference> effectiveBaseChain(String projectKey, ConfigSetVersion envVersion) {
        if (envVersion != null) {
            if (envVersion.isExplicitlyStandalone()) {
                // FR-87: a deliberate zero-base declaration — never FR-52's synthesized single-entry
                // default. Checked first, before the declared/isEmpty() check below, to document the
                // actual precedence intent unambiguously (in practice the two checks never compete on
                // the same real version — a version cannot simultaneously be explicitly-standalone AND
                // carry a non-empty chain, enforced at write time by ConfigSet.addVersion).
                return Collections.emptyList();
            }
            List<BaseConfigReference> declared = envVersion.getBaseChain();
            if (declared != null && !declared.isEmpty()) {
                return declared;
            }
        }
        return Collections.singletonList(BaseConfigReference.active(projectKey));
    }

    /**
     * FR-81: resolves directly against {@code configKey}'s own global COMMON Config Set only,
     * completely bypassing any Job-scoped resolution machinery entirely (matrix rows 6/7). A
     * supplied {@code version} pins this SAME COMMON Config Set to that exact version.
     */
    static ResolvedEffective resolveUseBaseOnly(ConfigSetRepository repository, String configKey, Integer version)
            throws AbortException {
        ConfigSet common = requireCommon(repository, configKey);
        ConfigSetVersion resolved = version != null ? common.getVersion(version) : common.getActiveVersion();
        if (resolved == null) {
            String what = version != null ? "version " + version : "an active version";
            throw new AbortException("[configTemplateSync] Global COMMON Config Set for configKey '" + configKey
                    + "' has no " + what + " to resolve"
                    + (version != null ? " (explicit 'version' parameter, useBase=true)" : ""));
        }
        TreeNode content = TreeFormats.forType(common.getContentType()).parse(resolved.getContentJson());
        List<ResolvedBaseVersion> chain = Collections.singletonList(
                new ResolvedBaseVersion(configKey, resolved.getVersionNumber()));
        List<ConfigSet> configSets = Collections.singletonList(common);
        return new ResolvedEffective(content, common.getContentType(), chain, configSets);
    }

    /**
     * Resolution-mode phrase for the missing/orphaned-keys messages (tech-lead scoping decision,
     * 2026-09-14, pipeline-steps.md §5): describes which matrix row (1/2/4/5/6/7) resolved this
     * call, so the message is self-sufficient in a build log with no other context.
     */
    static String describeResolutionMode(boolean useBase, String configKey, Integer version) {
        return describeResolutionMode(useBase, configKey, version, false);
    }

    /**
     * Overload adding the {@code (frozen deployment binding replay)} suffix
     * (pipeline-steps.md §5, "the belt-and-suspenders internal re-check ... needs ... one
     * refinement beyond the plain matrix-row phrase") — used at {@code
     * configTemplateSubstitute}'s defensive re-check call site when a frozen Deployment Binding
     * replay is active ({@code pinned == true}), so the message never misleadingly implies an
     * explicit {@code version} was passed on this call when it was actually replayed.
     */
    static String describeResolutionMode(boolean useBase, String configKey, Integer version, boolean pinned) {
        String base;
        if (useBase && configKey != null && !configKey.trim().isEmpty()) {
            base = version != null
                    ? "global Config Set '" + configKey + "', version " + version
                    : "global Config Set '" + configKey + "', active version";
        } else if (useBase) {
            base = version != null
                    ? "this Job's own local config's base chain only, version " + version
                    : "this Job's own local config's base chain only, active version";
        } else {
            base = version != null
                    ? "this Job's own local config, version " + version
                    : "this Job's own local config, active version";
        }
        return pinned ? base + " (frozen deployment binding replay)" : base;
    }

    /**
     * Runs the flatten-and-compare drift check (FR-17) and fails the build (FR-18) if any token in
     * the target file has no matching key in the effective configuration. Orphaned keys are only
     * warned about, never fatal (FR-19). {@code resolutionMode} (see {@link
     * #describeResolutionMode}) is spliced into both messages so each is self-sufficient in a build
     * log with no other context (pipeline-steps.md §5).
     */
    static void validateOrThrow(TreeNode effectiveConfig, String targetFileContent, TaskListener listener,
            String jobFullName, String file, String resolutionMode) throws AbortException {
        DriftResult result = DriftChecker.diff(effectiveConfig, targetFileContent);
        if (result.hasOrphanedKeys()) {
            List<String> orphaned = new ArrayList<>(result.getOrphanedKeys());
            String plural = orphaned.size() == 1 ? "key" : "keys";
            listener.getLogger().println(
                    "[configTemplateSync][WARN] Orphaned config keys — Job='" + jobFullName
                            + "', resolved via " + resolutionMode + ": the effective configuration has "
                            + orphaned.size() + " " + plural + " with no matching token in target file '" + file
                            + "': " + orphaned + ". This is not a failure, but likely means either the key is "
                            + "genuinely unused or the token was removed from the file without removing the key.");
        }
        if (result.hasMissingKeys()) {
            List<String> missing = new ArrayList<>(result.getMissingKeys());
            String plural = missing.size() == 1 ? "token" : "tokens";
            String message = "Missing config keys — target file '" + file + "' (Job='" + jobFullName
                    + "', resolved via " + resolutionMode + ") references " + missing.size() + " " + plural
                    + " with no matching key in the effective configuration: " + missing
                    + ". Check for a typo in the token's dotted path, or add this key to the resolved source "
                    + "(this Job's own Config Templates, or the global COMMON Config Set referenced via "
                    + "useBase/configKey).";
            listener.getLogger().println("[configTemplateSync][ERROR] " + message);
            throw new AbortException("[configTemplateSync] " + message);
        }
    }

    static ConfigSetRepository newRepository() {
        return new ConfigSetRepository();
    }

    /**
     * Merges the resolved base chain's and (optional) calling target's own secrets manifests (dotted
     * path -> Jenkins credential ID) into one lookup table for substitution (FR-13/FR-21). Later
     * entries in {@code resolvedBaseConfigSets} take precedence over earlier ones on a conflicting
     * path, matching the same later-wins fold {@link EffectiveConfigResolver#resolveChain} already
     * applies to content — the manifest is metadata about the same content tree the chain folds, so
     * the precedence rule for "which base determines this path" is identical whether asking about the
     * value or the secret-ness at that path. {@code ownManifestOrNull} (the Job's own
     * {@code JobConfigTemplateProperty#getSecretsManifest()}, or {@code null} when the Job's own
     * config was not consulted — matrix rows 6/7) still wins over every base.
     */
    static Map<String, String> mergedSecretsManifest(List<ConfigSet> resolvedBaseConfigSets,
            Map<String, String> ownManifestOrNull) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (ConfigSet base : resolvedBaseConfigSets) {
            if (base != null) {
                merged.putAll(base.getSecretsManifest());
            }
        }
        if (ownManifestOrNull != null) {
            merged.putAll(ownManifestOrNull);
        }
        return merged;
    }

    /**
     * Resolves a secret-manifest-declared path's real value <b>exclusively</b> from the Jenkins
     * credential store, using the credential ID declared for that path in the owning Config Set's
     * secrets manifest (FR-13/FR-21). Looks the credential up scoped to the running {@link Run} via
     * {@link CredentialsProvider#findCredentialById(String, Class, Run, List)} — the standard,
     * correctly-authorization-scoped way for step code to resolve an arbitrary credential ID without
     * requiring the pipeline author to have first bound it via {@code withCredentials}
     * (jenkinsci/credentials-plugin {@code CredentialsProvider} javadoc: this overload evaluates the
     * same "is the user triggering this run permitted to use this credential" check that
     * {@code withCredentials} relies on, returning {@code null} — never throwing — for both
     * "credential does not exist" and "not permitted", which is why the null-check below is the sole
     * source of truth for the fail-loud path required by NFR-7).
     *
     * <p>Never returns a placeholder or empty string on failure — always throws {@link
     * AbortException} naming both the dotted path and the missing/inaccessible credential ID
     * (NFR-7: fail loud, never fail silent).</p>
     */
    /**
     * FR-93: per-parameter merge of a call's own explicit arguments against a prior
     * {@code setupConfigTemplate} call's stored {@link ConfigTemplateSetupAction} state for the same
     * build — explicit call-site value always wins, evaluated field by field, never all-or-nothing.
     * Also implements FR-94 (fail-loud on insufficient parameters) as this method's single exit-check.
     */
    static final class EffectiveParams {
        final String file;
        final String redeployFromRun;
        final boolean useBase;
        final String configKey;
        final Integer version;

        EffectiveParams(String file, String redeployFromRun, boolean useBase, String configKey, Integer version) {
            this.file = file;
            this.redeployFromRun = redeployFromRun;
            this.useBase = useBase;
            this.configKey = configKey;
            this.version = version;
        }
    }

    static EffectiveParams mergeWithSetupState(Run<?, ?> run, String callFile, String callRedeployFromRun,
            Boolean callUseBase, String callConfigKey, Integer callVersion) throws AbortException {
        ConfigTemplateSetupAction setup = run == null ? null : run.getAction(ConfigTemplateSetupAction.class);

        String file = callFile != null ? callFile : (setup == null ? null : setup.getFile());
        String redeployFromRun = callRedeployFromRun != null
                ? callRedeployFromRun : (setup == null ? null : setup.getRedeployFromRun());
        boolean useBase = callUseBase != null ? callUseBase : (setup != null && setup.isUseBase());
        String configKey = callConfigKey != null ? callConfigKey : (setup == null ? null : setup.getConfigKey());
        Integer version = callVersion != null ? callVersion : (setup == null ? null : setup.getVersion());

        // Matrix row 3 (tech-lead scoping decision, 2026-09-14, pipeline-steps.md §3(a)): checked
        // FIRST, before any repository lookup/I-O — mirrors the missing-'file' check's own
        // fail-before-I/O placement below.
        if (!isBlank(configKey) && !useBase) {
            throw new AbortException("[configTemplateSync] 'configKey' ('" + configKey
                    + "') is only valid together with useBase: true — remove 'configKey', or add "
                    + "'useBase: true' to this call.");
        }

        List<String> missing = new ArrayList<>();
        if (isBlank(file)) {
            missing.add("file");
        }
        if (!missing.isEmpty()) {
            // FR-94's exact message shape.
            throw new AbortException("[configTemplateSync] Missing required parameter(s) " + missing
                    + " — not supplied explicitly on this call, and no prior setupConfigTemplate() call in this "
                    + "build provided them.");
        }
        return new EffectiveParams(file, redeployFromRun, useBase, configKey, version);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String resolveSecretOrThrow(String dottedPath, String credentialId, Run<?, ?> run)
            throws AbortException {
        StringCredentials credentials = CredentialsProvider.findCredentialById(
                credentialId, StringCredentials.class, run, Collections.emptyList());
        if (credentials == null) {
            throw new AbortException(
                    "[configTemplateSync] Secret path '" + dottedPath + "' declares Jenkins credential ID '"
                            + credentialId + "', but no such credential exists (or this build is not "
                            + "permitted to use it). Refusing to substitute a placeholder or empty value — "
                            + "fix the credential ID in the secrets manifest, or the build's credential "
                            + "permissions, and re-run.");
        }
        // Credential Usage Tracking (jenkinsci/credentials-plugin CredentialsProvider javadoc):
        // record that this run consumed the credential, same as a normal withCredentials binding would.
        CredentialsProvider.track(run, credentials);
        return credentials.getSecret().getPlainText();
    }
}
