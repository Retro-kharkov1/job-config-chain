package io.github.retrokharkov1.configtemplatesync.steps;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.gson.JsonObject;
import hudson.AbortException;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.retrokharkov1.configtemplatesync.merge.BaseChainResolver;
import io.github.retrokharkov1.configtemplatesync.merge.DriftChecker;
import io.github.retrokharkov1.configtemplatesync.merge.DriftResult;
import io.github.retrokharkov1.configtemplatesync.merge.EffectiveConfigResolver;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.model.PinMode;
import io.github.retrokharkov1.configtemplatesync.model.ResolvedBaseVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logic shared by {@code configTemplateValidate} and the defensive re-check inside
 * {@code configTemplateSubstitute} (OQ-7) — kept here as plain, unit-testable Java so neither step
 * duplicates the flatten-and-compare implementation.
 */
final class StepSupport {

    private StepSupport() {
    }

    /** Resolves the common ConfigSet, throwing a clear, fail-loud message if it does not exist (NFR-7). */
    static ConfigSet requireCommon(ConfigSetRepository repository, String projectKey) throws AbortException {
        ConfigSet common = repository.findCommon(projectKey);
        if (common == null) {
            throw new AbortException("No common Config Set found for projectKey '" + projectKey + "'");
        }
        return common;
    }

    /** Resolves the env ConfigSet, throwing a clear, fail-loud message if it does not exist (NFR-7). */
    static ConfigSet requireEnv(ConfigSetRepository repository, String projectKey, String environment)
            throws AbortException {
        ConfigSet env = repository.findEnv(projectKey, environment);
        if (env == null) {
            throw new AbortException(
                    "No env Config Set found for projectKey '" + projectKey + "', environment '" + environment + "'");
        }
        return env;
    }

    /**
     * Return type of {@link #resolveEffective}: the merged JSON, the concrete
     * {@link ResolvedBaseVersion} list to freeze into a {@code ConfigDeploymentBinding} (FR-23), and
     * the resolved {@link ConfigSet} objects in the same chain order (reused by
     * {@link #mergedSecretsManifest}, so the chain is fetched from the repository exactly once).
     */
    static final class ResolvedEffective {
        final JsonObject mergedConfig;
        final List<ResolvedBaseVersion> resolvedBaseChain;
        final List<ConfigSet> resolvedBaseConfigSets;

        ResolvedEffective(JsonObject mergedConfig, List<ResolvedBaseVersion> resolvedBaseChain,
                           List<ConfigSet> resolvedBaseConfigSets) {
            this.mergedConfig = mergedConfig;
            this.resolvedBaseChain = resolvedBaseChain;
            this.resolvedBaseConfigSets = resolvedBaseConfigSets;
        }
    }

    /**
     * Resolves an ordered {@code baseChain} (FR-51) to its actual base contents, folds them with the
     * env version's content per {@link EffectiveConfigResolver#resolveChain}, and returns both the
     * merged result and the concrete {@link ResolvedBaseVersion}s to freeze into a deployment binding
     * (FR-23). Fails loud (NFR-7): throws {@link AbortException} naming the missing project/version
     * for the first unresolvable chain entry encountered.
     */
    static ResolvedEffective resolveEffective(ConfigSetRepository repository, List<BaseConfigReference> baseChain,
                                               ConfigSetVersion envVersion) throws AbortException {
        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, baseChain);
        List<String> baseContents = new ArrayList<>();
        List<ResolvedBaseVersion> resolvedBaseChain = new ArrayList<>();
        List<ConfigSet> resolvedBaseConfigSets = new ArrayList<>();
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
            baseContents.add(r.version.getContentJson());
            resolvedBaseChain.add(new ResolvedBaseVersion(r.reference.getProjectKey(), r.version.getVersionNumber()));
            resolvedBaseConfigSets.add(r.configSet);
        }
        String envPatch = envVersion == null ? null : envVersion.getContentJson();
        JsonObject merged = EffectiveConfigResolver.resolveChain(baseContents, envPatch);
        return new ResolvedEffective(merged, Collections.unmodifiableList(resolvedBaseChain),
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
            List<BaseConfigReference> declared = envVersion.getBaseChain();
            if (declared != null && !declared.isEmpty()) {
                return declared;
            }
        }
        return Collections.singletonList(BaseConfigReference.active(projectKey));
    }

    /**
     * Runs the flatten-and-compare drift check (FR-17) and fails the build (FR-18) if any token in
     * the target file has no matching key in the effective configuration. Orphaned keys are only
     * warned about, never fatal (FR-19).
     */
    static void validateOrThrow(JsonObject effectiveConfig, String targetFileContent, TaskListener listener)
            throws AbortException {
        DriftResult result = DriftChecker.diff(effectiveConfig, targetFileContent);
        if (result.hasOrphanedKeys()) {
            List<String> orphaned = new ArrayList<>(result.getOrphanedKeys());
            listener.getLogger().println(
                    "[configTemplateSync][WARN] Orphaned config keys (no matching token in target file): "
                            + orphaned);
        }
        if (result.hasMissingKeys()) {
            List<String> missing = new ArrayList<>(result.getMissingKeys());
            String message = "[configTemplateSync] Missing config keys (token present in target file, "
                    + "no matching key in effective configuration): " + missing;
            listener.getLogger().println("[configTemplateSync][ERROR] " + message);
            throw new AbortException(message);
        }
    }

    static ConfigSetRepository newRepository() {
        return new ConfigSetRepository();
    }

    /**
     * Merges the resolved base chain's and (optional) env Config Set's secrets manifests (dotted
     * path -> Jenkins credential ID) into one lookup table for substitution (FR-13/FR-21). Later
     * entries in {@code resolvedBaseConfigSets} take precedence over earlier ones on a conflicting
     * path, matching the same later-wins fold {@link EffectiveConfigResolver#resolveChain} already
     * applies to content — the manifest is metadata about the same content tree the chain folds, so
     * the precedence rule for "which base determines this path" is identical whether asking about the
     * value or the secret-ness at that path. The env Config Set's own manifest still wins over every
     * base.
     */
    static Map<String, String> mergedSecretsManifest(List<ConfigSet> resolvedBaseConfigSets, ConfigSet env) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (ConfigSet base : resolvedBaseConfigSets) {
            if (base != null) {
                merged.putAll(base.getSecretsManifest());
            }
        }
        if (env != null) {
            merged.putAll(env.getSecretsManifest());
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
