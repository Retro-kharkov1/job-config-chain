package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import org.htmlunit.Page;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves the "Config Templates" job sidebar link is now job-scoped, exactly like Jenkins' own
 * "Configure" entry: {@link ConfigTemplatesJobAction#getUrlName()} always returns the plain
 * relative segment {@code "configTemplates"} (so Stapler exposes it at
 * {@code /job/<name>/configTemplates}, per {@code hudson.model.Action#getUrlName()}'s javadoc),
 * and hitting that job-scoped URL redirects to whichever of the three real destinations {@link
 * ConfigTemplatesJobActionFactory}'s already-existing resolution logic computes from the job's
 * (optional) {@link ConfigTemplatesJobProperty}:
 *
 * <ul>
 *   <li>no property (or a blank {@code projectKey}) → the generic {@code /configTemplates} root
 *       list ("nothing set up, browse everything" — {@link ConfigTemplatesJobAction}'s javadoc);</li>
 *   <li>{@code projectKey} set, no {@code environment} → the project OVERVIEW page
 *       {@code /configTemplates/<projectKey>} ({@link ProjectConfigPage}), NOT
 *       {@code /configTemplates/<projectKey>/common} — owner clarification 2026-09-02: the
 *       overview page is actionable (lists env Config Sets + lets you create one), the common
 *       page is not job/environment-specific;</li>
 *   <li>{@code projectKey} + {@code environment} both set → the env page
 *       {@code /configTemplates/<projectKey>/<environment>} ({@link EnvConfigSetPage}).</li>
 * </ul>
 */
public class ConfigTemplatesJobActionAssociationTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void jobAction_isReachableAtTheJobScopedUrl_notTheOldAbsolutePath() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-url-shape");

        ConfigTemplatesJobAction action = findJobAction(project);

        assertEquals("getUrlName() must be a plain relative segment so Stapler exposes it at "
                        + "/job/<name>/configTemplates, exactly like /job/<name>/configure",
                "configTemplates", action.getUrlName());
    }

    @Test
    public void jobWithNoPropertyConfigured_redirectsToTheGenericRootList() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-none");

        assertRedirectsTo(project, jenkins.getURL() + "configTemplates");
    }

    @Test
    public void jobWithBlankProjectKey_redirectsToTheGenericRootList() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-blank");
        project.addProperty(new ConfigTemplatesJobProperty("", ""));

        assertRedirectsTo(project, jenkins.getURL() + "configTemplates");
    }

    @Test
    public void jobWithProjectKeyOnly_redirectsToTheProjectOverview_notTheCommonPage() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-project-only");
        project.addProperty(new ConfigTemplatesJobProperty("myproj", ""));

        assertRedirectsTo(project, jenkins.getURL() + "configTemplates/myproj");
    }

    @Test
    public void jobWithProjectKeyAndEnvironment_redirectsToTheEnvPage() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-project-env");
        project.addProperty(new ConfigTemplatesJobProperty("myproj", "dev"));

        assertRedirectsTo(project, jenkins.getURL() + "configTemplates/myproj/dev");
    }

    /**
     * Hits {@code /job/<name>/configTemplates} (the job-scoped entry point this action now
     * exposes) and confirms the browser lands on {@code expectedFinalUrl} after following the
     * {@code doIndex}-issued redirect — i.e. the redirect actually fired (this would 404 if
     * {@code doIndex} were missing/misnamed) and pointed at the right one of the three real
     * destination pages.
     */
    private void assertRedirectsTo(FreeStyleProject project, String expectedFinalUrl) throws Exception {
        String jobScopedUrl = jenkins.getURL() + "job/" + project.getName() + "/configTemplates";
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        // The env destination page embeds the Monaco editor's loader.js, which htmlunit's JS
        // engine cannot parse (modern ES6 syntax) — same known limitation worked around the same
        // way throughout ConfigTemplatesUiTest (see e.g. envEditPage_inlineScriptsAreSyntacticallyValidJs_withRemoveSecretCode).
        // Irrelevant here: this test only cares that the redirect landed on the right URL, not
        // that the destination page's JS executes.
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(new URL(jobScopedUrl));
        assertTrue("expected the job-scoped URL to redirect to " + expectedFinalUrl
                        + " but landed on " + page.getUrl(),
                page.getUrl().toString().equals(expectedFinalUrl)
                        || page.getUrl().toString().equals(expectedFinalUrl + "/"));
    }

    private static ConfigTemplatesJobAction findJobAction(FreeStyleProject project) {
        List<? extends Action> actions = project.getAllActions();
        ConfigTemplatesJobAction action = actions.stream()
                .filter(ConfigTemplatesJobAction.class::isInstance)
                .map(ConfigTemplatesJobAction.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull("every Job must be contributed a ConfigTemplatesJobAction sidebar link", action);
        return action;
    }
}
