package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.AbortException;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreePaths;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
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

        StepSupport.ResolvedEffective resolved = StepSupport.resolveEffective(repository, chain, envVersion);

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
                () -> StepSupport.resolveEffective(repository, chain, null));
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
                () -> StepSupport.resolveEffective(repository, chain, null));
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
                () -> StepSupport.resolveEffective(repository, chain, null));
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
                () -> StepSupport.resolveEffective(repository, chain, null));
        assertTrue(ex.getMessage().contains("team-a-common"));
        assertTrue(ex.getMessage().contains("active"));
    }
}
