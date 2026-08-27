package io.github.retrokharkov1.configtemplatesync;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the effective, two-tier configuration for a project+environment: "take the general
 * (common) config + merge with env config (env overrides on conflict) -&gt; THEN apply secrets by
 * key." This class performs the merge step only; secret-value substitution still happens the same
 * way already designed by the calling Jenkinsfile's own {@code withCredentials} block, AFTER this
 * merge, not inside the plugin.
 *
 * <p>Both {@code commonConfigSet} and {@code envConfigSet} are plain {@link ConfigSet}s — no new
 * Java type is needed for the "common" vs "env" distinction, it is purely a naming convention at
 * the call site (e.g. {@code "myapp-common"} + {@code "myapp-dev"}).
 */
public final class EffectiveConfigResolver {

    private EffectiveConfigResolver() {
    }

    /** Merged active JSON + merged secrets manifest for one common/env ConfigSet pair. */
    public static final class Effective {
        public final JsonNode mergedJson;
        public final Map<String, String> mergedSecretsManifest;

        Effective(JsonNode mergedJson, Map<String, String> mergedSecretsManifest) {
            this.mergedJson = mergedJson;
            this.mergedSecretsManifest = mergedSecretsManifest;
        }
    }

    /**
     * @param repository      backing store
     * @param commonConfigSet key of the general/cross-env ConfigSet (may be {@code null}/blank to
     *                        skip the common layer entirely)
     * @param envConfigSet    key of the env-specific override ConfigSet
     */
    public static Effective resolve(ConfigSetRepository repository, String commonConfigSet, String envConfigSet) {
        String commonJson = isBlank(commonConfigSet) ? null : repository.getActive(commonConfigSet);
        String envJson = repository.getActive(envConfigSet);
        if (envJson == null) {
            throw new IllegalStateException(
                    "ConfigSet '" + envConfigSet + "' has no active version");
        }
        if (!isBlank(commonConfigSet) && commonJson == null) {
            throw new IllegalStateException(
                    "ConfigSet '" + commonConfigSet + "' has no active version");
        }

        JsonNode merged = ConfigJsonTree.deepMerge(commonJson, envJson);

        // Secrets manifest follows the same override-wins rule: env entries win on key conflict.
        Map<String, String> mergedManifest = new LinkedHashMap<>();
        if (!isBlank(commonConfigSet)) {
            mergedManifest.putAll(repository.getConfigSet(commonConfigSet).getSecretsManifest());
        }
        mergedManifest.putAll(repository.getConfigSet(envConfigSet).getSecretsManifest());

        return new Effective(merged, mergedManifest);
    }

    /**
     * Same merge as {@link #resolve(ConfigSetRepository, String, String)} but pins each layer to
     * an explicit, historical {@link ConfigSetVersion} number instead of "whatever is currently
     * active" — used by {@code configTemplateSubstitute}'s build-version pinning (§4a): replaying
     * an older build's config must use the version numbers that build actually shipped with, even
     * if a newer version has since been activated.
     *
     * @param commonVersionNumber ignored when {@code commonConfigSet} is blank; required (non-null)
     *                            otherwise
     * @param envVersionNumber    required — the env layer is never optional
     */
    public static Effective resolveAtVersions(ConfigSetRepository repository,
                                               String commonConfigSet, Integer commonVersionNumber,
                                               String envConfigSet, int envVersionNumber) {
        String commonJson = null;
        Map<String, String> mergedManifest = new LinkedHashMap<>();

        if (!isBlank(commonConfigSet)) {
            if (commonVersionNumber == null) {
                throw new IllegalArgumentException(
                        "commonVersionNumber is required when commonConfigSet is set");
            }
            ConfigSetVersion commonVersion = repository.getConfigSet(commonConfigSet).getVersion(commonVersionNumber);
            if (commonVersion == null) {
                throw new IllegalStateException("ConfigSet '" + commonConfigSet + "' has no version "
                        + commonVersionNumber);
            }
            commonJson = commonVersion.getContentJson();
            mergedManifest.putAll(repository.getConfigSet(commonConfigSet).getSecretsManifest());
        }

        ConfigSetVersion envVersion = repository.getConfigSet(envConfigSet).getVersion(envVersionNumber);
        if (envVersion == null) {
            throw new IllegalStateException("ConfigSet '" + envConfigSet + "' has no version " + envVersionNumber);
        }
        String envJson = envVersion.getContentJson();
        mergedManifest.putAll(repository.getConfigSet(envConfigSet).getSecretsManifest());

        JsonNode merged = ConfigJsonTree.deepMerge(commonJson, envJson);
        return new Effective(merged, mergedManifest);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
