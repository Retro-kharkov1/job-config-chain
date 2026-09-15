package io.github.retrokharkov1.configtemplatesync.persistence;

import io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding;
import io.github.retrokharkov1.configtemplatesync.model.ResolvedBaseVersion;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        assertEquals(2, binding.getCommonVersionNumber());
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

        assertEquals(1, repository.find("proj#1.0.0").getCommonVersionNumber());
        assertEquals(2, repository.find("proj#2.0.0").getCommonVersionNumber());
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

    @Test
    public void oldShapeXmlWithoutResolvedBaseChainMigratesOnLoad() throws Exception {
        // Pre-multi-base-chain shape: a <commonVersionNumber> element and no <resolvedBaseChain>
        // element at all. This guard (ConfigDeploymentBinding#readResolve) is a SEPARATE, unrelated
        // legacy concern from the 2026-09-14 projectKey/environment re-key (tech-lead scoping
        // decision, pipeline-steps.md §1) — it guards an EARLIER schema evolution (the multi-base-chain
        // feature) and is left exactly as-is by that later rename, so this fixture intentionally uses
        // the class's CURRENT (post-rename) field names (buildVersion/commonVersionNumber/
        // ownConfigVersionNumber/deployedAtUtcEpochMillis) — XStream's RobustReflectionConverter
        // silently skips any unrecognized legacy <projectKey>/<environment>/<envVersionNumber>
        // elements it might encounter on a truly ancient document, which is exactly why those fields'
        // removal did not need to touch this migration guard's own logic (only its constructor call
        // shape, per the tech-lead decision).
        File baseDir = temporaryFolder.newFolder();
        File xmlFile = new File(baseDir, "deployment-bindings.xml");
        String oldShapeXml = "<list>\n"
                + "  <io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding>\n"
                + "    <buildVersion>legacy-proj#1.0.0</buildVersion>\n"
                + "    <commonVersionNumber>4</commonVersionNumber>\n"
                + "    <ownConfigVersionNumber>2</ownConfigVersionNumber>\n"
                + "    <deployedAtUtcEpochMillis>1234000</deployedAtUtcEpochMillis>\n"
                + "  </io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding>\n"
                + "</list>\n";
        Files.write(xmlFile.toPath(), oldShapeXml.getBytes(StandardCharsets.UTF_8));

        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(baseDir);
        ConfigDeploymentBinding binding = repository.find("legacy-proj#1.0.0");

        assertNotNull(binding);
        assertEquals(Collections.singletonList(new ResolvedBaseVersion("legacy", 4)),
                binding.getResolvedBaseChain());
        assertEquals(2, binding.getOwnConfigVersionNumber());
        assertEquals(1234000L, binding.getDeployedAtUtcEpochMillis());
    }
}
