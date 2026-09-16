package io.jenkins.plugins.jobconfigchain.steps;

import hudson.AbortException;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StepSupportTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void effectiveBaseChainDefaultsToOwnProjectActiveWhenEnvVersionHasNoDeclaredChain() throws Exception {
        ConfigSetVersion envVersion = new ConfigSetVersion(1, "{}", "seed", "test", 1L);
        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain("proj", envVersion);
        assertEquals(Collections.singletonList(BaseConfigReference.active("proj")), chain);
    }

    @Test
    public void effectiveBaseChainDefaultsToOwnProjectActiveWhenEnvVersionIsNull() {
        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain("proj", null);
        assertEquals(Collections.singletonList(BaseConfigReference.active("proj")), chain);
    }

    @Test
    public void effectiveBaseChainUsesDeclaredChainWhenNonEmpty() throws Exception {
        List<BaseConfigReference> declared = Arrays.asList(
                BaseConfigReference.active("team-a-common"),
                BaseConfigReference.pinned("team-b-common", 2));
        ConfigSetVersion envVersion = new ConfigSetVersion(1, "{}", "seed", "test", 1L, declared);
        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain("proj", envVersion);
        assertEquals(declared, chain);
    }

    @Test
    public void resolveEffectiveFoldsMultiEntryChainAndReturnsResolvedVersions() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        ConfigSet teamA = new ConfigSet("team-a-common", ConfigSetRole.COMMON, null, "Team A Common", ContentType.JSON);
        int aV = teamA.addVersion("{\"a\":1}", "seed", "test", 1L);
        teamA.activate(aV);
        repository.save(teamA);

        ConfigSet teamB = new ConfigSet("team-b-common", ConfigSetRole.COMMON, null, "Team B Common", ContentType.JSON);
        int bV = teamB.addVersion("{\"b\":2}", "seed", "test", 1L);
        teamB.activate(bV);
        repository.save(teamB);

        List<BaseConfigReference> chain = Arrays.asList(
                BaseConfigReference.active("team-a-common"),
                BaseConfigReference.active("team-b-common"));
        ConfigSetVersion envVersion = new ConfigSetVersion(1, "{\"c\":3}", "seed", "test", 1L);

        StepSupport.ResolvedEffective resolved = StepSupport.resolveEffective(repository, chain, envVersion, null);

        assertEquals(2, resolved.resolvedBaseChain.size());
        assertEquals(aV, resolved.resolvedBaseChain.get(0).getVersionNumber());
        assertEquals(bV, resolved.resolvedBaseChain.get(1).getVersionNumber());
        assertEquals("1", TreePaths.get(resolved.mergedConfig, "a").leafAsString());
        assertEquals("2", TreePaths.get(resolved.mergedConfig, "b").leafAsString());
        assertEquals("3", TreePaths.get(resolved.mergedConfig, "c").leafAsString());
    }

    @Test
    public void resolveEffectiveThrowsAbortExceptionForMissingProject() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("no-such-project"));

        AbortException ex = assertThrows(AbortException.class,
                () -> StepSupport.resolveEffective(repository, chain, null, null));
        assertTrue(ex.getMessage().contains("no-such-project"));
    }

    @Test
    public void resolveEffectiveThrowsAbortExceptionForMissingPinnedVersion() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        ConfigSet teamA = new ConfigSet("team-a-common", ConfigSetRole.COMMON, null, "Team A Common", ContentType.JSON);
        teamA.addVersion("{\"a\":1}", "seed", "test", 1L);
        repository.save(teamA);

        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.pinned("team-a-common", 99));

        AbortException ex = assertThrows(AbortException.class,
                () -> StepSupport.resolveEffective(repository, chain, null, null));
        assertTrue(ex.getMessage().contains("team-a-common"));
        assertTrue(ex.getMessage().contains("99"));
    }

    @Test
    public void resolveEffectiveThrowsAbortExceptionForMismatchedContentTypesInChain() throws Exception {
        // FR-62: pipeline-path half of the cross-chain type-consistency invariant.
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        ConfigSet teamA = new ConfigSet("team-a-common", ConfigSetRole.COMMON, null, "Team A Common", ContentType.JSON);
        teamA.addVersion("{\"a\":1}", "seed", "test", 1L);
        teamA.activate(1);
        repository.save(teamA);

        ConfigSet teamB = new ConfigSet("team-b-common", ConfigSetRole.COMMON, null, "Team B Common", ContentType.XML);
        teamB.addVersion("<root><b>2</b></root>", "seed", "test", 1L);
        teamB.activate(1);
        repository.save(teamB);

        List<BaseConfigReference> chain = Arrays.asList(
                BaseConfigReference.active("team-a-common"),
                BaseConfigReference.active("team-b-common"));

        AbortException ex = assertThrows(AbortException.class,
                () -> StepSupport.resolveEffective(repository, chain, null, null));
        assertTrue(ex.getMessage().contains("Mismatched content types in base chain"));
        assertTrue(ex.getMessage().contains("team-a-common (JSON)"));
        assertTrue(ex.getMessage().contains("team-b-common (XML)"));
    }

    @Test
    public void resolveEffectiveThrowsAbortExceptionForMissingActiveVersion() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        ConfigSet teamA = new ConfigSet("team-a-common", ConfigSetRole.COMMON, null, "Team A Common", ContentType.JSON);
        // no version added, never activated -> getActiveVersion() is null
        repository.save(teamA);

        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("team-a-common"));

        AbortException ex = assertThrows(AbortException.class,
                () -> StepSupport.resolveEffective(repository, chain, null, null));
        assertTrue(ex.getMessage().contains("team-a-common"));
        assertTrue(ex.getMessage().contains("active"));
    }

    @Test
    public void effectiveBaseChainReturnsEmptyForExplicitlyStandaloneVersion() {
        // FR-87: a deliberate zero-base declaration must return an empty chain, never FR-52's
        // synthesized single-entry default.
        ConfigSetVersion standalone = new ConfigSetVersion(1, "{\"a\":1}", "standalone", "test", 1L,
                Collections.emptyList(), true);
        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain("proj", standalone);
        assertTrue(chain.isEmpty());
    }

    @Test
    public void resolveEffectiveWithEmptyChainFallsBackToEnvsOwnContentType() throws Exception {
        // FR-87/§2b gap fix: an explicitlyStandalone version's empty chain must not silently
        // mis-type as ContentType.JSON — it must fall back to the env Config Set's own contentType.
        // Rewritten (2026-09-14 full removal of ConfigSetRole.ENV): resolveEffective's `env`
        // parameter is role-agnostic (it only ever reads getContentType(), never getRole()/
        // getEnvironment()) — a COMMON-role Config Set exercises the exact same fallback-content-type
        // code path the old ENV-role fixture did.
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        ConfigSet env = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Env", ContentType.XML);
        ConfigSetVersion standalone = new ConfigSetVersion(1, "<root><a>1</a></root>", "standalone", "test", 1L,
                Collections.emptyList(), true);

        StepSupport.ResolvedEffective resolved =
                StepSupport.resolveEffective(repository, Collections.emptyList(), standalone, env);

        assertEquals(ContentType.XML, resolved.contentType);
    }

    @Test
    public void resolveUseBaseOnlyResolvesActiveCommonVersionDirectly() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        ConfigSet common = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v1 = common.addVersion("{\"a\":1}", "seed", "test", 1L);
        common.addVersion("{\"a\":2}", "second", "test", 2L);
        common.activate(v1);
        repository.save(common);

        StepSupport.ResolvedEffective resolved = StepSupport.resolveUseBaseOnly(repository, "proj", null);

        assertEquals("1", TreePaths.get(resolved.mergedConfig, "a").leafAsString());
        assertEquals(1, resolved.resolvedBaseChain.get(0).getVersionNumber());
    }

    @Test
    public void resolveUseBaseOnlyPinsExplicitVersion() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        ConfigSet common = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.addVersion("{\"a\":1}", "seed", "test", 1L);
        int v2 = common.addVersion("{\"a\":2}", "second", "test", 2L);
        common.activate(v2);
        repository.save(common);

        StepSupport.ResolvedEffective resolved = StepSupport.resolveUseBaseOnly(repository, "proj", 1);

        assertEquals("1", TreePaths.get(resolved.mergedConfig, "a").leafAsString());
    }

    @Test
    public void resolveUseBaseOnlyThrowsAbortExceptionForMissingExplicitVersion() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        ConfigSet common = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.addVersion("{\"a\":1}", "seed", "test", 1L);
        repository.save(common);

        AbortException ex = assertThrows(AbortException.class,
                () -> StepSupport.resolveUseBaseOnly(repository, "proj", 99));
        assertTrue(ex.getMessage().contains("proj"));
        assertTrue(ex.getMessage().contains("99"));
    }

    @Test
    public void resolveUseBaseOnlyThrowsAbortExceptionForUnknownConfigKey() throws Exception {
        // Matrix rows 6/7 miss (tech-lead scoping decision, 2026-09-14, pipeline-steps.md §3(b)):
        // configKey names a Config Set that does not exist as a global COMMON Config Set at all.
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        AbortException ex = assertThrows(AbortException.class,
                () -> StepSupport.resolveUseBaseOnly(repository, "no-such-key", null));
        assertEquals("[configTemplateSync] No global COMMON Config Set found for configKey 'no-such-key'.",
                ex.getMessage());
    }

    // NOTE: StepSupport#resolveJobScoped (the new Job-scoped entry point implementing matrix rows
    // 1/2/4/5/6/7) and StepSupport#mergeWithSetupState's row-3 fail-loud check both require a real
    // hudson.model.Job (JenkinsRule), which this fast, JenkinsRule-free unit-test class deliberately
    // does not carry. Their coverage lives in ConfigTemplateSubstituteStepTest/
    // ConfigTemplateValidateStepTest instead, exercised end to end through real pipeline runs against
    // real Jobs — see those classes' row1/row2/row4/row5/row6/row7 and configKey-without-useBase
    // tests.
}
