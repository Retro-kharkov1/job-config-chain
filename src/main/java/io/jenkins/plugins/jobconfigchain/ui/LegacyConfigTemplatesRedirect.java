package io.jenkins.plugins.jobconfigchain.ui;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.ManagementLink;
import jenkins.model.TransientActionFactory;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/**
 * Compatibility for the public URL rename {@code configTemplates} to {@code configChains}.
 *
 * <p>The old URL names stay routable only to answer old links: a GET is redirected (302) to the
 * same path and query under the new name; every other HTTP method answers 404. A redirect cannot
 * carry a POST body, so redirecting a POST would silently drop the request; no external caller is
 * known to POST to these URLs, and a stale page left open across an upgrade must be reloaded.
 * Nothing here has a sidebar entry or a Manage Jenkins tile (the icon is {@code null}).</p>
 *
 * <p>Persisted data is not involved: only the URL segment changed.</p>
 */
public final class LegacyConfigTemplatesRedirect {

    /** The URL segment that existed before the rename. */
    public static final String LEGACY_URL_NAME = "configTemplates";

    private LegacyConfigTemplatesRedirect() {
    }

    /**
     * Answers a request addressed to a legacy URL. {@code self} must be the object Stapler
     * dispatched to (it is looked up among the request's ancestors to learn the matched URL).
     */
    static void respond(Object self, String newUrlName, StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException {
        if (!"GET".equals(req.getMethod())) {
            rsp.sendError(StaplerResponse2.SC_NOT_FOUND);
            return;
        }
        Ancestor ancestor = req.findAncestor(self);
        String matched = ancestor == null ? req.getContextPath() + "/" + LEGACY_URL_NAME : ancestor.getUrl();
        String uri = req.getRequestURI();
        String suffix;
        if (uri.startsWith(matched)) {
            suffix = uri.substring(matched.length());
        } else {
            String rest = ancestor == null ? "" : ancestor.getRestOfUrl();
            suffix = rest.isEmpty() ? "" : "/" + rest;
        }
        rsp.sendRedirect(StaplerResponse2.SC_FOUND, redirectTarget(matched, suffix, req.getQueryString(), newUrlName));
    }

    /**
     * Pure URL arithmetic: {@code matched} is the matched legacy URL ending in the legacy segment,
     * {@code suffix} the remaining path (empty or starting with a slash), {@code query} the raw
     * query string or {@code null}.
     */
    static String redirectTarget(String matched, String suffix, String query, String newUrlName) {
        String base = matched.endsWith(LEGACY_URL_NAME)
                ? matched.substring(0, matched.length() - LEGACY_URL_NAME.length()) + newUrlName
                : matched;
        String path = suffix.isEmpty() ? base + "/" : base + suffix;
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    /** Hidden legacy entry for {@code /configTemplates/**} and {@code /manage/configTemplates/**}. */
    @Extension
    public static class Root extends ManagementLink {

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return ConfigTemplatesRootAction.URL_NAME;
        }

        @Override
        public String getUrlName() {
            return LEGACY_URL_NAME;
        }

        /** Requests to the bare legacy URL. */
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            respond(this, ConfigTemplatesRootAction.URL_NAME, req, rsp);
        }

        /** Requests to any path below the legacy URL. */
        public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            respond(this, ConfigTemplatesRootAction.URL_NAME, req, rsp);
        }
    }

    /** Hidden legacy action for {@code /job/<name>/configTemplates/**}. */
    public static class JobAction implements Action {

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return LEGACY_URL_NAME;
        }

        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            respond(this, ConfigTemplatesJobAction.URL_NAME, req, rsp);
        }

        public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            respond(this, ConfigTemplatesJobAction.URL_NAME, req, rsp);
        }
    }

    /** Contributes {@link JobAction} to every job. */
    @Extension
    public static class JobFactory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Collection<? extends Action> createFor(Job target) {
            return Collections.singleton(new JobAction());
        }
    }
}
