package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.FreeStyleProject;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextInput;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Reproduction test for the confirmed live bug (docs/business/requirements.md FR-78): saving the
 * "Config Templates Association" section on {@code /job/<name>/configure} through a REAL HTML
 * form round-trip (exactly what a browser does) does not persist a {@link
 * ConfigTemplatesJobProperty} onto the job at all — {@code config.xml} is left with
 * {@code <properties/>} completely empty.
 *
 * <p>This is deliberately different from every other existing test in this package (see {@link
 * ConfigTemplatesJobActionAssociationTest}, {@link ConfigTemplatesJobActionTest}): those only ever
 * construct {@code ConfigTemplatesJobProperty} directly in Java (via {@code new
 * ConfigTemplatesJobProperty(...)} + {@code project.addProperty(...)}) or read a property that was
 * injected that way — none of them ever go through the actual {@code /configure} HTML form and
 * Jenkins' structured-form-submission machinery, which is exactly where the real defect lives (see
 * the root-cause note in {@code docs/business/requirements.md}).</p>
 *
 * <p><b>Expected to currently FAIL</b> against the production code as-is: {@code
 * job.getProperty(ConfigTemplatesJobProperty.class)} comes back {@code null} after the round-trip,
 * because {@code ConfigTemplatesJobProperty/config.jelly} wraps its two {@code <f:entry>} fields in
 * an {@code <f:section>} that is itself nested inside the descriptor's own row-grouping scope
 * (contributed by Jenkins core's {@code <f:descriptorList field="properties" ... forceRowSet="true"/>}
 * in {@code Job/configure.jelly}) — an unsupported double-nesting of {@code f:rowSet} scopes (see
 * {@code lib/form/section.jelly}, which itself renders {@code <f:rowSet name="${attrs.name}">} with
 * no {@code name} supplied here) that breaks Jenkins' structured-form-submission JS so the submitted
 * form JSON never contains a JSON object keyed by this descriptor's JSON-safe class name at all;
 * {@code DescribableList.rebuild()}'s {@code if (o != null)} guard then skips constructing the
 * property entirely, so it is never added to the job's persisted property list. Do NOT fix
 * {@code config.jelly} or {@code ConfigTemplatesJobProperty} as part of resolving this test — that is
 * the next agent's job; this test exists purely to characterize and lock in the current (broken)
 * behavior as a red test for that fix to turn green.</p>
 */
public class ConfigTemplatesJobPropertyFormPersistenceTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void savingTheAssociationSectionOnTheRealConfigureForm_persistsTheJobProperty() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("form-persistence-e2e");

        JenkinsRule.WebClient wc = jenkins.createWebClient();

        HtmlPage configurePage = wc.getPage(project, "configure");
        HtmlForm form = configurePage.getFormByName("config");

        HtmlTextInput projectKeyInput = form.getInputByName("_.projectKey");
        HtmlTextInput environmentInput = form.getInputByName("_.environment");
        projectKeyInput.setText("test-app");
        environmentInput.setText("dev");

        jenkins.submit(form);

        FreeStyleProject reloaded =
                jenkins.jenkins.getItemByFullName(project.getFullName(), FreeStyleProject.class);
        assertNotNull("job must still exist after the config save", reloaded);

        ConfigTemplatesJobProperty property = reloaded.getProperty(ConfigTemplatesJobProperty.class);
        assertNotNull("ConfigTemplatesJobProperty must be persisted after a real /configure form "
                        + "submission with projectKey/environment filled in — config.xml must NOT be "
                        + "left with an empty <properties/> (the confirmed live bug)",
                property);
        assertEquals("test-app", property.getProjectKey());
        assertEquals("dev", property.getEnvironment());
    }
}
