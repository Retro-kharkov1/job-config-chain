package io.jenkins.plugins.jobconfigchain.merge;

import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The single flatten-and-compare implementation shared by {@code configTemplateValidate} (see
 * pipeline-steps.md's "Validation (drift detection)" section)
 * and the defensive re-check inside {@code configTemplateSubstitute} (substitute reuses this exact
 * logic rather than duplicating it or trusting validate already ran).
 *
 * <p>The drift comparison itself is a plain string-set comparison (dotted paths vs. {@code #{...}#}
 * tokens) and is entirely format-neutral — only the flattening of {@code effectiveConfig} into that
 * dotted-path key set is format-aware, delegated to {@link TreePaths#flatten}.</p>
 */
public final class DriftChecker {

    private DriftChecker() {
    }

    public static DriftResult diff(TreeNode effectiveConfig, String targetFileContent) {
        Set<String> expectedKeys = TreePaths.flatten(effectiveConfig).keySet();
        Set<String> actualTokens = TokenExtractor.extractTokenPaths(targetFileContent);

        Set<String> missing = new LinkedHashSet<>(actualTokens);
        missing.removeAll(expectedKeys);

        Set<String> orphaned = new LinkedHashSet<>(expectedKeys);
        orphaned.removeAll(actualTokens);

        return new DriftResult(missing, orphaned);
    }
}
