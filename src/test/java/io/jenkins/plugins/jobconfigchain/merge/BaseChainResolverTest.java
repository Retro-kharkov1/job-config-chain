package io.jenkins.plugins.jobconfigchain.merge;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BaseChainResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesActiveAndPinnedReferencesAcrossProjects() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        ConfigSet teamA = new ConfigSet("team-a-common", ConfigSetRole.COMMON, null, "Team A Common", ContentType.JSON);
        int aV1 = teamA.addVersion("{\"a\":1}", "v1", "alice", 1L);
        teamA.addVersion("{\"a\":2}", "v2", "alice", 2L);
        teamA.activate(aV1);
        repository.save(teamA);

        ConfigSet teamB = new ConfigSet("team-b-common", ConfigSetRole.COMMON, null, "Team B Common", ContentType.JSON);
        int bV1 = teamB.addVersion("{\"b\":1}", "v1", "bob", 1L);
        int bV2 = teamB.addVersion("{\"b\":2}", "v2", "bob", 2L);
        teamB.activate(bV2);
        repository.save(teamB);

        List<BaseConfigReference> chain = Arrays.asList(
                BaseConfigReference.active("team-a-common"),
                BaseConfigReference.pinned("team-b-common", bV1));

        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, chain);

        assertEquals(2, resolved.size());
        assertEquals(aV1, resolved.get(0).version.getVersionNumber());
        assertEquals(bV1, resolved.get(1).version.getVersionNumber());
    }

    @Test
    public void missingProjectResolvesToNullConfigSetAndVersionWithoutThrowing() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("no-such-project"));

        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, chain);

        assertEquals(1, resolved.size());
        assertNull(resolved.get(0).configSet);
        assertNull(resolved.get(0).version);
    }

    @Test
    public void missingPinnedVersionResolvesToNullVersionWithoutThrowing() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        ConfigSet teamA = new ConfigSet("team-a-common", ConfigSetRole.COMMON, null, "Team A Common", ContentType.JSON);
        teamA.addVersion("{\"a\":1}", "v1", "alice", 1L);
        repository.save(teamA);

        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.pinned("team-a-common", 99));
        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, chain);

        assertEquals(1, resolved.size());
        assertNull(resolved.get(0).version);
    }
}
