package io.jenkins.plugins.jobconfigchain.persistence;

import io.jenkins.plugins.jobconfigchain.model.ConfigDeploymentBinding;
import io.jenkins.plugins.jobconfigchain.model.ResolvedBaseVersion;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/**
 * Re-keyed (tech-lead scoping decision, 2026-09-14, pipeline-steps.md §1) to a pure Run-identity
 * ({@code buildVersion}) string — {@code projectKey}/{@code environment} are gone from both the
 * identity and the persisted shape entirely, since every pipeline call now resolves per-Job by
 * construction (a binding is inherently per-Job already).
 */
public class ConfigDeploymentBindingRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createsThenUpdatesInPlaceRatherThanDuplicating() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());

        repository.save("proj#1.2.3", Collections.singletonList(new ResolvedBaseVersion("proj", 1)), 1, 1000L);
        repository.save("proj#1.2.3", Collections.singletonList(new ResolvedBaseVersion("proj", 2)), 3, 2000L);

        ConfigDeploymentBinding binding = repository.find("proj#1.2.3");
        assertNotNull(binding);
        assertEquals(Collections.singletonList(new ResolvedBaseVersion("proj", 2)),
                binding.getResolvedBaseChain());
        assertEquals(3, binding.getOwnConfigVersionNumber());
        assertEquals(2000L, binding.getDeployedAtUtcEpochMillis());
    }

    @Test
    public void findReturnsNullWhenNoBindingExists() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());
        assertNull(repository.find("proj#9.9.9"));
    }

    @Test
    public void distinctBuildVersionsProduceDistinctBindings() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());
        repository.save("proj#1.0.0", Collections.singletonList(new ResolvedBaseVersion("proj", 1)), 1, 1000L);
        repository.save("proj#2.0.0", Collections.singletonList(new ResolvedBaseVersion("proj", 2)), 2, 2000L);

        assertEquals(Collections.singletonList(new ResolvedBaseVersion("proj", 1)),
                repository.find("proj#1.0.0").getResolvedBaseChain());
        assertEquals(Collections.singletonList(new ResolvedBaseVersion("proj", 2)),
                repository.find("proj#2.0.0").getResolvedBaseChain());
    }

    @Test
    public void multiEntryChainRoundTripsInOrderThroughRealXmlFile() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());
        List<ResolvedBaseVersion> chain = Arrays.asList(
                new ResolvedBaseVersion("team-a-common", 5),
                new ResolvedBaseVersion("team-b-common", 2));

        repository.save("proj#1.2.3", chain, 4, 5000L);

        ConfigDeploymentBinding binding = repository.find("proj#1.2.3");
        assertNotNull(binding);
        assertEquals(chain, binding.getResolvedBaseChain());
    }
}
