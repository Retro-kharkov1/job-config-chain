package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves the "Config Templates" discoverability link is contributed to every Job's sidebar via
 * {@link ConfigTemplatesJobActionFactory} ({@link jenkins.model.TransientActionFactory}), lands
 * on the plugin's root list page, and matches the {@link ConfigTemplatesRootAction} label/icon
 * character-for-character.
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
}
