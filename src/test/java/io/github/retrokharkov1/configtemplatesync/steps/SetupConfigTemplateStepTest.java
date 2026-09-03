package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.model.Result;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * FR-90–FR-95: {@code setupConfigTemplate}'s build-scoped convenience state, its per-parameter
 * precedence against explicit call-site arguments (FR-93), the fail-loud missing-parameter path
 * (FR-94), and the {@code parallel {}} rejection (FR-95, OQ-11).
 */
public class SetupConfigTemplateStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private void seed(String projectKey, String environment, String commonJson, String envPatchJson)
            throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();

        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v = common.addVersion(commonJson, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);

        ConfigSet env = new ConfigSet(projectKey, ConfigSetRole.ENV, environment, "Env", ContentType.JSON);
        int ev = env.addVersion(envPatchJson, "seed", "test", 1L);
        env.activate(ev);
        repository.save(env);
    }

    @Test
    public void zeroArgumentCallReadsStoredSetupState() throws Exception {
        seed("setup1", "dev", "{\"a\":1}", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-zero-arg");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  setupConfigTemplate(projectKey: 'setup1', environment: 'dev', file: 'app.json')\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate()\n"
                        + "  configTemplateSubstitute()\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=1", run);
    }

    @Test
    public void explicitCallSiteParameterBeatsStoredSetupState_perParameter() throws Exception {
        // FR-93: a call that explicitly supplies only `file` still picks up stored
        // projectKey/environment unchanged.
        seed("setup2", "dev", "{\"a\":\"fromSetupKey\"}", "{}");
        seed("setup2-other", "dev", "{\"a\":\"fromExplicitFile-shouldNeverBeUsed\"}", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-per-param-precedence");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  setupConfigTemplate(projectKey: 'setup2', environment: 'dev', file: 'wrong.json')\n"
                        + "  writeFile file: 'right.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'right.json')\n"
                        + "  configTemplateSubstitute(file: 'right.json')\n"
                        + "  echo \"RESULT:${readFile('right.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=fromSetupKey", run);
    }

    @Test
    public void missingRequiredParameterFromBothSourcesFailsLoud() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-missing-params");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  configTemplateValidate()\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("[configTemplateSync] Missing required parameter(s)", run);
        jenkins.assertLogContains("projectKey", run);
        jenkins.assertLogContains("environment", run);
        jenkins.assertLogContains("file", run);
    }

    @Test
    public void secondSetupCallReplacesRatherThanDuplicatesStoredState() throws Exception {
        seed("setup3", "dev", "{\"a\":1}", "{}");
        seed("setup3", "qa", "{\"a\":1}", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-replace-not-duplicate");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  setupConfigTemplate(projectKey: 'setup3', environment: 'dev', file: 'app.json')\n"
                        + "  setupConfigTemplate(projectKey: 'setup3', environment: 'qa', file: 'app.json')\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate()\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void setupConfigTemplateForbiddenInsideParallelBranch() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-forbidden-in-parallel");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  parallel(\n"
                        + "    branchA: {\n"
                        + "      setupConfigTemplate(projectKey: 'x', environment: 'dev', file: 'a.json')\n"
                        + "    }\n"
                        + "  )\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains(
                "[configTemplateSync] setupConfigTemplate() is not supported inside a parallel {} branch", run);
    }

    @Test
    public void fullyExplicitCallsRemainSafeInsideParallelBranch() throws Exception {
        // FR-95: the restriction is scoped to setupConfigTemplate itself, not to the two
        // existing steps — a fully explicit call inside parallel {} must still succeed.
        seed("setup4", "dev", "{\"a\":1}", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "explicit-call-safe-in-parallel");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  parallel(\n"
                        + "    branchA: {\n"
                        + "      configTemplateValidate(projectKey: 'setup4', environment: 'dev', file: 'app.json')\n"
                        + "    }\n"
                        + "  )\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }
}
