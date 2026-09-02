package io.github.retrokharkov1.configtemplatesync.steps;

import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

public class ConfigTemplateValidateStepTest {

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

    @Test
    public void multiEntryChainIncludingCrossProjectReferenceResolvesAllBases() throws Exception {
        // FR-8/FR-51: a chain of 3 entries, one of which references a different project's common
        // Config Set entirely — not just projectKey's own.
        ConfigSetRepository repository = new ConfigSetRepository();

        ConfigSet own = new ConfigSet("proj4", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int ownV = own.addVersion("{\"a\":1}", "seed", "test", 1L);
        own.activate(ownV);
        repository.save(own);

        ConfigSet teamB = new ConfigSet("team-b-common", ConfigSetRole.COMMON, null, "Team B Common", ContentType.JSON);
        int bV = teamB.addVersion("{\"b\":2}", "seed", "test", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        ConfigSet teamC = new ConfigSet("team-c-common", ConfigSetRole.COMMON, null, "Team C Common", ContentType.JSON);
        int cV1 = teamC.addVersion("{\"c\":1}", "seed", "test", 1L);
        int cV2 = teamC.addVersion("{\"c\":2}", "seed2", "test", 2L);
        teamC.activate(cV2);
        repository.save(teamC);

        ConfigSet env = new ConfigSet("proj4", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int ev = env.addVersion("{}", "seed", "test", 1L, Arrays.asList(
                BaseConfigReference.active("proj4"),
                BaseConfigReference.active("team-b-common"),
                BaseConfigReference.pinned("team-c-common", cV1)));
        env.activate(ev);
        repository.save(env);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-multi-chain");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}# z=#{c}#'\n"
                        + "  configTemplateValidate(projectKey: 'proj4', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void missingChainReferenceFailsBuildNamingTheProject() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet("proj5", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int ev = env.addVersion("{}", "seed", "test", 1L,
                Collections.singletonList(BaseConfigReference.active("no-such-common-project")));
        env.activate(ev);
        repository.save(env);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing-chain-ref");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(projectKey: 'proj5', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("no-such-common-project", run);
    }
}
