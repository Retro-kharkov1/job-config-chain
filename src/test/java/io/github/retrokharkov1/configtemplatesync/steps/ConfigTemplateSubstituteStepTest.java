package io.github.retrokharkov1.configtemplatesync.steps;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Result;
import hudson.util.Secret;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.model.SecretPlaceholder;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigDeploymentBindingRepository;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConfigTemplateSubstituteStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private ConfigSet seedCommon(String projectKey, String contentJson) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v = common.addVersion(contentJson, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
        return common;
    }

    private ConfigSet seedEnv(String projectKey, String environment, String patchJson) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet(projectKey, ConfigSetRole.ENV, environment, "Env", ContentType.JSON);
        int v = env.addVersion(patchJson, "seed", "test", 1L);
        env.activate(v);
        repository.save(env);
        return env;
    }

    private ConfigSet seedEnvWithChain(String projectKey, String environment, String patchJson,
                                        java.util.List<BaseConfigReference> chain) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet env = new ConfigSet(projectKey, ConfigSetRole.ENV, environment, "Env", ContentType.JSON);
        int v = env.addVersion(patchJson, "seed", "test", 1L, chain);
        env.activate(v);
        repository.save(env);
        return env;
    }

    /** Seeds a common Config Set whose content has a secret-manifest-declared placeholder leaf. */
    private ConfigSet seedCommonWithSecret(String projectKey, String dottedPath, String credentialId,
                                            String contentJsonWithPlaceholder) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.putSecretManifestEntry(dottedPath, credentialId);
        int v = common.addVersion(contentJsonWithPlaceholder, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
        return common;
    }

    private void seedRealStringCredential(String id, String secretValue) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, id, "test credential seeded for substitute step test",
                Secret.fromString(secretValue)));
        SystemCredentialsProvider.getInstance().save();
    }

    @Test
    public void secretManifestPath_resolvesRealCredentialValue_notEnvVarOrPlaceholder() throws Exception {
        // FR-13/FR-21 fix: the real value must come from the Jenkins credential store, exclusively,
        // even though NO env var named "Database.Password" is exported anywhere in this pipeline.
        seedRealStringCredential("subproj7-db-pass", "S3cr3tDbPass!");
        seedCommonWithSecret("subproj7", "Database.Password", "subproj7-db-pass",
                "{\"Database\":{\"Password\":\"" + SecretPlaceholder.VALUE + "\"}}");
        seedEnv("subproj7", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-real-secret");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj7', environment: 'dev', file: 'app.json')\n"
                        + "  def content = readFile('app.json')\n"
                        + "  echo \"RESULT:${content}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:password=S3cr3tDbPass!", run);
        assertFalse("must never fall back to the raw placeholder text",
                jenkins.getLog(run).contains("password=" + SecretPlaceholder.VALUE));
    }

    @Test
    public void secretManifestPath_missingCredential_failsBuildLoudlyNamingIt() throws Exception {
        // NFR-7: a declared-but-nonexistent/inaccessible credential must fail the build, never
        // silently substitute the placeholder or an empty string.
        seedCommonWithSecret("subproj8", "Database.Password", "does-not-exist-credential-id",
                "{\"Database\":{\"Password\":\"" + SecretPlaceholder.VALUE + "\"}}");
        seedEnv("subproj8", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-missing-credential");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj8', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("does-not-exist-credential-id", run);
        jenkins.assertLogContains("Database.Password", run);
        assertTrue("must not report success while silently substituting a placeholder",
                run.getResult() == Result.FAILURE);
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
        // FR-26: must surface, in build output, that it fell back to the currently active/pinned chain.
        jenkins.assertLogContains("falling back to the currently active/pinned base chain", run);
        jenkins.assertLogContains("resolved to v1", run);
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

    @Test
    public void multiEntryCrossProjectChainSubstitutesFromAllBases() throws Exception {
        seedCommon("subproj9", "{\"a\":\"own\"}");
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet teamB = new ConfigSet("team-b-common", ConfigSetRole.COMMON, null, "Team B Common", ContentType.JSON);
        int bV = teamB.addVersion("{\"b\":\"fromB\"}", "seed", "test", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        seedEnvWithChain("subproj9", "dev", "{}", Arrays.asList(
                BaseConfigReference.active("subproj9"),
                BaseConfigReference.active("team-b-common")));

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-multi-chain");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj9', environment: 'dev', file: 'app.json')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=own y=fromB", run);
    }

    @Test
    public void fr54_pinnedBuildVersionSubstitutionIsByteIdenticalAcrossRepeatRuns() throws Exception {
        // FR-54: substituting the same buildVersion twice, even after the referenced base's active
        // version changes in between, must produce byte-identical output both times.
        seedCommon("subproj10", "{\"a\":\"v1-value\"}");
        seedEnv("subproj10", "dev", "{}");

        WorkflowJob firstDeploy = jenkins.createProject(WorkflowJob.class, "fr54-first");
        firstDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj10', environment: 'dev', file: 'app.json', buildVersion: 'b1')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun first = jenkins.assertBuildStatus(Result.SUCCESS, firstDeploy.scheduleBuild2(0));
        jenkins.assertLogContains("FIRST:x=v1-value", first);
        String firstOutput = jenkins.getLog(first).lines()
                .filter(l -> l.contains("FIRST:"))
                .findFirst().orElseThrow();

        // Activate a different version of the referenced base common Config Set in between.
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloadedCommon = repository.findCommon("subproj10");
        int v2 = reloadedCommon.addVersion("{\"a\":\"v2-value\"}", "bump", "test", 2L);
        reloadedCommon.activate(v2);
        repository.save(reloadedCommon);

        WorkflowJob secondDeploy = jenkins.createProject(WorkflowJob.class, "fr54-second");
        secondDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj10', environment: 'dev', file: 'app.json', buildVersion: 'b1')\n"
                        + "  echo \"SECOND:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun second = jenkins.assertBuildStatus(Result.SUCCESS, secondDeploy.scheduleBuild2(0));
        String secondOutput = jenkins.getLog(second).lines()
                .filter(l -> l.contains("SECOND:"))
                .findFirst().orElseThrow();

        assertEquals("re-substituting the same pinned buildVersion must be byte-identical",
                firstOutput.replace("FIRST:", ""), secondOutput.replace("SECOND:", ""));
        jenkins.assertLogContains("SECOND:x=v1-value", second);
    }
}
