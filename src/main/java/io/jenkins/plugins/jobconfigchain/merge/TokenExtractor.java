package io.jenkins.plugins.jobconfigchain.merge;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code #{Dotted.Path}#}-shaped placeholder tokens from target config file content (see
 * pipeline-steps.md's "Validation (drift detection)" section), and detects whether any such token
 * remains after substitution (see pipeline-steps.md's "Substitution (deploy-time)" section).
 */
public final class TokenExtractor {

    /** Token shape: {@code #{...}#}, capturing the inner dotted path. */
    public static final Pattern TOKEN_PATTERN = Pattern.compile("#\\{([^}]+)\\}#");

    private TokenExtractor() {
    }

    public static Set<String> extractTokenPaths(String fileContent) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(fileContent);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    public static boolean containsAnyToken(String fileContent) {
        return TOKEN_PATTERN.matcher(fileContent).find();
    }
}
