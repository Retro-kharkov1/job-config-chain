package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The single flatten-and-compare implementation shared by {@code configTemplateValidate} (FR-17)
 * and the defensive re-check inside {@code configTemplateSubstitute} (OQ-7, resolved 2026-08-27:
 * substitute reuses this exact logic rather than duplicating it or trusting validate already ran).
 */
public final class DriftChecker {

    private DriftChecker() {
    }

    public static DriftResult diff(JsonObject effectiveConfig, String targetFileContent) {
        Set<String> expectedKeys = JsonPaths.flatten(effectiveConfig).keySet();
        Set<String> actualTokens = TokenExtractor.extractTokenPaths(targetFileContent);

        Set<String> missing = new LinkedHashSet<>(actualTokens);
        missing.removeAll(expectedKeys);

        Set<String> orphaned = new LinkedHashSet<>(expectedKeys);
        orphaned.removeAll(actualTokens);

        return new DriftResult(missing, orphaned);
    }
}
