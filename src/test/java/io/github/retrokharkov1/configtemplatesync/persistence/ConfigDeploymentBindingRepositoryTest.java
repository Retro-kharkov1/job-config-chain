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

public class ConfigDeploymentBindingRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createsThenUpdatesInPlaceRatherThanDuplicating() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());

        repository.save("proj", "dev", "1.2.3", Collections.singletonList(new ResolvedBaseVersion("proj", 1)), 1, 1000L);
        repository.save("proj", "dev", "1.2.3", Collections.singletonList(new ResolvedBaseVersion("proj", 2)), 3, 2000L);

        ConfigDeploymentBinding binding = repository.find("proj", "dev", "1.2.3");
        assertNotNull(binding);
        assertEquals(2, binding.getCommonVersionNumber());
        assertEquals(3, binding.getEnvVersionNumber());
        assertEquals(2000L, binding.getDeployedAtUtcEpochMillis());
    }

    @Test
    public void findReturnsNullWhenNoBindingExists() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());
        assertNull(repository.find("proj", "dev", "9.9.9"));
    }

    @Test
    public void distinctBuildVersionsProduceDistinctBindings() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());
        repository.save("proj", "dev", "1.0.0", Collections.singletonList(new ResolvedBaseVersion("proj", 1)), 1, 1000L);
        repository.save("proj", "dev", "2.0.0", Collections.singletonList(new ResolvedBaseVersion("proj", 2)), 2, 2000L);

        assertEquals(1, repository.find("proj", "dev", "1.0.0").getCommonVersionNumber());
        assertEquals(2, repository.find("proj", "dev", "2.0.0").getCommonVersionNumber());
    }

    @Test
    public void multiEntryChainRoundTripsInOrderThroughRealXmlFile() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());
        List<ResolvedBaseVersion> chain = Arrays.asList(
                new ResolvedBaseVersion("team-a-common", 5),
                new ResolvedBaseVersion("team-b-common", 2));

        repository.save("proj", "dev", "1.2.3", chain, 4, 5000L);

        ConfigDeploymentBinding binding = repository.find("proj", "dev", "1.2.3");
        assertNotNull(binding);
        assertEquals(chain, binding.getResolvedBaseChain());
    }

    @Test
    public void oldShapeXmlWithoutResolvedBaseChainMigratesOnLoad() throws Exception {
        // Pre-multi-base-chain shape: a <commonVersionNumber> element and no <resolvedBaseChain>
        // element at all, matching the field list of the OLD 6-arg-int ConfigDeploymentBinding
        // constructor (projectKey, environment, buildVersion, commonVersionNumber, envVersionNumber,
        // deployedAtUtcEpochMillis), one element per field in declaration order (Decision 8: no aliases).
        File baseDir = temporaryFolder.newFolder();
        File xmlFile = new File(baseDir, "deployment-bindings.xml");
        String oldShapeXml = "<list>\n"
                + "  <io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding>\n"
                + "    <projectKey>legacy-proj</projectKey>\n"
                + "    <environment>dev</environment>\n"
                + "    <buildVersion>1.0.0</buildVersion>\n"
                + "    <commonVersionNumber>4</commonVersionNumber>\n"
                + "    <envVersionNumber>2</envVersionNumber>\n"
                + "    <deployedAtUtcEpochMillis>1234000</deployedAtUtcEpochMillis>\n"
                + "  </io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding>\n"
                + "</list>\n";
        Files.write(xmlFile.toPath(), oldShapeXml.getBytes(StandardCharsets.UTF_8));

        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(baseDir);
        ConfigDeploymentBinding binding = repository.find("legacy-proj", "dev", "1.0.0");

        assertNotNull(binding);
        assertEquals(Collections.singletonList(new ResolvedBaseVersion("legacy-proj", 4)),
                binding.getResolvedBaseChain());
        assertEquals(2, binding.getEnvVersionNumber());
        assertEquals(1234000L, binding.getDeployedAtUtcEpochMillis());
    }
}
