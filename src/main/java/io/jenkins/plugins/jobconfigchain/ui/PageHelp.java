package io.jenkins.plugins.jobconfigchain.ui;

import hudson.Extension;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Serves the in-UI help for the plugin's three admin pages (the (?) icons rendered by core's
 * {@code f:helpLink}), in the browser's language.
 *
 * <p>Core resolves localized help files ({@code help-field_uk.html}) only for a {@code Descriptor}'s
 * own fields. The admin pages are not descriptors, so this small hidden action does the same
 * lookup for them: {@code /job-config-chain-help/<name>} serves
 * {@code ui/PageHelp/help-<name>[_<lang>].html} from the classpath, trying each of the request's
 * locales in order and falling back to the English file. Each file is an HTML fragment wrapped in a
 * {@code <div>}, as core's help loader expects.</p>
 *
 * <p>Access is {@link Jenkins#ADMINISTER}, the same as the pages the help belongs to. The name is
 * restricted to letters, so no request can reach any other resource.</p>
 */
@Extension
@Restricted(NoExternalUse.class)
public class PageHelp implements RootAction {

    /** URL segment the help is served under. */
    public static final String URL_NAME = "job-config-chain-help";

    private static final Pattern NAME = Pattern.compile("[A-Za-z]{1,40}");

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
        return URL_NAME;
    }

    /** {@code /job-config-chain-help/<name>}. */
    public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String rest = req.getRestOfPath();
        String name = rest.startsWith("/") ? rest.substring(1) : rest;
        URL url = NAME.matcher(name).matches() ? resolve(name, req.getLocales()) : null;
        if (url == null) {
            rsp.sendError(StaplerResponse2.SC_NOT_FOUND);
            return;
        }
        byte[] body;
        try (InputStream in = url.openStream()) {
            body = in.readAllBytes();
        }
        rsp.setContentType("text/html;charset=UTF-8");
        rsp.getOutputStream().write(body);
    }

    /** Locale lookup, the same order core uses for descriptor help: language_country, then language, then default. */
    static URL resolve(String name, Enumeration<Locale> locales) {
        String base = "PageHelp/help-" + name;
        while (locales != null && locales.hasMoreElements()) {
            Locale locale = locales.nextElement();
            String language = locale.getLanguage();
            if (language.isEmpty()) {
                continue;
            }
            URL url = null;
            if (!locale.getCountry().isEmpty()) {
                url = PageHelp.class.getResource(base + '_' + language + '_' + locale.getCountry() + ".html");
            }
            if (url == null) {
                url = PageHelp.class.getResource(base + '_' + language + ".html");
            }
            if (url != null) {
                return url;
            }
            if ("en".equals(language)) {
                break;
            }
        }
        return PageHelp.class.getResource(base + ".html");
    }
}
