package io.jenkins.plugins.jobconfigchain.ui;

import hudson.ExtensionList;
import hudson.model.User;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hosting review: the Manage Jenkins pages (the global list and the common Config Set editor) are built on
 * {@code l:settings-subpage}. Pins the structure in both the classic and the new Manage Jenkins UI (the per-user
 * "New Manage Jenkins UI" experimental flag): one title bar that carries the page controls, the page content
 * inside the settings container in the new UI, and no second title bar.
 */
@WithJenkins
public class ManageSubpageLayoutTest {

    private static final String FLAG = "new-manage-jenkins.flag";

    private HtmlPage open(JenkinsRule j, boolean newUi, String path) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(
                jenkins.model.Jenkins.ADMINISTER).everywhere().to("admin"));
        User admin = User.getById("admin", true);
        admin.addProperty(new UserExperimentalFlagsProperty(Map.of(FLAG, Boolean.toString(newUi))));
        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        wc.getOptions().setJavaScriptEnabled(false);
        return wc.goTo(path);
    }

    private static String rootPath() {
        return "manage/" + ExtensionList.lookupSingleton(ConfigTemplatesRootAction.class).getUrlName() + "/";
    }

    private static String editorPath(String key) {
        return rootPath() + key + "/";
    }

    private static void seed(String key) throws Exception {
        ConfigSetRepository repo = new ConfigSetRepository();
        ConfigSet set = new ConfigSet(key, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        set.activate(set.addVersion("{\"a\":1}", "seed", "alice", 1L));
        repo.save(set);
    }

    private static int count(HtmlPage page, String xpath) {
        return page.getByXPath(xpath).size();
    }

    private static final String CONTAINER = "//*[contains(@class,'app-settings-container__inner')]";

    @Test
    public void classicUi_listPage_hasOneTitleBarAndNoSettingsContainer(JenkinsRule j) throws Exception {
        HtmlPage page = open(j, false, rootPath());
        assertEquals(0, count(page, CONTAINER), "the classic UI has no settings container");
        assertEquals(1, count(page, "//*[contains(concat(' ',normalize-space(@class),' '),' jenkins-app-bar ')]"), "exactly one title bar");
        assertEquals(1, count(page, "//*[@id='main-panel']//*[contains(@class,'ctsync-page')]"));
        assertTrue(page.getTitleText().contains("Manage Jenkins"), page.getTitleText());
    }

    @Test
    public void newUi_listPage_contentSitsInTheSettingsContainer_withOneTitleBar(JenkinsRule j) throws Exception {
        HtmlPage page = open(j, true, rootPath());
        assertEquals(1, count(page, CONTAINER + "//*[contains(@class,'ctsync-page')]"),
                "page content is inside the settings container");
        assertEquals(1, count(page, CONTAINER + "//*[contains(concat(' ',normalize-space(@class),' '),' jenkins-app-bar ')]"),
                "the default settings title bar is suppressed, only the page's own remains");
        assertTrue(count(page, "//*[contains(@class,'jenkins-side-nav__heading')]") > 0
                || count(page, "//*[@id='side-panel']") > 0, "Manage Jenkins side panel is shown");
        assertTrue(page.getTitleText().contains("Manage Jenkins"), page.getTitleText());
    }

    @Test
    public void editorPage_titleBarKeepsTheDeleteButton_inBothUis(JenkinsRule j) throws Exception {
        seed("layout-chain");
        for (boolean newUi : new boolean[] {false, true}) {
            HtmlPage page = open(j, newUi, editorPath("layout-chain"));
            DomElement delete = page.getElementById("deleteConfigSetBtn");
            assertNotNull(delete, "delete button, newUi=" + newUi);
            assertEquals(1, count(page, "//*[@id='deleteConfigSetBtn']/ancestor::*[contains(concat(' ',normalize-space(@class),' '),' jenkins-app-bar ')]"),
                    "the delete button stays in the title bar, newUi=" + newUi);
            assertEquals(newUi ? 1 : 0, count(page, CONTAINER + "//*[contains(@class,'ctsync-page')]"));
            assertTrue(page.getTitleText().contains("layout-chain"), page.getTitleText());
            assertFalse(page.asXml().contains("Manage Jenkins - Manage Jenkins"));
        }
    }

    @Test
    public void jobPage_keepsItsOwnJobLayout(JenkinsRule j) throws Exception {
        j.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "layout-job");
        HtmlPage page = open(j, true, "job/layout-job/configChains/");
        assertEquals(0, count(page, CONTAINER), "the job page is not a Manage Jenkins sub-page");
        assertNull(page.getElementById("settings-search-bar"));
    }
}
