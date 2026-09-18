package io.jenkins.plugins.jobconfigchain.ui;

import hudson.PluginWrapper;
import jenkins.model.Jenkins;

/**
 * Single source of truth for this plugin's own Jenkins "short-name" (the path segment Jenkins
 * serves this plugin's {@code src/main/webapp/} resources under: {@code /plugin/<short-name>/...},
 * see PluginWrapper#getShortName() / https://www.jenkins.io/doc/developer/views/exposing-bundled-resources/).
 *
 * <p>Root-caused 2026-09 (see commit 6f2f83a, the artifactId rename from {@code config-template-sync}
 * to {@code job-config-chain}): the Monaco editor assets under {@code src/main/webapp/monaco/} are
 * still packaged correctly, but the two Jelly views that reference them
 * ({@code CommonConfigSetPage/index.jelly}, {@code ConfigTemplatesJobAction/index.jelly}) had the
 * OLD short-name hardcoded literally six times across the two files, so every request 404'd
 * against the live plugin's real {@code /plugin/job-config-chain/...} URL space. Rather than fix
 * the six literals to the new name (which would silently rot again on the next rename), both
 * views now resolve the short-name at render time through this one class — the short-name can
 * never drift from whatever the plugin is actually installed/packaged as.
 *
 * <p>{@link Jenkins#getPluginManager()}'s {@code whichPlugin(Class)} returns {@code null} when
 * this class is loaded outside a packaged {@code .hpi} (e.g. a plain unit test with no
 * {@code JenkinsRule}), so {@link #FALLBACK} — the artifactId this class was written against — is
 * used in that case; it is never reached against a real, installed Jenkins instance.
 */
final class PluginShortName {

    /** The current pom.xml {@code <artifactId>} — used only when no packaged plugin is found (see class javadoc). */
    private static final String FALLBACK = "job-config-chain";

    private PluginShortName() {
    }

    static String get() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return FALLBACK;
        }
        PluginWrapper wrapper = jenkins.getPluginManager().whichPlugin(PluginShortName.class);
        return wrapper != null ? wrapper.getShortName() : FALLBACK;
    }
}
