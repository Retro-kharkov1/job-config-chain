package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the "Config Templates" discoverability link is contributed to every Job's sidebar via
 * {@link ConfigTemplatesJobActionFactory} ({@link jenkins.model.TransientActionFactory}), matches
 * the {@link ConfigTemplatesRootAction} label/icon character-for-character, and exposes the job
 * itself (not a cached property) so its association state is always read fresh.
 */
public class ConfigTemplatesJobActionTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void jobAction_isContributedToEveryJobAndMatchesTheManagementLinkEntry() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action");

        List<? extends Action> actions = project.getAllActions();
        ConfigTemplatesJobAction jobAction = actions.stream()
                .filter(ConfigTemplatesJobAction.class::isInstance)
                .map(ConfigTemplatesJobAction.class::cast)
                .findFirst()
                .orElse(null);

        assertTrue("every Job must be contributed a ConfigTemplatesJobAction sidebar link",
                jobAction != null);
        assertEquals("label must match the ManagementLink entry character-for-character",
                new ConfigTemplatesRootAction().getDisplayName(), jobAction.getDisplayName());
        assertEquals("icon must match the ManagementLink entry",
                new ConfigTemplatesRootAction().getIconFileName(), jobAction.getIconFileName());
        assertEquals("target must be a plain job-relative segment, exposed at "
                        + "/job/<name>/configTemplates just like /job/<name>/configure",
                "configTemplates", jobAction.getUrlName());
    }

    @Test
    public void jobPage_rendersTheConfigTemplatesSidebarLink() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action-2");

        HtmlPage page = jenkins.createWebClient().getPage(project);
        assertTrue("the job's page must render the 'Config Templates' sidebar link",
                page.asNormalizedText().contains("Config Templates"));
    }

    @Test
    public void jobAction_exposesTheJobItself_notACachedProperty() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("uitest-job-action-3");
        ConfigTemplatesJobAction jobAction = findJobAction(project);

        assertEquals("the action must expose the owning job so callers (and the Jelly view) can "
                        + "always read the CURRENT association, never a stale snapshot",
                project, jobAction.getJob());
        assertFalse("a freshly created job must have no association yet", jobAction.hasAssociation());

        project.addProperty(new ConfigTemplatesJobProperty("myproj", ""));

        assertTrue("getProperty()/hasAssociation() must reflect the property added AFTER this "
                        + "action instance was constructed — proves the property is read fresh, "
                        + "not cached at construction time",
                jobAction.hasAssociation());
        assertEquals("myproj", jobAction.getProjectKey());
    }

    private static ConfigTemplatesJobAction findJobAction(FreeStyleProject project) {
        List<? extends Action> actions = project.getAllActions();
        return actions.stream()
                .filter(ConfigTemplatesJobAction.class::isInstance)
                .map(ConfigTemplatesJobAction.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
