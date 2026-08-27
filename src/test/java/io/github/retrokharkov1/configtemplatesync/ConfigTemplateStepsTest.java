package io.github.retrokharkov1.configtemplatesync;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

/**
 * End-to-end tests of {@code configTemplateValidate} / {@code configTemplateSubstitute} running
 * inside a real Pipeline job against a fixture workspace file, driven through a live (test)
 * Jenkins controller via {@link JenkinsRule}.
 */
public class ConfigTemplateStepsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private void seedConfigSets() {
        ConfigSetRepository repo = ConfigSetRepository.onMaster();
        repo.save("it-common", "{\"AppSettings\":{\"TimeoutSeconds\":\"30\"}}", null, "seed", "alice", true);
        repo.save("it-dev", "{\"AppSettings\":{\"FeatureX\":\"on\"}}",
                Collections.singletonMap("Db.Password", "dummy-cred-id"), "seed", "alice", true);
    }

    @Test
    public void substituteHappyPath() throws Exception {
        seedConfigSets();
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "substitute-happy-path");
        job.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  writeFile file: 'appsettings.Production.json', text: '{\"T\":\"#{AppSettings.TimeoutSeconds}#\",\"F\":\"#{AppSettings.FeatureX}#\",\"P\":\"#{Db.Password}#\"}'",
                "  withEnv(['Db.Password=super-secret-value']) {",
                "    configTemplateSubstitute(commonConfigSet: 'it-common', envConfigSet: 'it-dev', file: 'appsettings.Production.json')",
                "  }",
                "  def result = readFile('appsettings.Production.json')",
                "  echo 'RESULT=' + result",
                "}"), true));

        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0).get());
        String log = j.getLog(run);
        assertThat(log, containsString(
                "RESULT={\"T\":\"30\",\"F\":\"on\",\"P\":\"super-secret-value\"}"));
    }

    @Test
    public void validateFailsBuildOnMissingToken() throws Exception {
        seedConfigSets();
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "validate-missing-token");
        job.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  writeFile file: 'appsettings.Production.json', text: '{\"X\":\"#{AppSettings.OrphanedOnPurpose}#\"}'",
                "  configTemplateValidate(commonConfigSet: 'it-common', envConfigSet: 'it-dev', file: 'appsettings.Production.json')",
                "}"), true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0).get());
        String log = j.getLog(run);
        assertThat(log, containsString("AppSettings.OrphanedOnPurpose"));
    }

    @Test
    public void validateWarnsOnOrphanStoreKeyButDoesNotFail() throws Exception {
        seedConfigSets();
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "validate-orphan-warn");
        job.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                // File only references TimeoutSeconds; FeatureX + Db.Password are store-only -> orphans.
                "  writeFile file: 'appsettings.Production.json', text: '{\"T\":\"#{AppSettings.TimeoutSeconds}#\"}'",
                "  configTemplateValidate(commonConfigSet: 'it-common', envConfigSet: 'it-dev', file: 'appsettings.Production.json')",
                "}"), true));

        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0).get());
        String log = j.getLog(run);
        assertThat(log, containsString("[WARN] Orphan store keys"));
        assertThat(log, containsString("AppSettings.FeatureX"));
    }

    @Test
    public void substituteFailsBuildWhenTokensRemainUnsubstituted() throws Exception {
        seedConfigSets();
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "substitute-leftover-token");
        job.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  writeFile file: 'appsettings.Production.json', text: '{\"X\":\"#{Not.In.AnyStore}#\"}'",
                "  configTemplateSubstitute(commonConfigSet: 'it-common', envConfigSet: 'it-dev', file: 'appsettings.Production.json')",
                "}"), true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0).get());
        String log = j.getLog(run);
        assertThat(log, containsString("Unsubstituted tokens remain"));
        assertThat(log, containsString("Not.In.AnyStore"));
    }
}
