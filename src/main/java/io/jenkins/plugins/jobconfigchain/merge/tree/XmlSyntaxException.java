package io.jenkins.plugins.jobconfigchain.merge.tree;

/**
 * Unchecked syntax exception for malformed XML — mirrors {@code com.google.gson.JsonSyntaxException}'s
 * role so {@code validateSyntaxOrFail}'s single {@code catch (RuntimeException e)} clause needs no
 * format-specific branching.
 */
final class XmlSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    XmlSyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
