package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.model.Result;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigDeploymentBindingRepository;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ConfigTemplateSubstituteStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private ConfigSet seedCommon(String projectKey, String contentJson) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common");
        int v = common.addVersion(contentJson, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
        return common;
    }

    private ConfigSet seedEnv(String projectKey, String environment, String patchJson) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet(projectKey, ConfigSetRole.ENV, environment, "Env");
        int v = env.addVersion(patchJson, "seed", "test", 1L);
        env.activate(v);
        repository.save(env);
        return env;
    }

    @Test
    public void happyPath_substitutesValuesIntoTargetFile() throws Exception {
        seedCommon("subproj1", "{\"Feature\":{\"Name\":\"widgets\"}}");
        seedEnv("subproj1", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-happy");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'name=#{Feature.Name}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj1', environment: 'dev', file: 'app.json')\n"
                        + "  def content = readFile('app.json')\n"
                        + "  echo \"RESULT:${content}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:name=widgets", run);
    }

    @Test
    public void remainingTokenAfterSubstitutionFailsBuild() throws Exception {
        seedCommon("subproj2", "{\"a\":1}");
        seedEnv("subproj2", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-remaining-token");
        job.setDefinition(new CpsFlowDefinition(
                // this file has a token the effective config also has no key for -> defensive
                // re-check (OQ-7) fails it before substitution even runs.
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{missing}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj2', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("missing", run);
    }

    @Test
    public void buildVersionOptIn_recordsBindingOnSuccessfulSubstitution() throws Exception {
        seedCommon("subproj3", "{\"a\":\"one\"}");
        seedEnv("subproj3", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-pin-record");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj3', environment: 'dev', file: 'app.json', buildVersion: '1.0.0')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        var binding = bindingRepository.find("subproj3", "dev", "1.0.0");
        assertNotNull(binding);
        assertEquals(1, binding.getCommonVersionNumber());
    }

    @Test
    public void buildVersionWithNoBinding_fallsBackToActiveAndLogsFallback() throws Exception {
        seedCommon("subproj4", "{\"a\":\"one\"}");
        seedEnv("subproj4", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-pin-fallback");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj4', environment: 'dev', file: 'app.json', buildVersion: '9.9.9')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        // FR-26: must surface, in build output, that it fell back to active versions.
        jenkins.assertLogContains("falling back to the currently active versions", run);
    }

    @Test
    public void noBuildVersionGiven_usesActiveWithNoPinningBehavior() throws Exception {
        seedCommon("subproj5", "{\"a\":\"one\"}");
        seedEnv("subproj5", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-no-pin");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj5', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        assertEquals(null, bindingRepository.find("subproj5", "dev", ""));
    }

    @Test
    public void pinnedBuildVersionReusesRecordedVersionsNotCurrentActive() throws Exception {
        ConfigSet common = seedCommon("subproj6", "{\"a\":\"v1-value\"}");
        seedEnv("subproj6", "dev", "{}");

        WorkflowJob firstDeploy = jenkins.createProject(WorkflowJob.class, "substitute-pin-first");
        firstDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj6', environment: 'dev', file: 'app.json', buildVersion: '1.0.0')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun first = jenkins.assertBuildStatus(Result.SUCCESS, firstDeploy.scheduleBuild2(0));
        jenkins.assertLogContains("FIRST:x=v1-value", first);

        // now advance common's active version, but a redeploy of build 1.0.0 must keep using the
        // pinned (older) common version, not the new "currently active" one (UF-6).
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloadedCommon = repository.findCommon("subproj6");
        int v2 = reloadedCommon.addVersion("{\"a\":\"v2-value\"}", "bump", "test", 2L);
        reloadedCommon.activate(v2);
        repository.save(reloadedCommon);

        WorkflowJob rollbackRedeploy = jenkins.createProject(WorkflowJob.class, "substitute-pin-redeploy");
        rollbackRedeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj6', environment: 'dev', file: 'app.json', buildVersion: '1.0.0')\n"
                        + "  echo \"REDEPLOY:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun redeploy = jenkins.assertBuildStatus(Result.SUCCESS, rollbackRedeploy.scheduleBuild2(0));
        jenkins.assertLogContains("REDEPLOY:x=v1-value", redeploy);
    }
}
