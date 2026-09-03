package io.github.retrokharkov1.configtemplatesync.steps;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Result;
import hudson.util.Secret;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void fr97_defaultOn_recordsBindingUnderOwnRunIdentityWithNoParameter() throws Exception {
        // FR-96/FR-97: every successful real substitution automatically derives its own
        // Run-identity key and unconditionally creates/updates a binding for it — no parameter
        // needed at all.
        seedCommon("subproj3", "{\"a\":\"one\"}");
        seedEnv("subproj3", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-default-on-binding");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj3', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        ConfigDeploymentBinding binding =
                bindingRepository.find("subproj3", "dev", run.getExternalizableId());
        assertNotNull("a binding must exist under this run's own externalizableId with zero parameters",
                binding);
        assertEquals(1, binding.getCommonVersionNumber());
    }

    @Test
    public void fr98_explicitVersionSuppressesBindingLookupAndWrite() throws Exception {
        seedCommon("subproj4", "{\"a\":\"v1-value\"}");
        seedEnv("subproj4", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-version-suppresses-binding");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj4', environment: 'dev', file: 'app.json', version: 1)\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        assertNull("an explicit 'version' must suppress the binding write entirely (FR-98)",
                bindingRepository.find("subproj4", "dev", run.getExternalizableId()));
    }

    @Test
    public void fr103_redeployFromRunWithNoBinding_fallsBackToActiveAndLogsFallback() throws Exception {
        seedCommon("subproj5", "{\"a\":\"one\"}");
        seedEnv("subproj5", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-redeploy-fallback");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj5', environment: 'dev', file: 'app.json', redeployFromRun: '999')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        // FR-103: must surface, in build output, that it fell back to the currently active/pinned
        // chain, naming the unresolved redeployFromRun value explicitly.
        jenkins.assertLogContains("redeployFromRun '999'", run);
        jenkins.assertLogContains("falling back to the currently active/pinned base chain", run);
    }

    @Test
    public void fr27_firstSubstitutionUnderOwnIdentity_usesActiveWithNoWarning() throws Exception {
        seedCommon("subproj6a", "{\"a\":\"one\"}");
        seedEnv("subproj6a", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-no-pin");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj6a', environment: 'dev', file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertFalse("a Run's very first substitution under its own identity must not be a warned-about fallback",
                jenkins.getLog(run).contains("falling back"));
    }

    @Test
    public void fr25_sameRunRepeatCallFindsItsOwnPriorBindingAndReplays() throws Exception {
        // FR-25's "(a) the current Run's own ... identity" case — a Pipeline restart-from-stage
        // analogue, simulated here as two substitute calls within the same build.
        ConfigSet common = seedCommon("subproj6", "{\"a\":\"v1-value\"}");
        seedEnv("subproj6", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-same-run-self-lookup");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj6', environment: 'dev', file: 'app.json')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj6', environment: 'dev', file: 'app.json')\n"
                        + "  echo \"SECOND:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("FIRST:x=v1-value", run);
        jenkins.assertLogContains("SECOND:x=v1-value", run);
        jenkins.assertLogContains("This run's own prior binding is pinned", run);
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
    public void fr54_redeployFromRunReplayIsByteIdenticalAcrossRuns_evenAfterBaseChanges() throws Exception {
        // FR-54's amended acceptance criterion: substitute under Run A's own key, then substitute
        // under a DIFFERENT Run B supplying redeployFromRun targeting Run A, and assert Run B's
        // merged output is byte-identical to Run A's original output, even after Run A's base
        // Config Set's active version has since changed. This is the test that actually proves
        // UF-6's cross-Run rollback case works.
        seedCommon("subproj10", "{\"a\":\"v1-value\"}");
        seedEnv("subproj10", "dev", "{}");

        WorkflowJob firstDeploy = jenkins.createProject(WorkflowJob.class, "fr54-first");
        firstDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj10', environment: 'dev', file: 'app.json')\n"
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

        int firstBuildNumber = first.getNumber();
        WorkflowJob secondDeploy = jenkins.createProject(WorkflowJob.class, "fr54-second");
        secondDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj10', environment: 'dev', file: 'app.json', "
                        + "redeployFromRun: '" + first.getParent().getFullName() + "#" + firstBuildNumber + "')\n"
                        + "  echo \"SECOND:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun second = jenkins.assertBuildStatus(Result.SUCCESS, secondDeploy.scheduleBuild2(0));
        String secondOutput = jenkins.getLog(second).lines()
                .filter(l -> l.contains("SECOND:"))
                .findFirst().orElseThrow();

        assertEquals("redeployFromRun must replay Run A's frozen chain byte-identically in Run B",
                firstOutput.replace("FIRST:", ""), secondOutput.replace("SECOND:", ""));
        jenkins.assertLogContains("SECOND:x=v1-value", second);

        // FR-102: Run B's own successful substitution must ALSO forward-chain a fresh binding
        // keyed to ITS OWN identity, so a future redeploy could in turn target Run B.
        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        ConfigDeploymentBinding ownBindingForSecond =
                bindingRepository.find("subproj10", "dev", second.getExternalizableId());
        assertNotNull("FR-102: redeployFromRun must still forward-chain a binding under the CURRENT run",
                ownBindingForSecond);
        assertEquals(1, ownBindingForSecond.getCommonVersionNumber());
    }

    @Test
    public void useBase_bypassesEnvEntirely_resolvesAgainstCommonOnly() throws Exception {
        // FR-81/FR-85: useBase=true resolves directly against the COMMON Config Set, bypassing
        // the env Config Set's override content and base-chain machinery entirely.
        seedCommon("subproj11", "{\"a\":\"commonValue\"}");
        seedEnv("subproj11", "dev", "{\"a\":\"envOverrideValueMustNeverAppear\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj11', environment: 'dev', file: 'app.json', useBase: true)\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=commonValue", run);
    }

    @Test
    public void useBaseWithVersion_pinsCommonConfigSetToThatVersion() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj12", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.addVersion("{\"a\":\"v1\"}", "seed", "test", 1L);
        int v2 = common.addVersion("{\"a\":\"v2\"}", "second", "test", 2L);
        common.activate(v2);
        repository.save(common);
        seedEnv("subproj12", "dev", "{}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase-version");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj12', environment: 'dev', file: 'app.json', useBase: true, version: 1)\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=v1", run);
    }

    @Test
    public void envVersionPin_pinsEnvConfigSetToThatVersionIncludingItsOwnBaseChain() throws Exception {
        // FR-80: pinning the env version pins its entire frozen baseChain for free.
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj13", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int cv = common.addVersion("{\"a\":\"common-value\"}", "seed", "test", 1L);
        common.activate(cv);
        repository.save(common);

        ConfigSet env = new ConfigSet("subproj13", ConfigSetRole.ENV, "dev", "Env", ContentType.JSON);
        int ev1 = env.addVersion("{\"a\":\"env-v1\"}", "seed", "test", 1L);
        int ev2 = env.addVersion("{\"a\":\"env-v2\"}", "second", "test", 2L);
        env.activate(ev2);
        repository.save(env);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-env-version-pin");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(projectKey: 'subproj13', environment: 'dev', file: 'app.json', version: "
                        + ev1 + ")\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=env-v1", run);
    }
}
