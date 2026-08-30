package io.github.retrokharkov1.configtemplatesync.steps;

import com.google.gson.JsonObject;
import hudson.AbortException;
import hudson.model.TaskListener;
import io.github.retrokharkov1.configtemplatesync.merge.DriftChecker;
import io.github.retrokharkov1.configtemplatesync.merge.DriftResult;
import io.github.retrokharkov1.configtemplatesync.merge.EffectiveConfigResolver;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;

import java.util.ArrayList;
import java.util.List;

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

    /** Resolves the effective config from a common ConfigSet's active version + an optional env version. */
    static JsonObject resolveEffective(ConfigSetVersion commonVersion, ConfigSetVersion envVersion) {
        String commonJson = commonVersion == null ? "{}" : commonVersion.getContentJson();
        String envPatch = envVersion == null ? null : envVersion.getContentJson();
        return EffectiveConfigResolver.resolve(commonJson, envPatch);
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
}
