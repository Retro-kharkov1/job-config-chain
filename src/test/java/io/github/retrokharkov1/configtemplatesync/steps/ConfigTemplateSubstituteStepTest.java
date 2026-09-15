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
import io.github.retrokharkov1.configtemplatesync.ui.JobConfigTemplateProperty;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code configTemplateSubstitute} end-to-end pipeline tests against the job-scoped resolution
 * matrix (tech-lead scoping decision, 2026-09-14, pipeline-steps.md "Pipeline call resolution —
 * the final parameter model"). Every call resolves against the CALLING JOB's own attached
 * {@link JobConfigTemplateProperty} by construction, unless {@code useBase: true} + {@code
 * configKey} redirects to a named global COMMON Config Set. There is no {@code projectKey}/
 * {@code environment} call parameter — that calling form (and {@code ConfigSetRole.ENV}/
 * {@code EnvConfigSetPage} themselves) was retired in full, not deprecated.
 */
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

    private ConfigSet seedCommon(String projectKey, String content, ContentType type) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", type);
        int v = common.addVersion(content, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
        return common;
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

    /** Format-parameterized sibling of {@link #seedCommonWithSecret(String, String, String, String)}. */
    private ConfigSet seedCommonWithSecret(String projectKey, String dottedPath, String credentialId,
                                            String contentWithPlaceholder, ContentType type) throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", type);
        common.putSecretManifestEntry(dottedPath, credentialId);
        int v = common.addVersion(contentWithPlaceholder, "seed", "test", 1L);
        common.activate(v);
        repository.save(common);
        return common;
    }

    /**
     * Attaches a {@link JobConfigTemplateProperty} directly to {@code job} — the job-scoped
     * replacement for the old {@code seedEnv(projectKey, environment, patchJson)} helper. Unlike an
     * env Config Set, a job version has NO FR-52 self-reference default (job-scoped-config.md), so
     * callers must pass the base chain explicitly.
     */
    private JobConfigTemplateProperty attachJobProperty(WorkflowJob job, String contentJson,
            List<BaseConfigReference> baseChain, String contentTypeOrNull) throws Exception {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion(contentJson, "seed", "test", 1L, baseChain, contentTypeOrNull);
        property.activate(v);
        job.addProperty(property);
        return property;
    }

    /**
     * The common job-scoped-equivalent shape used throughout this file to mirror the OLD
     * {@code seedEnv(projectKey, environment, patchJson)}: the job's own single base-chain entry
     * self-references the SAME {@code projectKey} its own common Config Set was seeded under
     * (mirroring the old FR-52 default that a job version never gets automatically) with an
     * (usually empty) overlay patch.
     */
    private JobConfigTemplateProperty attachJobPropertyDefaultChain(WorkflowJob job, String projectKey,
            String overlayJson, String contentTypeOrNull) throws Exception {
        return attachJobProperty(job, overlayJson,
                Collections.singletonList(BaseConfigReference.active(projectKey)), contentTypeOrNull);
    }

    private void seedRealStringCredential(String id, String secretValue) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, id, "test credential seeded for substitute step test",
                Secret.fromString(secretValue)));
        SystemCredentialsProvider.getInstance().save();
    }

    @Test
    public void secretManifestPath_resolvesRealCredentialValue_notEnvVarOrPlaceholder() throws Exception {
        // FR-13/FR-21: the real value must come from the Jenkins credential store, exclusively,
        // even though NO env var named "Database.Password" is exported anywhere in this pipeline.
        seedRealStringCredential("subproj7-db-pass", "S3cr3tDbPass!");
        seedCommonWithSecret("subproj7", "Database.Password", "subproj7-db-pass",
                "{\"Database\":{\"Password\":\"" + SecretPlaceholder.VALUE + "\"}}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-real-secret");
        attachJobPropertyDefaultChain(job, "subproj7", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
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

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-missing-credential");
        attachJobPropertyDefaultChain(job, "subproj8", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("does-not-exist-credential-id", run);
        jenkins.assertLogContains("Database.Password", run);
        assertTrue("must not report success while silently substituting a placeholder",
                run.getResult() == Result.FAILURE);
    }

    @Test
    public void secretManifestPath_resolvesRealCredentialValue_notEnvVarOrPlaceholderXml() throws Exception {
        // XML sibling of the JSON test above: same secret-manifest resolution scenario, Config Set
        // content in XML.
        seedRealStringCredential("subproj20-db-pass", "S3cr3tDbPass!");
        seedCommonWithSecret("subproj20", "Database.Password", "subproj20-db-pass",
                "<root><Database><Password>" + SecretPlaceholder.VALUE + "</Password></Database></root>",
                ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-real-secret-xml");
        attachJobPropertyDefaultChain(job, "subproj20", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  def content = readFile('app.json')\n"
                        + "  echo \"RESULT:${content}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:password=S3cr3tDbPass!", run);
        assertFalse("must never fall back to the raw placeholder text",
                jenkins.getLog(run).contains("password=" + SecretPlaceholder.VALUE));
    }

    @Test
    public void secretManifestPath_resolvesRealCredentialValue_notEnvVarOrPlaceholderYaml() throws Exception {
        // YAML sibling of the JSON test above: same secret-manifest resolution scenario, Config Set
        // content in YAML. "__SECRET__" contains no YAML-special characters, so no quoting is needed.
        seedRealStringCredential("subproj21-db-pass", "S3cr3tDbPass!");
        seedCommonWithSecret("subproj21", "Database.Password", "subproj21-db-pass",
                "Database:\n  Password: " + SecretPlaceholder.VALUE + "\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-real-secret-yaml");
        attachJobPropertyDefaultChain(job, "subproj21", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  def content = readFile('app.json')\n"
                        + "  echo \"RESULT:${content}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:password=S3cr3tDbPass!", run);
        assertFalse("must never fall back to the raw placeholder text",
                jenkins.getLog(run).contains("password=" + SecretPlaceholder.VALUE));
    }

    @Test
    public void secretManifestPath_missingCredential_failsBuildLoudlyNamingItXml() throws Exception {
        seedCommonWithSecret("subproj22", "Database.Password", "does-not-exist-credential-id",
                "<root><Database><Password>" + SecretPlaceholder.VALUE + "</Password></Database></root>",
                ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-missing-credential-xml");
        attachJobPropertyDefaultChain(job, "subproj22", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("does-not-exist-credential-id", run);
        jenkins.assertLogContains("Database.Password", run);
        assertTrue("must not report success while silently substituting a placeholder",
                run.getResult() == Result.FAILURE);
    }

    @Test
    public void secretManifestPath_missingCredential_failsBuildLoudlyNamingItYaml() throws Exception {
        seedCommonWithSecret("subproj23", "Database.Password", "does-not-exist-credential-id",
                "Database:\n  Password: " + SecretPlaceholder.VALUE + "\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-missing-credential-yaml");
        attachJobPropertyDefaultChain(job, "subproj23", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'password=#{Database.Password}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("does-not-exist-credential-id", run);
        jenkins.assertLogContains("Database.Password", run);
        assertTrue("must not report success while silently substituting a placeholder",
                run.getResult() == Result.FAILURE);
    }

    // --- UF-7: matrix row 1, default call — own override AND folded base BOTH land in one call ----

    @Test
    public void row1_defaultCall_foldsJobsOwnBaseChainAndAppliesOwnOverrideBothInOneCall() throws Exception {
        // UF-7's own worked example: a plain default call (no useBase/configKey/version) must resolve
        // BOTH this Job's own override content AND its folded base chain's content in the SAME
        // substituted file. Every other happyPath-style test in this class seeds the job's own
        // override as empty ("{}"), so it only ever proves the base-fold half of row 1 — this test is
        // the one that actually proves the "own override + folded base coexist" claim UF-7 makes.
        seedCommon("row1proj", "{\"Database\":{\"Host\":\"db-host-from-base\"}}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-row1-own-override-plus-base");
        attachJobPropertyDefaultChain(job, "row1proj", "{\"Own\":{\"Override\":\"own-override-value\"}}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', "
                        + "text: 'own=#{Own.Override}# host=#{Database.Host}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:own=own-override-value host=db-host-from-base", run);
    }

    // --- UF-16: Job with no attached config at all (state 1) — valid, silent no-op / composes with
    // the existing "unresolved token remains" fail-loud path, never a special-cased error. ----------

    @Test
    public void uf16_noAttachedProperty_zeroTokensInFile_isASilentNoOp() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-uf16-no-property-no-tokens");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'no tokens here'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:no tokens here", run);
    }

    @Test
    public void uf16_noAttachedProperty_tokenInFile_failsViaExistingUnresolvedTokenPath() throws Exception {
        // Same "state 1" Job (job-scoped-config.md), but the file has a token with nothing to
        // substitute it with — no new special-case code needed: the pre-substitution defensive
        // re-check (OQ-7) already fails this exactly like any other missing-key case.
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-uf16-no-property-with-token");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{anything}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("anything", run);
    }

    @Test
    public void happyPath_substitutesValuesIntoTargetFile() throws Exception {
        seedCommon("subproj1", "{\"Feature\":{\"Name\":\"widgets\"}}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-happy");
        attachJobPropertyDefaultChain(job, "subproj1", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'name=#{Feature.Name}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  def content = readFile('app.json')\n"
                        + "  echo \"RESULT:${content}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:name=widgets", run);
    }

    @Test
    public void happyPath_substitutesValuesIntoTargetFileXml() throws Exception {
        seedCommon("subproj24", "<root><Feature><Name>widgets</Name></Feature></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-happy-xml");
        attachJobPropertyDefaultChain(job, "subproj24", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'name=#{Feature.Name}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  def content = readFile('app.json')\n"
                        + "  echo \"RESULT:${content}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:name=widgets", run);
    }

    @Test
    public void happyPath_substitutesValuesIntoTargetFileYaml() throws Exception {
        seedCommon("subproj25", "Feature:\n  Name: widgets\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-happy-yaml");
        attachJobPropertyDefaultChain(job, "subproj25", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'name=#{Feature.Name}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  def content = readFile('app.json')\n"
                        + "  echo \"RESULT:${content}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:name=widgets", run);
    }

    @Test
    public void remainingTokenAfterSubstitutionFailsBuild() throws Exception {
        seedCommon("subproj2", "{\"a\":1}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-remaining-token");
        attachJobPropertyDefaultChain(job, "subproj2", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                // this file has a token the effective config also has no key for -> defensive
                // re-check (OQ-7) fails it before substitution even runs.
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{missing}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("missing", run);
    }

    @Test
    public void remainingTokenAfterSubstitutionFailsBuildXml() throws Exception {
        seedCommon("subproj26", "<root><a>1</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-remaining-token-xml");
        attachJobPropertyDefaultChain(job, "subproj26", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{missing}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("missing", run);
    }

    @Test
    public void remainingTokenAfterSubstitutionFailsBuildYaml() throws Exception {
        seedCommon("subproj27", "a: 1\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-remaining-token-yaml");
        attachJobPropertyDefaultChain(job, "subproj27", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{missing}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("missing", run);
    }

    @Test
    public void postSubstitutionValueThatItselfLooksLikeATokenIsFlaggedAsUnresolved() throws Exception {
        // The pre-substitution defensive re-check (OQ-7) passes: the file's only token, #{a}#, has a
        // matching key 'a' in the effective config, so StepSupport.validateOrThrow does not fire.
        // But the value stored at 'a' is itself a literal string shaped like a token ("#{b}#"), so
        // once substitution replaces #{a}# with that literal value, the RESULT still contains a
        // token-shaped string. This must be caught by the separate post-substitution
        // TokenExtractor.containsAnyToken(...) check, not the pre-substitution one.
        seedCommon("subproj15", "{\"a\":\"#{b}#\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-post-substitution-token-shaped-value");
        attachJobPropertyDefaultChain(job, "subproj15", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("Substitution incomplete", run);
        jenkins.assertLogContains("tokens remain unresolved", run);
        jenkins.assertLogContains("b", run);
    }

    @Test
    public void postSubstitutionValueThatItselfLooksLikeATokenIsFlaggedAsUnresolvedXml() throws Exception {
        // XML sibling of the JSON test above: the same "value itself looks like a token" scenario,
        // this time with the Config Set's own stored content in XML. No quoting concern in XML --
        // '#' inside element text is plain text, unlike YAML's comment-start rule.
        seedCommon("subproj18", "<root><a>#{b}#</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-post-substitution-token-shaped-value-xml");
        attachJobPropertyDefaultChain(job, "subproj18", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("Substitution incomplete", run);
        jenkins.assertLogContains("tokens remain unresolved", run);
        jenkins.assertLogContains("b", run);
    }

    @Test
    public void postSubstitutionValueThatItselfLooksLikeATokenIsFlaggedAsUnresolvedYaml() throws Exception {
        // YAML sibling of the JSON test above. IMPORTANT: the value must be quoted ("#{b}#"), because
        // in YAML an unquoted '#' preceded by whitespace starts a comment -- 'a: #{b}#' would parse
        // the value as empty/null instead of the literal token string, silently defeating this test's
        // intent. Quoting preserves the literal token-shaped string as the actual value.
        seedCommon("subproj19", "a: \"#{b}#\"\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-post-substitution-token-shaped-value-yaml");
        attachJobPropertyDefaultChain(job, "subproj19", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("Substitution incomplete", run);
        jenkins.assertLogContains("tokens remain unresolved", run);
        jenkins.assertLogContains("b", run);
    }

    @Test
    public void fr97_defaultOn_recordsBindingUnderOwnRunIdentityWithNoParameter() throws Exception {
        // FR-96/FR-97: every successful real substitution automatically derives its own
        // Run-identity key and unconditionally creates/updates a binding for it — no parameter
        // needed at all. The binding is now keyed purely by the Run's own externalizableId
        // (2026-09-14 re-key) and getOwnConfigVersionNumber() reports the JOB's own resolved
        // version number (renamed from getEnvVersionNumber(); this is a real semantic shift from
        // the old getCommonVersionNumber()/getEnvVersionNumber() split, not just a rename — the
        // job's own version happens to be 1 here because it is this job's first-ever saved version).
        seedCommon("subproj3", "{\"a\":\"one\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-default-on-binding");
        attachJobPropertyDefaultChain(job, "subproj3", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        ConfigDeploymentBinding binding = bindingRepository.find(run.getExternalizableId());
        assertNotNull("a binding must exist under this run's own externalizableId with zero parameters",
                binding);
        assertEquals("ownConfigVersionNumber must be the job's own resolved version number",
                1, binding.getOwnConfigVersionNumber());
        assertEquals("the frozen chain must record the common project this job's own chain referenced",
                1, binding.getResolvedBaseChain().size());
        assertEquals("subproj3", binding.getResolvedBaseChain().get(0).getProjectKey());
        assertEquals(1, binding.getResolvedBaseChain().get(0).getVersionNumber());
    }

    @Test
    public void fr97_defaultOn_recordsBindingUnderOwnRunIdentityWithNoParameterXml() throws Exception {
        seedCommon("subproj28", "<root><a>one</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-default-on-binding-xml");
        attachJobPropertyDefaultChain(job, "subproj28", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        ConfigDeploymentBinding binding = bindingRepository.find(run.getExternalizableId());
        assertNotNull("a binding must exist under this run's own externalizableId with zero parameters",
                binding);
        assertEquals(1, binding.getOwnConfigVersionNumber());
    }

    @Test
    public void fr97_defaultOn_recordsBindingUnderOwnRunIdentityWithNoParameterYaml() throws Exception {
        seedCommon("subproj29", "a: one\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-default-on-binding-yaml");
        attachJobPropertyDefaultChain(job, "subproj29", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        ConfigDeploymentBinding binding = bindingRepository.find(run.getExternalizableId());
        assertNotNull("a binding must exist under this run's own externalizableId with zero parameters",
                binding);
        assertEquals(1, binding.getOwnConfigVersionNumber());
    }

    @Test
    public void fr98_explicitVersionSuppressesBindingLookupAndWrite() throws Exception {
        seedCommon("subproj4", "{\"a\":\"v1-value\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-version-suppresses-binding");
        attachJobPropertyDefaultChain(job, "subproj4", "{}", null); // job's own version 1
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', version: 1)\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        assertNull("an explicit 'version' must suppress the binding write entirely (FR-98)",
                bindingRepository.find(run.getExternalizableId()));
    }

    @Test
    public void fr98_explicitVersionSuppressesBindingLookupAndWriteXml() throws Exception {
        seedCommon("subproj30", "<root><a>v1-value</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-version-suppresses-binding-xml");
        attachJobPropertyDefaultChain(job, "subproj30", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', version: 1)\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        assertNull("an explicit 'version' must suppress the binding write entirely (FR-98)",
                bindingRepository.find(run.getExternalizableId()));
    }

    @Test
    public void fr98_explicitVersionSuppressesBindingLookupAndWriteYaml() throws Exception {
        seedCommon("subproj31", "a: v1-value\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-version-suppresses-binding-yaml");
        attachJobPropertyDefaultChain(job, "subproj31", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', version: 1)\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        assertNull("an explicit 'version' must suppress the binding write entirely (FR-98)",
                bindingRepository.find(run.getExternalizableId()));
    }

    @Test
    public void fr103_redeployFromRunWithNoBinding_fallsBackToActiveAndLogsFallback() throws Exception {
        seedCommon("subproj5", "{\"a\":\"one\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-redeploy-fallback");
        attachJobPropertyDefaultChain(job, "subproj5", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', redeployFromRun: '999')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        // FR-103: must surface, in build output, that it fell back to the currently active/pinned
        // chain, naming the unresolved redeployFromRun value explicitly.
        jenkins.assertLogContains("redeployFromRun '999'", run);
        jenkins.assertLogContains("falling back to the currently active/pinned base chain", run);
    }

    @Test
    public void fr103_redeployFromRunWithNoBinding_fallsBackToActiveAndLogsFallbackXml() throws Exception {
        seedCommon("subproj32", "<root><a>one</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-redeploy-fallback-xml");
        attachJobPropertyDefaultChain(job, "subproj32", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', redeployFromRun: '999')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("redeployFromRun '999'", run);
        jenkins.assertLogContains("falling back to the currently active/pinned base chain", run);
    }

    @Test
    public void fr103_redeployFromRunWithNoBinding_fallsBackToActiveAndLogsFallbackYaml() throws Exception {
        seedCommon("subproj33", "a: one\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-redeploy-fallback-yaml");
        attachJobPropertyDefaultChain(job, "subproj33", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', redeployFromRun: '999')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("redeployFromRun '999'", run);
        jenkins.assertLogContains("falling back to the currently active/pinned base chain", run);
    }

    @Test
    public void fr27_firstSubstitutionUnderOwnIdentity_usesActiveWithNoWarning() throws Exception {
        seedCommon("subproj6a", "{\"a\":\"one\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-no-pin");
        attachJobPropertyDefaultChain(job, "subproj6a", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertFalse("a Run's very first substitution under its own identity must not be a warned-about fallback",
                jenkins.getLog(run).contains("falling back"));
    }

    @Test
    public void fr27_firstSubstitutionUnderOwnIdentity_usesActiveWithNoWarningXml() throws Exception {
        seedCommon("subproj34", "<root><a>one</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-no-pin-xml");
        attachJobPropertyDefaultChain(job, "subproj34", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertFalse("a Run's very first substitution under its own identity must not be a warned-about fallback",
                jenkins.getLog(run).contains("falling back"));
    }

    @Test
    public void fr27_firstSubstitutionUnderOwnIdentity_usesActiveWithNoWarningYaml() throws Exception {
        seedCommon("subproj35", "a: one\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-no-pin-yaml");
        attachJobPropertyDefaultChain(job, "subproj35", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertFalse("a Run's very first substitution under its own identity must not be a warned-about fallback",
                jenkins.getLog(run).contains("falling back"));
    }

    @Test
    public void fr25_sameRunRepeatCallFindsItsOwnPriorBindingAndReplays() throws Exception {
        // FR-25's "(a) the current Run's own ... identity" case — a Pipeline restart-from-stage
        // analogue, simulated here as two substitute calls within the same build.
        seedCommon("subproj6", "{\"a\":\"v1-value\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-same-run-self-lookup");
        attachJobPropertyDefaultChain(job, "subproj6", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"SECOND:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("FIRST:x=v1-value", run);
        jenkins.assertLogContains("SECOND:x=v1-value", run);
        jenkins.assertLogContains("This run's own prior binding is pinned", run);
    }

    @Test
    public void frozenBindingReplay_defensiveRecheckOrphanedWarning_showsFrozenReplaySuffix() throws Exception {
        // Pipeline-steps.md §5's OQ-7 refinement: on the SECOND substitute call in the same build,
        // Branch C finds this run's own prior binding (pinned == true) and replays it. The
        // defensive drift re-check's resolution-mode phrase must then carry the
        // "(frozen deployment binding replay)" suffix — never the plain matrix-row phrase alone,
        // which would otherwise misleadingly look identical to a fresh, non-replayed resolution.
        seedCommon("subproj-frozen-replay", "{\"a\":\"v1-value\",\"unused\":\"x\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-frozen-replay-wording");
        attachJobPropertyDefaultChain(job, "subproj-frozen-replay", "{}", null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("This run's own prior binding is pinned", run);
        jenkins.assertLogContains("[configTemplateSync][WARN] Orphaned config keys — "
                + "Job='substitute-frozen-replay-wording', resolved via this Job's own local config, version 1 "
                + "(frozen deployment binding replay): the effective configuration has 1 key with no matching "
                + "token in target file 'app.json': [unused].", run);
    }

    @Test
    public void fr25_sameRunRepeatCallFindsItsOwnPriorBindingAndReplaysXml() throws Exception {
        seedCommon("subproj36", "<root><a>v1-value</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-same-run-self-lookup-xml");
        attachJobPropertyDefaultChain(job, "subproj36", "<root><placeholder>x</placeholder></root>", "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"SECOND:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("FIRST:x=v1-value", run);
        jenkins.assertLogContains("SECOND:x=v1-value", run);
        jenkins.assertLogContains("This run's own prior binding is pinned", run);
    }

    @Test
    public void fr25_sameRunRepeatCallFindsItsOwnPriorBindingAndReplaysYaml() throws Exception {
        seedCommon("subproj37", "a: v1-value\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-same-run-self-lookup-yaml");
        attachJobPropertyDefaultChain(job, "subproj37", "placeholder: x\n", "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
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

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-multi-chain");
        attachJobProperty(job, "{}", Arrays.asList(
                BaseConfigReference.active("subproj9"),
                BaseConfigReference.active("team-b-common")), null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=own y=fromB", run);
    }

    @Test
    public void multiEntryCrossProjectChainSubstitutesFromAllBasesXml() throws Exception {
        // XML sibling: same 2-entry chain shape, both Config Sets in the chain kept consistently XML
        // (a base chain's entries must be type-consistent per this plugin's own business rule).
        seedCommon("subproj38", "<root><a>own</a></root>", ContentType.XML);
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet teamB = new ConfigSet("team-b-sub-xml", ConfigSetRole.COMMON, null, "Team B Common", ContentType.XML);
        int bV = teamB.addVersion("<root><b>fromB</b></root>", "seed", "test", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-multi-chain-xml");
        attachJobProperty(job, "<root><placeholder>x</placeholder></root>", Arrays.asList(
                BaseConfigReference.active("subproj38"),
                BaseConfigReference.active("team-b-sub-xml")), "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=own y=fromB", run);
    }

    @Test
    public void multiEntryCrossProjectChainSubstitutesFromAllBasesYaml() throws Exception {
        // YAML sibling: same 2-entry chain shape, both Config Sets in the chain kept consistently YAML.
        seedCommon("subproj39", "a: own\n", ContentType.YAML);
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet teamB = new ConfigSet("team-b-sub-yaml", ConfigSetRole.COMMON, null, "Team B Common", ContentType.YAML);
        int bV = teamB.addVersion("b: fromB\n", "seed", "test", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-multi-chain-yaml");
        attachJobProperty(job, "placeholder: x\n", Arrays.asList(
                BaseConfigReference.active("subproj39"),
                BaseConfigReference.active("team-b-sub-yaml")), "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}# y=#{b}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
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
        //
        // Cross-JOB nuance under the job-scoped model: on a redeployFromRun replay,
        // overlayPatchForFrozenReplay re-applies the frozen ownConfigVersionNumber against the
        // CURRENT run's own job property (StepSupport/ConfigTemplateSubstituteStep.Execution — see
        // "own config version" javadoc), never against the ORIGINATING job's property. A realistic
        // cross-job redeploy pipeline (a separate "deploy" job replaying a "build" job's pinned
        // config) is therefore only byte-identical when the redeploying job's own version N carries
        // the SAME overlay content as the originating job's version N — seeded identically here,
        // mirroring that real-world precondition rather than assuming it away.
        seedCommon("subproj10", "{\"a\":\"v1-value\"}");

        WorkflowJob firstDeploy = jenkins.createProject(WorkflowJob.class, "fr54-first");
        attachJobPropertyDefaultChain(firstDeploy, "subproj10", "{}", null);
        firstDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
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
        // Same version-1 overlay content as firstDeploy's own job — see class-level nuance note above.
        attachJobPropertyDefaultChain(secondDeploy, "subproj10", "{}", null);
        secondDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', "
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
        ConfigDeploymentBinding ownBindingForSecond = bindingRepository.find(second.getExternalizableId());
        assertNotNull("FR-102: redeployFromRun must still forward-chain a binding under the CURRENT run",
                ownBindingForSecond);
        assertEquals(1, ownBindingForSecond.getOwnConfigVersionNumber());
    }

    @Test
    public void fr54_redeployFromRunReplayIsByteIdenticalAcrossRuns_evenAfterBaseChangesXml() throws Exception {
        // XML sibling of the JSON fr54 test: same cross-job redeployFromRun replay scenario, Config
        // Set content in XML.
        seedCommon("subproj40", "<root><a>v1-value</a></root>", ContentType.XML);

        WorkflowJob firstDeploy = jenkins.createProject(WorkflowJob.class, "fr54-first-xml");
        attachJobPropertyDefaultChain(firstDeploy, "subproj40", "<root><placeholder>x</placeholder></root>", "XML");
        firstDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun first = jenkins.assertBuildStatus(Result.SUCCESS, firstDeploy.scheduleBuild2(0));
        jenkins.assertLogContains("FIRST:x=v1-value", first);
        String firstOutput = jenkins.getLog(first).lines()
                .filter(l -> l.contains("FIRST:"))
                .findFirst().orElseThrow();

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloadedCommon = repository.findCommon("subproj40");
        int v2 = reloadedCommon.addVersion("<root><a>v2-value</a></root>", "bump", "test", 2L);
        reloadedCommon.activate(v2);
        repository.save(reloadedCommon);

        int firstBuildNumber = first.getNumber();
        WorkflowJob secondDeploy = jenkins.createProject(WorkflowJob.class, "fr54-second-xml");
        attachJobPropertyDefaultChain(secondDeploy, "subproj40", "<root><placeholder>x</placeholder></root>", "XML");
        secondDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', "
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

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        ConfigDeploymentBinding ownBindingForSecond = bindingRepository.find(second.getExternalizableId());
        assertNotNull("FR-102: redeployFromRun must still forward-chain a binding under the CURRENT run",
                ownBindingForSecond);
        assertEquals(1, ownBindingForSecond.getOwnConfigVersionNumber());
    }

    @Test
    public void fr54_redeployFromRunReplayIsByteIdenticalAcrossRuns_evenAfterBaseChangesYaml() throws Exception {
        // YAML sibling of the JSON fr54 test: same cross-job redeployFromRun replay scenario, Config
        // Set content in YAML.
        seedCommon("subproj41", "a: v1-value\n", ContentType.YAML);

        WorkflowJob firstDeploy = jenkins.createProject(WorkflowJob.class, "fr54-first-yaml");
        attachJobPropertyDefaultChain(firstDeploy, "subproj41", "placeholder: x\n", "YAML");
        firstDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"FIRST:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun first = jenkins.assertBuildStatus(Result.SUCCESS, firstDeploy.scheduleBuild2(0));
        jenkins.assertLogContains("FIRST:x=v1-value", first);
        String firstOutput = jenkins.getLog(first).lines()
                .filter(l -> l.contains("FIRST:"))
                .findFirst().orElseThrow();

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloadedCommon = repository.findCommon("subproj41");
        int v2 = reloadedCommon.addVersion("a: v2-value\n", "bump", "test", 2L);
        reloadedCommon.activate(v2);
        repository.save(reloadedCommon);

        int firstBuildNumber = first.getNumber();
        WorkflowJob secondDeploy = jenkins.createProject(WorkflowJob.class, "fr54-second-yaml");
        attachJobPropertyDefaultChain(secondDeploy, "subproj41", "placeholder: x\n", "YAML");
        secondDeploy.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', "
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

        ConfigDeploymentBindingRepository bindingRepository = new ConfigDeploymentBindingRepository();
        ConfigDeploymentBinding ownBindingForSecond = bindingRepository.find(second.getExternalizableId());
        assertNotNull("FR-102: redeployFromRun must still forward-chain a binding under the CURRENT run",
                ownBindingForSecond);
        assertEquals(1, ownBindingForSecond.getOwnConfigVersionNumber());
    }

    @Test
    public void fr54b_sameJobRebuildViaRedeployFromRunReplaysByteIdenticalDespiteLaterActiveVersionBump()
            throws Exception {
        // Literal same-job "rebuild" reading of FR-54/UF-6: build #1 auto-pins to Configuration A (v1),
        // build #2 (no redeployFromRun) live-resolves to Configuration B (v2) after it becomes active,
        // and build #3 explicitly redeployFromRun: '1' must replay build #1's frozen v1 output
        // byte-identically, even though v2 is now the currently-active version.
        seedCommon("subproj14", "{\"a\":\"v1-value\"}"); // Configuration A / v1

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "fr54b-same-job-rebuild");
        attachJobPropertyDefaultChain(job, "subproj14", "{}", null);

        // Build #1: plain call, auto-pins/binds build #1 to Configuration A (v1).
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"BUILD1:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build1 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(1, build1.getNumber());
        String build1Output = jenkins.getLog(build1).lines()
                .filter(l -> l.contains("BUILD1:")).findFirst().orElseThrow();

        // Activate Configuration B (v2) on the SAME Config Key in between build #1 and build #2.
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloadedCommon = repository.findCommon("subproj14");
        int v2 = reloadedCommon.addVersion("{\"a\":\"v2-value\"}", "activate config B", "test", 2L);
        reloadedCommon.activate(v2);
        repository.save(reloadedCommon);

        // Build #2: same job, plain call again, no redeployFromRun -> must live-resolve to B (v2).
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"BUILD2:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build2 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(2, build2.getNumber());
        jenkins.assertLogContains("BUILD2:x=v2-value", build2);
        String build2Output = jenkins.getLog(build2).lines()
                .filter(l -> l.contains("BUILD2:")).findFirst().orElseThrow();

        // Build #3: same job, explicit redeployFromRun: '1' targeting build #1's identity by bare
        // build number (resolveTargetIdentity resolves a '#'-less value against THIS run's own job).
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', "
                        + "redeployFromRun: '1')\n"
                        + "  echo \"BUILD3:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build3 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(3, build3.getNumber());
        jenkins.assertLogContains("BUILD3:x=v1-value", build3);
        String build3Output = jenkins.getLog(build3).lines()
                .filter(l -> l.contains("BUILD3:")).findFirst().orElseThrow();

        // Sanity check first: A and B must genuinely differ, or the byte-identical assertion below
        // would be vacuous.
        assertFalse("Configuration A and Configuration B outputs must differ, otherwise this test is vacuous",
                build1Output.replace("BUILD1:", "").equals(build2Output.replace("BUILD2:", "")));

        // The core assertion: rebuilding via redeployFromRun: '1' on build #3 must reproduce build #1's
        // ORIGINAL (Configuration A / v1) output byte-identically, not Configuration B / v2, even though
        // v2 is now the currently-active version.
        assertEquals("build #3's redeployFromRun: '1' replay must be byte-identical to build #1's original output",
                build1Output.replace("BUILD1:", ""), build3Output.replace("BUILD3:", ""));
    }

    @Test
    public void fr54b_sameJobRebuildViaRedeployFromRunReplaysByteIdenticalDespiteLaterActiveVersionBumpXml()
            throws Exception {
        // XML sibling of the JSON fr54b test: same same-job rebuild scenario, Config Set content in XML.
        seedCommon("subproj16", "<root><a>v1-value</a></root>", ContentType.XML); // Configuration A / v1

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "fr54b-same-job-rebuild-xml");
        attachJobPropertyDefaultChain(job, "subproj16", "<root><placeholder>x</placeholder></root>", "XML");

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"BUILD1:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build1 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(1, build1.getNumber());
        String build1Output = jenkins.getLog(build1).lines()
                .filter(l -> l.contains("BUILD1:")).findFirst().orElseThrow();

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloadedCommon = repository.findCommon("subproj16");
        int v2 = reloadedCommon.addVersion("<root><a>v2-value</a></root>", "activate config B", "test", 2L);
        reloadedCommon.activate(v2);
        repository.save(reloadedCommon);

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"BUILD2:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build2 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(2, build2.getNumber());
        jenkins.assertLogContains("BUILD2:x=v2-value", build2);
        String build2Output = jenkins.getLog(build2).lines()
                .filter(l -> l.contains("BUILD2:")).findFirst().orElseThrow();

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', "
                        + "redeployFromRun: '1')\n"
                        + "  echo \"BUILD3:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build3 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(3, build3.getNumber());
        jenkins.assertLogContains("BUILD3:x=v1-value", build3);
        String build3Output = jenkins.getLog(build3).lines()
                .filter(l -> l.contains("BUILD3:")).findFirst().orElseThrow();

        assertFalse("Configuration A and Configuration B outputs must differ, otherwise this test is vacuous",
                build1Output.replace("BUILD1:", "").equals(build2Output.replace("BUILD2:", "")));

        assertEquals("build #3's redeployFromRun: '1' replay must be byte-identical to build #1's original output",
                build1Output.replace("BUILD1:", ""), build3Output.replace("BUILD3:", ""));
    }

    @Test
    public void fr54b_sameJobRebuildViaRedeployFromRunReplaysByteIdenticalDespiteLaterActiveVersionBumpYaml()
            throws Exception {
        // YAML sibling of the JSON fr54b test: same same-job rebuild scenario, Config Set content in YAML.
        seedCommon("subproj17", "a: v1-value\n", ContentType.YAML); // Configuration A / v1

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "fr54b-same-job-rebuild-yaml");
        attachJobPropertyDefaultChain(job, "subproj17", "placeholder: x\n", "YAML");

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"BUILD1:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build1 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(1, build1.getNumber());
        String build1Output = jenkins.getLog(build1).lines()
                .filter(l -> l.contains("BUILD1:")).findFirst().orElseThrow();

        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet reloadedCommon = repository.findCommon("subproj17");
        int v2 = reloadedCommon.addVersion("a: v2-value\n", "activate config B", "test", 2L);
        reloadedCommon.activate(v2);
        repository.save(reloadedCommon);

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"BUILD2:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build2 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(2, build2.getNumber());
        jenkins.assertLogContains("BUILD2:x=v2-value", build2);
        String build2Output = jenkins.getLog(build2).lines()
                .filter(l -> l.contains("BUILD2:")).findFirst().orElseThrow();

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', "
                        + "redeployFromRun: '1')\n"
                        + "  echo \"BUILD3:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun build3 = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertEquals(3, build3.getNumber());
        jenkins.assertLogContains("BUILD3:x=v1-value", build3);
        String build3Output = jenkins.getLog(build3).lines()
                .filter(l -> l.contains("BUILD3:")).findFirst().orElseThrow();

        assertFalse("Configuration A and Configuration B outputs must differ, otherwise this test is vacuous",
                build1Output.replace("BUILD1:", "").equals(build2Output.replace("BUILD2:", "")));

        assertEquals("build #3's redeployFromRun: '1' replay must be byte-identical to build #1's original output",
                build1Output.replace("BUILD1:", ""), build3Output.replace("BUILD3:", ""));
    }

    // --- Matrix rows 6/7 (useBase=true, configKey=X[, version]) -----------------------------

    @Test
    public void row6_useBaseWithConfigKey_resolvesDirectlyAgainstNamedCommon_ignoringJobsOwnLocalConfig()
            throws Exception {
        // Matrix row 6: useBase=true + configKey='X' is a DIRECT global lookup by name — the calling
        // Job's own local config is never consulted at all, even when it exists and would produce a
        // conflicting value.
        seedCommon("subproj11", "{\"a\":\"commonValue\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase");
        attachJobProperty(job, "{\"a\":\"jobOverrideMustNeverAppear\"}", Collections.emptyList(), null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, configKey: 'subproj11')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=commonValue", run);
    }

    @Test
    public void row6_useBaseWithConfigKey_resolvesDirectlyAgainstNamedCommon_ignoringJobsOwnLocalConfigXml()
            throws Exception {
        seedCommon("subproj42", "<root><a>commonValue</a></root>", ContentType.XML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase-xml");
        attachJobProperty(job, "<root><a>jobOverrideMustNeverAppear</a></root>", Collections.emptyList(), "XML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, configKey: 'subproj42')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=commonValue", run);
    }

    @Test
    public void row6_useBaseWithConfigKey_resolvesDirectlyAgainstNamedCommon_ignoringJobsOwnLocalConfigYaml()
            throws Exception {
        seedCommon("subproj43", "a: commonValue\n", ContentType.YAML);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase-yaml");
        attachJobProperty(job, "a: jobOverrideMustNeverAppear\n", Collections.emptyList(), "YAML");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, configKey: 'subproj43')\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=commonValue", run);
    }

    @Test
    public void row7_useBaseWithConfigKeyAndVersion_pinsNamedCommonToThatVersion() throws Exception {
        // Matrix row 7: useBase=true + configKey='X' + version=N pins the named global COMMON
        // Config Set (not the calling Job's own config) to that exact version.
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj12", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.addVersion("{\"a\":\"v1\"}", "seed", "test", 1L);
        int v2 = common.addVersion("{\"a\":\"v2\"}", "second", "test", 2L);
        common.activate(v2);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase-version");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, configKey: 'subproj12', version: 1)\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=v1", run);
    }

    @Test
    public void row7_useBaseWithConfigKeyAndVersion_pinsNamedCommonToThatVersionXml() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj44", ConfigSetRole.COMMON, null, "Common", ContentType.XML);
        common.addVersion("<root><a>v1</a></root>", "seed", "test", 1L);
        int v2 = common.addVersion("<root><a>v2</a></root>", "second", "test", 2L);
        common.activate(v2);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase-version-xml");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, configKey: 'subproj44', version: 1)\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=v1", run);
    }

    @Test
    public void row7_useBaseWithConfigKeyAndVersion_pinsNamedCommonToThatVersionYaml() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj45", ConfigSetRole.COMMON, null, "Common", ContentType.YAML);
        common.addVersion("a: v1\n", "seed", "test", 1L);
        int v2 = common.addVersion("a: v2\n", "second", "test", 2L);
        common.activate(v2);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-usebase-version-yaml");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, configKey: 'subproj45', version: 1)\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=v1", run);
    }

    // --- Matrix row 2 (useBase=false, version=N: the Job's own version, own baseChain+own content) --

    @Test
    public void row2_jobOwnVersionPin_pinsJobsOwnConfigToThatVersionIncludingItsRecordedBaseChain()
            throws Exception {
        // Matrix row 2: pinning the JOB's own version pins that version's own recorded baseChain for
        // free (no separate baseChain-pin parameter needed) — the job-scoped replacement for the old
        // FR-80 "pinning the env version pins its entire frozen baseChain" coverage.
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj13", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int cv = common.addVersion("{\"a\":\"common-value\"}", "seed", "test", 1L);
        common.activate(cv);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-own-version-pin");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("subproj13"));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int ev1 = property.addVersion("{\"a\":\"job-v1\"}", "seed", "test", 1L, chain, null);
        int ev2 = property.addVersion("{\"a\":\"job-v2\"}", "second", "test", 2L, chain, null);
        property.activate(ev2); // ACTIVE is v2; the pin below must still resolve v1 explicitly
        job.addProperty(property);

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', version: " + ev1 + ")\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=job-v1", run);
    }

    @Test
    public void row2_jobOwnVersionPin_pinsJobsOwnConfigToThatVersionIncludingItsRecordedBaseChainXml()
            throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj46", ConfigSetRole.COMMON, null, "Common", ContentType.XML);
        int cv = common.addVersion("<root><a>common-value</a></root>", "seed", "test", 1L);
        common.activate(cv);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-own-version-pin-xml");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("subproj46"));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int ev1 = property.addVersion("<root><a>job-v1</a></root>", "seed", "test", 1L, chain, "XML");
        int ev2 = property.addVersion("<root><a>job-v2</a></root>", "second", "test", 2L, chain, null);
        property.activate(ev2);
        job.addProperty(property);

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', version: " + ev1 + ")\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=job-v1", run);
    }

    @Test
    public void row2_jobOwnVersionPin_pinsJobsOwnConfigToThatVersionIncludingItsRecordedBaseChainYaml()
            throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet("subproj47", ConfigSetRole.COMMON, null, "Common", ContentType.YAML);
        int cv = common.addVersion("a: common-value\n", "seed", "test", 1L);
        common.activate(cv);
        repository.save(common);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-own-version-pin-yaml");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("subproj47"));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int ev1 = property.addVersion("a: job-v1\n", "seed", "test", 1L, chain, "YAML");
        int ev2 = property.addVersion("a: job-v2\n", "second", "test", 2L, chain, null);
        property.activate(ev2);
        job.addProperty(property);

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', version: " + ev1 + ")\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=job-v1", run);
    }

    // --- Matrix rows 4/5 (useBase=true, no configKey: Job's own recorded baseChain, override ignored) --

    @Test
    public void row4_useBaseTrueNoConfigKey_foldsJobsOwnBaseChainOnly_ignoringItsOwnOverrideContent()
            throws Exception {
        // Matrix row 4: useBase=true with no configKey folds the Job's own ACTIVE version's recorded
        // baseChain, but discards that SAME version's own override content — the sole behavioral
        // difference from row 1.
        seedCommon("row4proj", "{\"a\":\"fromCommon\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-row4");
        attachJobProperty(job, "{\"a\":\"jobOverrideMustBeIgnored\"}",
                Collections.singletonList(BaseConfigReference.active("row4proj")), null);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true)\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=fromCommon", run);
    }

    @Test
    public void row5_useBaseTrueWithVersionNoConfigKey_foldsThatJobVersionsOwnRecordedBaseChainOnly()
            throws Exception {
        // Matrix row 5: useBase=true + version=N (no configKey) targets the Job's own version N —
        // never an entry within a chain — folding THAT version's own recorded baseChain, again with
        // its own override content discarded.
        seedCommon("row5proj", "{\"a\":\"fromCommon\"}");

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-row5");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("row5proj"));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v1 = property.addVersion("{\"a\":\"jobV1OverrideMustBeIgnored\"}", "seed", "test", 1L, chain, null);
        property.addVersion("{\"a\":\"jobV2OverrideMustNeverAppearEither\"}", "second", "test", 2L,
                Collections.emptyList(), null);
        property.activate(v1);
        job.addProperty(property);

        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, version: " + v1 + ")\n"
                        + "  echo \"RESULT:${readFile('app.json')}\"\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        jenkins.assertLogContains("RESULT:x=fromCommon", run);
    }

    // --- Matrix row 3 fail-loud: configKey without useBase: true ----------------------------

    @Test
    public void row3_configKeyWithoutUseBase_failsLoudWithExactWording() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-row3-fail-loud");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=1'\n"
                        + "  configTemplateSubstitute(file: 'app.json', configKey: 'some-key')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("'configKey' ('some-key') is only valid together with useBase: true — "
                + "remove 'configKey', or add 'useBase: true' to this call.", run);
    }

    // --- Fail-loud: unknown configKey (matrix rows 6/7 miss) --------------------------------

    @Test
    public void unknownConfigKey_failsLoudNamingIt() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "substitute-unknown-configkey");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=1'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true, configKey: 'no-such-key')\n"
                        + "}", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        jenkins.assertLogContains("No global COMMON Config Set found for configKey 'no-such-key'.", run);
    }

    // --- Adversarial isolation test: a call can never reach ANOTHER Job's own local config --

    @Test
    public void isolation_callNeverReachesAnotherJobsOwnLocalConfig_underAnyParameter() throws Exception {
        // Isolation rule (pipeline-steps.md, unconditional across every matrix row): the ONLY things
        // any call can ever reach are (a) the calling Job's own local config, and (b) any global
        // COMMON Config Set. There is no parameter shaped to name another Job's own local config at
        // all — this test proves that in practice: two Jobs each carry their OWN, DIFFERENT local
        // config, referencing the SAME global COMMON project, and Job B's build must reflect ONLY
        // Job B's own override, never Job A's — even though nothing about Job B's pipeline call
        // changes between the two Jobs (job-scoped resolution is structural, not name-addressed).
        seedCommon("isolationproj", "{\"a\":\"fromCommon\",\"secret\":\"shared-baseline\"}");

        WorkflowJob jobA = jenkins.createProject(WorkflowJob.class, "isolation-job-a");
        attachJobProperty(jobA, "{\"secret\":\"JOB-A-OWN-SECRET-MUST-NEVER-LEAK-TO-JOB-B\"}",
                Collections.singletonList(BaseConfigReference.active("isolationproj")), null);
        jobA.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{secret}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"A_RESULT:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun runA = jenkins.assertBuildStatus(Result.SUCCESS, jobA.scheduleBuild2(0));
        jenkins.assertLogContains("A_RESULT:x=JOB-A-OWN-SECRET-MUST-NEVER-LEAK-TO-JOB-B", runA);

        WorkflowJob jobB = jenkins.createProject(WorkflowJob.class, "isolation-job-b");
        attachJobProperty(jobB, "{\"secret\":\"job-b-own-value\"}",
                Collections.singletonList(BaseConfigReference.active("isolationproj")), null);
        // Identical call shape to Job A's — there is no parameter anywhere that could be used to
        // "reach across" to Job A's own local config even if a pipeline author tried.
        jobB.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{secret}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json')\n"
                        + "  echo \"B_RESULT:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun runB = jenkins.assertBuildStatus(Result.SUCCESS, jobB.scheduleBuild2(0));

        jenkins.assertLogContains("B_RESULT:x=job-b-own-value", runB);
        assertFalse("Job B's build must never resolve Job A's own local config content",
                jenkins.getLog(runB).contains("JOB-A-OWN-SECRET-MUST-NEVER-LEAK-TO-JOB-B"));
    }

    @Test
    public void isolation_callNeverReachesAnotherJobsOwnLocalConfig_underVersionAndUseBaseParameters()
            throws Exception {
        // UF-15's own "future adversarial test" instruction — "attempt every parameter combination" —
        // taken further than the plain-default-call test above: also exercises matrix row 2
        // (explicit 'version') and row 4 ('useBase: true', no configKey), since both are additional
        // distinct call shapes that still resolve ONLY against the calling Job's own property. There
        // is structurally no parameter on either call shape that could name a different Job — this
        // proves it in practice for both, not only the plain default call.
        seedCommon("isolationproj2", "{\"a\":\"fromCommon\"}");

        WorkflowJob jobA = jenkins.createProject(WorkflowJob.class, "isolation-job-a-version-usebase");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("isolationproj2"));
        JobConfigTemplateProperty propertyA = new JobConfigTemplateProperty();
        int aV1 = propertyA.addVersion("{\"secret\":\"JOB-A-VERSION-PIN-SECRET-MUST-NEVER-LEAK-TO-JOB-B\"}",
                "seed", "test", 1L, chain, null);
        propertyA.activate(aV1);
        jobA.addProperty(propertyA);
        jobA.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{secret}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', version: " + aV1 + ")\n"
                        + "  echo \"A_RESULT:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun runA = jenkins.assertBuildStatus(Result.SUCCESS, jobA.scheduleBuild2(0));
        jenkins.assertLogContains("A_RESULT:x=JOB-A-VERSION-PIN-SECRET-MUST-NEVER-LEAK-TO-JOB-B", runA);

        WorkflowJob jobB = jenkins.createProject(WorkflowJob.class, "isolation-job-b-version-usebase");
        attachJobProperty(jobB, "{\"secret\":\"job-b-own-value-should-never-be-affected\"}", chain, null);
        // useBase: true, no configKey (matrix row 4) — folds JOB B's own recorded baseChain only;
        // 'secret' is only ever declared in each Job's own override, never in the shared common
        // Config Set, so it must be absent from row 4's output for Job B, and in particular must
        // never resolve to Job A's own pinned override value from the call above.
        jobB.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'app.json', text: 'x=#{a}#'\n"
                        + "  configTemplateSubstitute(file: 'app.json', useBase: true)\n"
                        + "  echo \"B_RESULT:${readFile('app.json')}\"\n"
                        + "}", true));
        WorkflowRun runB = jenkins.assertBuildStatus(Result.SUCCESS, jobB.scheduleBuild2(0));
        jenkins.assertLogContains("B_RESULT:x=fromCommon", runB);
        assertFalse("Job B's 'useBase: true' call must never resolve Job A's own pinned-version content",
                jenkins.getLog(runB).contains("JOB-A-VERSION-PIN-SECRET-MUST-NEVER-LEAK-TO-JOB-B"));
    }
}
