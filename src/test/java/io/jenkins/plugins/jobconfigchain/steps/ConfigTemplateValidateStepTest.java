package io.jenkins.plugins.jobconfigchain.steps;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * {@code configTemplateValidate} end-to-end pipeline tests against the job-scoped resolution
 * matrix (tech-lead scoping decision, 2026-09-14, pipeline-steps.md "Pipeline call resolution —
 * the final parameter model"). See {@link ConfigTemplateSubstituteStepTest}'s class javadoc for
 * the shared model description; matrix rows 4/5, the {@code configKey}-without-{@code useBase}
 * fail-loud path, and the cross-Job isolation guarantee already have dedicated coverage there
 * (both steps share the identical {@code StepSupport.resolveJobScoped}/{@code mergeWithSetupState}
 * resolution code — see pipeline-steps.md §4, "confirmed untouched" for why validate needs no
 * separate binding-related treatment).
 */
public class ConfigTemplateValidateStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private void seedCommon(String projectKey, String commonJson) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v = common.addVersion(commonJson, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
    }

    private void seedCommon(String projectKey, String commonContent, ContentType type) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", type);
        int v = common.addVersion(commonContent, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
    }

    /**
     * Attaches a {@link JobConfigTemplateProperty} to {@code job} — the job-scoped replacement for
     * the old {@code seed(projectKey, environment, commonJson, envPatchJson)} helper's env half.
     * Unlike an env Config Set, a job version has NO FR-52 self-reference default
     * (job-scoped-config.md), so callers must pass the base chain explicitly.
     */
    private void attachJobProperty(WorkflowJob job, String overlayJson, List<BaseConfigReference> baseChain,
                                    String contentTypeOrNull) throws Exception {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion(overlayJson, "seed", "test", 1L, baseChain, contentTypeOrNull);
        property.activate(v);
        job.addProperty(property);
    }

    /** Mirrors the old {@code seed(projectKey, environment, commonJson, envPatchJson)} shape exactly. */
    private void seedCommonAndJobDefaultChain(WorkflowJob job, String projectKey, String commonJson,
                                               String overlayJson) throws Exception {
        seedCommon(projectKey, commonJson);
        attachJobProperty(job, overlayJson, Collections.singletonList(BaseConfigReference.active(projectKey)), null);
    }

    private void seedCommonAndJobDefaultChain(WorkflowJob job, String projectKey, String commonContent,
                                               String overlayContent, ContentType type) throws Exception {
        seedCommon(projectKey, commonContent, type);
        attachJobProperty(job, overlayContent, Collections.singletonList(BaseConfigReference.active(projectKey)),
                type.name());
    }

    @Test
    public void happyPath_noMissingNoOrphaned_buildSucceeds() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-happy");
        seedCommonAndJobDefaultChain(job, "proj1", "{\"a\":1,\"b\":2}", "{}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void happyPath_noMissingNoOrphaned_buildSucceedsXml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-happy-xml");
        seedCommonAndJobDefaultChain(job, "proj12", "<root><a>1</a><b>2</b></root>",
                "<root><placeholder>x</placeholder></root>", ContentType.XML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void happyPath_noMissingNoOrphaned_buildSucceedsYaml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-happy-yaml");
        seedCommonAndJobDefaultChain(job, "proj13", "a: 1\nb: 2\n", "placeholder: x\n", ContentType.YAML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void missingKey_failsBuildNamingTheToken() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing");
        seedCommonAndJobDefaultChain(job, "proj2", "{\"a\":1}", "{}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{doesNotExist}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("doesNotExist", run);
    }

    @Test
    public void missingKey_failsBuildNamingTheTokenXml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing-xml");
        seedCommonAndJobDefaultChain(job, "proj14", "<root><a>1</a></root>",
                "<root><placeholder>x</placeholder></root>", ContentType.XML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{doesNotExist}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("doesNotExist", run);
    }

    @Test
    public void missingKey_failsBuildNamingTheTokenYaml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing-yaml");
        seedCommonAndJobDefaultChain(job, "proj15", "a: 1\n", "placeholder: x\n", ContentType.YAML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{doesNotExist}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("doesNotExist", run);
    }

    @Test
    public void nestedPathLeafMissingWhileParentPathExists_notTreatedAsPrefixMatch() throws Exception {
        // Database.Host and Database.Name both exist under the 'Database' prefix, but the token
        // requests Database.Password specifically. The existence of sibling dotted paths sharing the
        // same parent prefix must NOT cause a false-pass — only an exact dotted-path match counts.
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-nested-leaf-missing");
        seedCommonAndJobDefaultChain(job, "proj9", "{\"Database\":{\"Host\":\"h\",\"Name\":\"n\"}}", "{}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'host=#{Database.Host}# pass=#{Database.Password}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("Database.Password", run);
    }

    @Test
    public void nestedPathLeafMissingWhileParentPathExists_notTreatedAsPrefixMatchXml() throws Exception {
        // XML sibling of the JSON test above: same nested-path exact-match semantics, this time
        // with the Config Set's own stored content in XML.
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-nested-leaf-missing-xml");
        seedCommonAndJobDefaultChain(job, "proj10", "<root><Database><Host>h</Host><Name>n</Name></Database></root>",
                "<root><placeholder>x</placeholder></root>", ContentType.XML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'host=#{Database.Host}# pass=#{Database.Password}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("Database.Password", run);
    }

    @Test
    public void nestedPathLeafMissingWhileParentPathExists_notTreatedAsPrefixMatchYaml() throws Exception {
        // YAML sibling of the JSON test above: same nested-path exact-match semantics, this time
        // with the Config Set's own stored content in YAML.
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-nested-leaf-missing-yaml");
        seedCommonAndJobDefaultChain(job, "proj11", "Database:\n  Host: h\n  Name: n\n",
                "placeholder: x\n", ContentType.YAML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'host=#{Database.Host}# pass=#{Database.Password}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("Database.Password", run);
    }

    @Test
    public void orphanedKey_warnsButDoesNotFailBuild() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-orphan");
        seedCommonAndJobDefaultChain(job, "proj3", "{\"a\":1,\"unused\":2}", "{}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("unused", run);
        assertTrue(true);
    }

    @Test
    public void orphanedKey_warnsButDoesNotFailBuildXml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-orphan-xml");
        seedCommonAndJobDefaultChain(job, "proj16", "<root><a>1</a><unused>2</unused></root>",
                "<root><placeholder>x</placeholder></root>", ContentType.XML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("unused", run);
        assertTrue(true);
    }

    @Test
    public void orphanedKey_warnsButDoesNotFailBuildYaml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-orphan-yaml");
        seedCommonAndJobDefaultChain(job, "proj17", "a: 1\nunused: 2\n", "placeholder: x\n", ContentType.YAML);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("unused", run);
        assertTrue(true);
    }

    @Test
    public void multiEntryChainIncludingCrossProjectReferenceResolvesAllBases() throws Exception {
        // FR-8/FR-51: a chain of 3 entries, one of which references a different project's common
        // Config Set entirely — not just this job's own paired projectKey.
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

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-multi-chain");
        attachJobProperty(job, "{}", Arrays.asList(
                BaseConfigReference.active("proj4"),
                BaseConfigReference.active("team-b-common"),
                BaseConfigReference.pinned("team-c-common", cV1)), null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}# z=#{c}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void multiEntryChainIncludingCrossProjectReferenceResolvesAllBasesXml() throws Exception {
        // XML sibling: same 3-entry chain shape, all Config Sets in the chain kept consistently XML.
        ConfigSetRepository repository = new ConfigSetRepository();

        ConfigSet own = new ConfigSet("proj18", ConfigSetRole.COMMON, null, "Common", ContentType.XML);
        int ownV = own.addVersion("<root><a>1</a></root>", "seed", "test", 1L);
        own.activate(ownV);
        repository.save(own);

        ConfigSet teamB = new ConfigSet("team-b-common-xml", ConfigSetRole.COMMON, null, "Team B Common", ContentType.XML);
        int bV = teamB.addVersion("<root><b>2</b></root>", "seed", "test", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        ConfigSet teamC = new ConfigSet("team-c-common-xml", ConfigSetRole.COMMON, null, "Team C Common", ContentType.XML);
        int cV1 = teamC.addVersion("<root><c>1</c></root>", "seed", "test", 1L);
        int cV2 = teamC.addVersion("<root><c>2</c></root>", "seed2", "test", 2L);
        teamC.activate(cV2);
        repository.save(teamC);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-multi-chain-xml");
        attachJobProperty(job, "<root><placeholder>x</placeholder></root>", Arrays.asList(
                BaseConfigReference.active("proj18"),
                BaseConfigReference.active("team-b-common-xml"),
                BaseConfigReference.pinned("team-c-common-xml", cV1)), "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}# z=#{c}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void multiEntryChainIncludingCrossProjectReferenceResolvesAllBasesYaml() throws Exception {
        // YAML sibling: same 3-entry chain shape, all Config Sets in the chain kept consistently YAML.
        ConfigSetRepository repository = new ConfigSetRepository();

        ConfigSet own = new ConfigSet("proj19", ConfigSetRole.COMMON, null, "Common", ContentType.YAML);
        int ownV = own.addVersion("a: 1\n", "seed", "test", 1L);
        own.activate(ownV);
        repository.save(own);

        ConfigSet teamB = new ConfigSet("team-b-common-yaml", ConfigSetRole.COMMON, null, "Team B Common", ContentType.YAML);
        int bV = teamB.addVersion("b: 2\n", "seed", "test", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        ConfigSet teamC = new ConfigSet("team-c-common-yaml", ConfigSetRole.COMMON, null, "Team C Common", ContentType.YAML);
        int cV1 = teamC.addVersion("c: 1\n", "seed", "test", 1L);
        int cV2 = teamC.addVersion("c: 2\n", "seed2", "test", 2L);
        teamC.activate(cV2);
        repository.save(teamC);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-multi-chain-yaml");
        attachJobProperty(job, "placeholder: x\n", Arrays.asList(
                BaseConfigReference.active("proj19"),
                BaseConfigReference.active("team-b-common-yaml"),
                BaseConfigReference.pinned("team-c-common-yaml", cV1)), "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}# z=#{c}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void missingChainReferenceFailsBuildNamingTheProject() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing-chain-ref");
        attachJobProperty(job, "{}",
                Collections.singletonList(BaseConfigReference.active("no-such-common-project")), null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("no-such-common-project", run);
    }

    @Test
    public void missingChainReferenceFailsBuildNamingTheProjectXml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing-chain-ref-xml");
        attachJobProperty(job, "<root><placeholder>x</placeholder></root>",
                Collections.singletonList(BaseConfigReference.active("no-such-common-project")), "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("no-such-common-project", run);
    }

    @Test
    public void missingChainReferenceFailsBuildNamingTheProjectYaml() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-missing-chain-ref-yaml");
        attachJobProperty(job, "placeholder: x\n",
                Collections.singletonList(BaseConfigReference.active("no-such-common-project")), "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("no-such-common-project", run);
    }

    @Test
    public void useBase_validatesAgainstNamedCommonOnly_bypassingJobsOwnLocalConfig() throws Exception {
        // Matrix rows 6/7: useBase=true + configKey='X' for validate is an intentional, valid use
        // case — a project-level CI check of the base template before any Job-specific override
        // values matter. The calling Job's own local config (with a conflicting 'a' value and an
        // extra 'onlyInJob' key) must never be consulted.
        seedCommon("proj6", "{\"a\":1}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-usebase");
        attachJobProperty(job, "{\"a\":2,\"onlyInJob\":3}",
                Collections.singletonList(BaseConfigReference.active("proj6")), null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', useBase: true, configKey: 'proj6')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void useBase_validatesAgainstNamedCommonOnly_bypassingJobsOwnLocalConfigXml() throws Exception {
        seedCommon("proj22", "<root><a>1</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-usebase-xml");
        attachJobProperty(job, "<root><a>2</a><onlyInJob>3</onlyInJob></root>",
                Collections.singletonList(BaseConfigReference.active("proj22")), "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', useBase: true, configKey: 'proj22')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void useBase_validatesAgainstNamedCommonOnly_bypassingJobsOwnLocalConfigYaml() throws Exception {
        seedCommon("proj23", "a: 1\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-usebase-yaml");
        attachJobProperty(job, "a: 2\nonlyInJob: 3\n",
                Collections.singletonList(BaseConfigReference.active("proj23")), "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', useBase: true, configKey: 'proj23')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void useBaseWithMissingExplicitVersion_failsLoudNamingIt() throws Exception {
        // Matrix row 7 miss: fail-loud naming the exact missing version number and which Config Set.
        seedCommon("proj7", "{\"a\":1}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-usebase-missing-version");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', useBase: true, configKey: 'proj7', version: 99)\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("proj7", run);
        jenkins.assertLogContains("99", run);
    }

    @Test
    public void useBaseWithMissingExplicitVersion_failsLoudNamingItXml() throws Exception {
        seedCommon("proj24", "<root><a>1</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-usebase-missing-version-xml");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', useBase: true, configKey: 'proj24', version: 99)\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("proj24", run);
        jenkins.assertLogContains("99", run);
    }

    @Test
    public void useBaseWithMissingExplicitVersion_failsLoudNamingItYaml() throws Exception {
        seedCommon("proj25", "a: 1\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-usebase-missing-version-yaml");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', useBase: true, configKey: 'proj25', version: 99)\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("proj25", run);
        jenkins.assertLogContains("99", run);
    }

    @Test
    public void jobOwnVersionPin_validatesAgainstThatExactVersion() throws Exception {
        // Matrix row 2: pinning the JOB's own version pins that version's own recorded baseChain for
        // free — the job-scoped replacement for the old "env version pin" coverage.
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("proj8", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int cv = common.addVersion("{\"a\":1}", "seed", "test", 1L);
        common.activate(cv);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-own-version-pin");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("proj8"));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int ev1 = property.addVersion("{}", "seed", "test", 1L, chain, null);
        int ev2 = property.addVersion("{\"onlyInV2\":9}", "second", "test", 2L, chain, null);
        property.activate(ev2);
        job.addProperty(property);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', version: " + ev1 + ")\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void jobOwnVersionPin_validatesAgainstThatExactVersionXml() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("proj26", ConfigSetRole.COMMON, null, "Common", ContentType.XML);
        int cv = common.addVersion("<root><a>1</a></root>", "seed", "test", 1L);
        common.activate(cv);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-own-version-pin-xml");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("proj26"));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int ev1 = property.addVersion("<root><placeholder>x</placeholder></root>", "seed", "test", 1L, chain, "XML");
        int ev2 = property.addVersion("<root><onlyInV2>9</onlyInV2></root>", "second", "test", 2L, chain, null);
        property.activate(ev2);
        job.addProperty(property);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', version: " + ev1 + ")\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void jobOwnVersionPin_validatesAgainstThatExactVersionYaml() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("proj27", ConfigSetRole.COMMON, null, "Common", ContentType.YAML);
        int cv = common.addVersion("a: 1\n", "seed", "test", 1L);
        common.activate(cv);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-own-version-pin-yaml");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("proj27"));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int ev1 = property.addVersion("placeholder: x\n", "seed", "test", 1L, chain, "YAML");
        int ev2 = property.addVersion("onlyInV2: 9\n", "second", "test", 2L, chain, null);
        property.activate(ev2);
        job.addProperty(property);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json', version: " + ev1 + ")\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void configKeyWithoutUseBase_failsLoudWithExactWording() throws Exception {
        // Matrix row 3 fail-loud, exercised against THIS step too (both steps share
        // StepSupport.mergeWithSetupState's identical check — see class javadoc).
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-row3-fail-loud");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=1'\n"
                        + "  configTemplateValidate(file: 'app.json', configKey: 'some-key')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("'configKey' ('some-key') is only valid together with useBase: true — "
                + "remove 'configKey', or add 'useBase: true' to this call.", run);
    }

    // --- UF-16: Job with no attached config at all (state 1) — valid, silent no-op / composes with
    // the existing drift-check machinery, never a special-cased error path. -----------------------

    @Test
    public void uf16_noAttachedProperty_zeroTokensInFile_isASilentNoOp() throws Exception {
        // job-scoped-config.md's "state 1: nothing configured" — a Job with no
        // JobConfigTemplateProperty at all resolves to an empty effective configuration, which is a
        // valid, non-error target. With zero #{...}# tokens in the target file, this must be a
        // correct, silent no-op success, not a special-cased error.
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-uf16-no-property-no-tokens");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'no tokens here'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    @Test
    public void uf16_noAttachedProperty_tokensInFile_failsViaExistingMissingKeysPath() throws Exception {
        // Same "state 1" Job, but the target file DOES have a token. No new special-case code is
        // needed: the effective configuration is empty, so the token has no matching key and the
        // *existing* missing-keys fail-loud path already fires naming it — this is the composition
        // pipeline-steps.md §3(d) describes explicitly.
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-uf16-no-property-with-token");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{anything}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("anything", run);
    }

    // --- UF-23: exact wording of the missing-keys (fatal) and orphaned-keys (non-fatal) messages,
    // per pipeline-steps.md's "Error/warning wording (as implemented, StepSupport.validateOrThrow)" --

    @Test
    public void uf23_missingKeyFailureMessage_matchesExactImplementedWording() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-uf23-missing-exact-wording");
        seedCommonAndJobDefaultChain(job, "uf23missingproj", "{\"a\":1}", "{}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{doesNotExist}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("[configTemplateSync] Missing config keys — target file 'app.json' "
                + "(Job='validate-uf23-missing-exact-wording', resolved via this Job's own local config, active "
                + "version) references 1 token with no matching key in the "
                + "effective configuration: [#{doesNotExist}#]. Check for a typo in the token's dotted path, or add "
                + "this key to the resolved source (this Job's own Config Templates, or the global COMMON "
                + "Config Set referenced via useBase/configKey).", run);
    }

    @Test
    public void uf23_orphanedKeyWarning_matchesExactImplementedWording() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-uf23-orphan-exact-wording");
        seedCommonAndJobDefaultChain(job, "uf23orphanproj", "{\"a\":1,\"unused\":2}", "{}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateValidate(file: 'app.json')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("[configTemplateSync][WARN] Orphaned config keys — "
                + "Job='validate-uf23-orphan-exact-wording', resolved via this Job's own local config, active "
                + "version: the effective configuration has 1 key with no "
                + "matching token in target file 'app.json': [#{unused}#]. This is not a failure, but likely means "
                + "either the key is genuinely unused or the token was removed from the file without removing "
                + "the key.", run);
    }

    // --- New matrix-row coverage (2026-09-14): a DIFFERENT matrix row must produce a DIFFERENT
    // resolution-mode phrase in the identical message shape, per StepSupport.describeResolutionMode. --

    @Test
    public void uf23_missingKeyFailureMessage_useBaseWithConfigKey_showsGlobalConfigSetResolutionMode()
            throws Exception {
        // Matrix row 6 (useBase:true, configKey:'X', no version) — direct global COMMON lookup by
        // name, Job's own config never consulted. Distinct resolution-mode phrase from the default
        // row-1 phrase asserted above ("global Config Set 'X', active version" vs. "this Job's own
        // local config, active version").
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "validate-uf23-usebase-configkey-wording");
        seedCommon("uf23usebaseproj", "{\"a\":1}");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{doesNotExist}#'\n"
                        + "  configTemplateValidate(file: 'app.json', useBase: true, configKey: 'uf23usebaseproj')\n"
                        + "}", true));

        var run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("[configTemplateSync] Missing config keys — target file 'app.json' "
                + "(Job='validate-uf23-usebase-configkey-wording', resolved via global Config Set "
                + "'uf23usebaseproj', active version) references 1 token with no matching key in the "
                + "effective configuration: [#{doesNotExist}#]. Check for a typo in the token's dotted path, or add "
                + "this key to the resolved source (this Job's own Config Templates, or the global COMMON "
                + "Config Set referenced via useBase/configKey).", run);
    }
}
