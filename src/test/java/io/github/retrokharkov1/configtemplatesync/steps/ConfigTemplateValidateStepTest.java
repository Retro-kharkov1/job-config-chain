package io.github.retrokharkov1.configtemplatesync.steps;

import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertTrue;

public class ConfigTemplateValidateStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private void seed(String projectKey, String environment, String commonJson, String envPatchJson)
            throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();

        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common");
        int v = common.addVersion(commonJson, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);

        ConfigSet env = new ConfigSet(projectKey, ConfigSetRole.ENV, environment, "Env");
        int ev = env.addVersion(envPatchJson, "seed", "test", 1L);
        env.activate(ev);
        repository.save(env);
    }

    @Test
    public void happyPath_noMissingNoOrphaned_buildSucceeds() throws Exception {
        seed("proj1", "dev", "{\"a\":1,\"b\":2}", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-happy");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateValidate(projectKey: 'proj1', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void missingKey_failsBuildNamingTheToken() throws Exception {
        seed("proj2", "dev", "{\"a\":1}", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{doesNotExist}#'\n"
                        + "  configTemplateValidate(projectKey: 'proj2', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("doesNotExist", run);
    }

    @Test
    public void orphanedKey_warnsButDoesNotFailBuild() throws Exception {
        seed("proj3", "dev", "{\"a\":1,\"unused\":2}", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-orphan");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(projectKey: 'proj3', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("unused", run);
        assertTrue(true);
    }
}
