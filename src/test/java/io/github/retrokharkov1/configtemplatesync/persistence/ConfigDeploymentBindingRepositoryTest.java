package io.github.retrokharkov1.configtemplatesync.persistence;

import io.github.retrokharkov1.configtemplatesync.model.ConfigDeploymentBinding;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class ConfigDeploymentBindingRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createsThenUpdatesInPlaceRatherThanDuplicating() throws Exception {
        ConfigDeploymentBindingRepository repository = new ConfigDeploymentBindingRepository(temporaryFolder.newFolder());

        repository.save("proj", "dev", "1.2.3", 1, 1, 1000L);
        repository.save("proj", "dev", "1.2.3", 2, 3, 2000L);

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
        repository.save("proj", "dev", "1.0.0", 1, 1, 1000L);
        repository.save("proj", "dev", "2.0.0", 2, 2, 2000L);

        assertEquals(1, repository.find("proj", "dev", "1.0.0").getCommonVersionNumber());
        assertEquals(2, repository.find("proj", "dev", "2.0.0").getCommonVersionNumber());
    }
}
