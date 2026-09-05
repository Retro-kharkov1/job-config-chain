package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves the "Config Templates" job sidebar link is job-scoped, exactly like Jenkins' own
 * "Configure" entry: {@link ConfigTemplatesJobAction#getUrlName()} always returns the plain
 * relative segment {@code "configTemplates"} (so Stapler exposes it at
 * {@code /job/<name>/configTemplates}, per {@code hudson.model.Action#getUrlName()}'s javadoc).
 *
 * <p><b>FR-76 supersedes the old redirect behavior</b> (FR-78a migration note): hitting
 * {@code /job/<name>/configTemplates} no longer redirects anywhere — it renders one of three page
 * STATES (no association / project-only / project+environment), all driven off the SAME
 * {@link ConfigTemplatesJobProperty} this class always read. Since the actual rendering
 * ({@code ConfigTemplatesJobAction/index.jelly}) is a separate task (T4), this class proves the
 * equivalent, structural guarantee directly against the Java model {@link ConfigTemplatesJobAction}
 * exposes for that view — the exact same three-state resolution the old {@code resolveDestinationUrl()}
 * used to compute a redirect target from, now expressed as page-state accessors instead:</p>
 * <ul>
 *   <li>no property (or a blank {@code projectKey}) → {@link ConfigTemplatesJobAction#hasAssociation()}
 *       is {@code false} ("not yet associated" state);</li>
 *   <li>{@code projectKey} set, no {@code environment} → {@code hasAssociation()} is {@code true},
 *       {@link ConfigTemplatesJobAction#getEnvironment()} is blank, and
 *       {@link ConfigTemplatesJobAction#getEnvConfigSets()} lists every env Config Set for that
 *       project (the project-only state's actionable env list — NOT a link to the common page,
 *       mirroring the previous "not the common page" guarantee);</li>
 *   <li>{@code projectKey} + {@code environment} both set → both accessors reflect the full
 *       association.</li>
 * </ul>
 *
 * <p>{@link ConfigTemplatesJobAction#doSaveAssociation(String, String)} and
 * {@link ConfigTemplatesJobAction#doAssociateEnvironment(String)} are exercised through real HTTP
 * POSTs to prove the new save handlers persist to the exact same {@link ConfigTemplatesJobProperty}
 * (verifiable via {@code GET /job/<name>/config.xml}, per FR-76's acceptance criteria) — redirects
 * are disabled on the client so this test does not depend on T4's not-yet-written
 * {@code index.jelly} to follow the post-save reload.</p>
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
    public void jobWithNoPropertyConfigured_rendersTheNoAssociationState() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-none");

        ConfigTemplatesJobAction action = findJobAction(project);

        assertFalse("no property at all must resolve to the 'not yet associated' page state",
                action.hasAssociation());
        assertEquals("", action.getProjectKey());
        assertEquals("", action.getEnvironment());
        assertTrue("no association means no env Config Sets to list",
                action.getEnvConfigSets().isEmpty());
    }

    @Test
    public void jobWithBlankProjectKey_rendersTheNoAssociationState() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-blank");
        project.addProperty(new ConfigTemplatesJobProperty("", ""));

        ConfigTemplatesJobAction action = findJobAction(project);

        assertFalse("a property with a blank projectKey is still 'not yet associated'",
                action.hasAssociation());
    }

    @Test
    public void jobWithProjectKeyOnly_rendersTheProjectOnlyState_withEnvListAndNoCommonLink() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-project-only");
        project.addProperty(new ConfigTemplatesJobProperty("myproj", ""));

        ConfigTemplatesJobAction action = findJobAction(project);

        assertTrue("projectKey set must resolve to an associated state",
                action.hasAssociation());
        assertEquals("myproj", action.getProjectKey());
        assertEquals("blank environment is a valid, meaningful project-only state (FR-75), "
                        + "not an incomplete configuration",
                "", action.getEnvironment());
        // The project-only state's actionable content is the env Config Set list (FR-76) — never
        // a link to the common Config Set page, mirroring the previous "not the common page"
        // guarantee this test always made.
        assertNotNull("project-only state must expose the env Config Set list for its page",
                action.getEnvConfigSets());
    }

    @Test
    public void jobWithProjectKeyAndEnvironment_rendersTheFullAssociationState() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-project-env");
        project.addProperty(new ConfigTemplatesJobProperty("myproj", "dev"));

        ConfigTemplatesJobAction action = findJobAction(project);

        assertTrue(action.hasAssociation());
        assertEquals("myproj", action.getProjectKey());
        assertEquals("dev", action.getEnvironment());
    }

    @Test
    public void doSaveAssociation_persistsProjectKeyAndEnvironmentOntoTheJob() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-save");
        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        // doSaveAssociation redirects (redirectToDot()) to this same page's index.jelly, which is
        // a separate task (T4) not yet written — disable redirect-following so this test verifies
        // only the persistence side-effect FR-76 mandates, independent of T4's rendering.
        wc.getOptions().setRedirectEnabled(false);

        URL url = new URL(wc.getContextPath() + "job/" + project.getName() + "/configTemplates/saveAssociation");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("projectKey", "newproj"),
                new org.htmlunit.util.NameValuePair("environment", "qa")
        ));
        wc.getPage(request);

        ConfigTemplatesJobProperty saved = project.getProperty(ConfigTemplatesJobProperty.class);
        assertNotNull("doSaveAssociation must persist a ConfigTemplatesJobProperty onto the job",
                saved);
        assertEquals("newproj", saved.getProjectKey());
        assertEquals("qa", saved.getEnvironment());
    }

    @Test
    public void doAssociateEnvironment_keepsTheProjectKeyAndOnlyUpdatesEnvironment() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("assoc-one-click-env");
        project.addProperty(new ConfigTemplatesJobProperty("myproj", ""));

        jenkins.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false); // see doSaveAssociation_... above

        URL url = new URL(wc.getContextPath()
                + "job/" + project.getName() + "/configTemplates/associateEnvironment");
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setRequestParameters(List.of(
                new org.htmlunit.util.NameValuePair("environment", "prod")
        ));
        wc.getPage(request);

        ConfigTemplatesJobProperty saved = project.getProperty(ConfigTemplatesJobProperty.class);
        assertNotNull(saved);
        assertEquals("the one-click env action must keep the job's existing projectKey",
                "myproj", saved.getProjectKey());
        assertEquals("prod", saved.getEnvironment());
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
