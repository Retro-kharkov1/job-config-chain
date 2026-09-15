package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.model.Result;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import io.github.retrokharkov1.configtemplatesync.ui.JobConfigTemplateProperty;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

/**
 * FR-90–FR-95: {@code setupConfigTemplate}'s build-scoped convenience state, its per-parameter
 * precedence against explicit call-site arguments (FR-93), the fail-loud missing-parameter path
 * (FR-94), and the {@code parallel {}} rejection (FR-95, OQ-11). Rewritten for the job-scoped
 * resolution model (tech-lead scoping decision, 2026-09-14): {@code setupConfigTemplate} has no
 * {@code projectKey}/{@code environment} parameter — only {@code file}, {@code useBase}, {@code
 * configKey}, {@code version}, {@code redeployFromRun}.
 */
public class SetupConfigTemplateStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private void seedCommon(String configKey, String commonJson) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(configKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v = common.addVersion(commonJson, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
    }

    /** Seeds the calling Job's own local config directly with {@code contentJson} (no base chain). */
    private void seedJobOwnConfig(WorkflowJob job, String contentJson) throws Exception {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion(contentJson, "seed", "test", 1L, Collections.emptyList(), null);
        property.activate(v);
        job.addProperty(property);
    }

    @Test
    public void zeroArgumentCallReadsStoredSetupState() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-zero-arg");
        seedJobOwnConfig(job, "{\"a\":1}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  setupConfigTemplate(file: 'app.json')\n"
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
        // FR-93: a call that explicitly supplies only `file` still picks up the stored
        // useBase/configKey unchanged — proven by seeding the calling Job's own local config with a
        // DIFFERENT (wrong) value that must never be used, since useBase+configKey redirect resolution
        // to the named global COMMON Config Set regardless of what file is explicitly supplied.
        seedCommon("setup2", "{\"a\":\"fromSetupKey\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-per-param-precedence");
        seedJobOwnConfig(job, "{\"a\":\"fromJobsOwnConfig-shouldNeverBeUsed\"}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  setupConfigTemplate(useBase: true, configKey: 'setup2', file: 'wrong.json')\n"
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
        jenkins.assertLogContains("file", run);
    }

    @Test
    public void secondSetupCallReplacesRatherThanDuplicatesStoredState() throws Exception {
        // FR-92: a second setupConfigTemplate() call in the same build must REPLACE the stored
        // state, not leave two ConfigTemplateSetupAction instances behind — proven here by pointing
        // the two calls at two DIFFERENT global COMMON Config Sets and asserting the SECOND one wins.
        seedCommon("setup3a", "{\"a\":1}");
        seedCommon("setup3b", "{\"a\":1,\"onlyInB\":2}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "setup-replace-not-duplicate");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  setupConfigTemplate(useBase: true, configKey: 'setup3a', file: 'app.json')\n"
                        + "  setupConfigTemplate(useBase: true, configKey: 'setup3b', file: 'app.json')\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{onlyInB}#'\n"
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
                        + "      setupConfigTemplate(file: 'a.json')\n"
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
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "explicit-call-safe-in-parallel");
        seedJobOwnConfig(job, "{\"a\":1}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  parallel(\n"
                        + "    branchA: {\n"
                        + "      configTemplateValidate(file: 'app.json')\n"
                        + "    }\n"
                        + "  )\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }
}
