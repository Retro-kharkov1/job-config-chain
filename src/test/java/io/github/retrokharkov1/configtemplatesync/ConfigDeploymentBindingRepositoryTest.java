package io.github.retrokharkov1.configtemplatesync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain-JUnit persistence round-trip for {@link ConfigDeploymentBindingRepository}, mirroring
 * {@link ConfigSetRepositoryPersistenceTest}'s "fresh instance, same directory" simulated-restart
 * technique.
 */
class ConfigDeploymentBindingRepositoryTest {

    @Test
    void noBindingRecordedYetReturnsNull(@TempDir File tempDir) {
        ConfigDeploymentBindingRepository repo = new ConfigDeploymentBindingRepository(tempDir);
        assertNull(repo.find("myapp-dev", "1.2.3"));
    }

    @Test
    void findReturnsNullForBlankOrMissingBuildVersion(@TempDir File tempDir) {
        ConfigDeploymentBindingRepository repo = new ConfigDeploymentBindingRepository(tempDir);
        assertNull(repo.find("myapp-dev", null));
        assertNull(repo.find("myapp-dev", ""));
    }

    @Test
    void recordThenFindRoundTripsAcrossSimulatedRestart(@TempDir File tempDir) {
        ConfigDeploymentBindingRepository writer = new ConfigDeploymentBindingRepository(tempDir);
        writer.record("myapp-dev", "1.2.3", 4, 7);

        ConfigDeploymentBindingRepository reader = new ConfigDeploymentBindingRepository(tempDir);
        ConfigDeploymentBinding found = reader.find("myapp-dev", "1.2.3");

        assertEquals("myapp-dev", found.getProjectEnvKey());
        assertEquals("1.2.3", found.getBuildVersion());
        assertEquals(4, found.getCommonVersionNumber());
        assertEquals(7, found.getEnvVersionNumber());
    }

    @Test
    void recordWithNullCommonVersionNumberIsAllowed(@TempDir File tempDir) {
        ConfigDeploymentBindingRepository repo = new ConfigDeploymentBindingRepository(tempDir);
        repo.record("myapp-dev", "1.2.3", null, 2);

        assertNull(repo.find("myapp-dev", "1.2.3").getCommonVersionNumber());
    }

    @Test
    void recordOverwritesExistingBindingForSameBuildVersion(@TempDir File tempDir) {
        ConfigDeploymentBindingRepository repo = new ConfigDeploymentBindingRepository(tempDir);
        repo.record("myapp-dev", "1.2.3", 1, 1);
        repo.record("myapp-dev", "1.2.3", 2, 3);

        ConfigDeploymentBinding found = repo.find("myapp-dev", "1.2.3");
        assertEquals(2, found.getCommonVersionNumber());
        assertEquals(3, found.getEnvVersionNumber());
    }

    @Test
    void differentBuildVersionsUnderSameProjectEnvKeyAreIndependent(@TempDir File tempDir) {
        ConfigDeploymentBindingRepository repo = new ConfigDeploymentBindingRepository(tempDir);
        repo.record("myapp-dev", "1.2.3", 1, 1);
        repo.record("myapp-dev", "1.3.0", 2, 5);

        assertEquals(1, repo.find("myapp-dev", "1.2.3").getEnvVersionNumber());
        assertEquals(5, repo.find("myapp-dev", "1.3.0").getEnvVersionNumber());
    }

    @Test
    void oneXmlFilePerProjectEnvKey(@TempDir File tempDir) {
        ConfigDeploymentBindingRepository repo = new ConfigDeploymentBindingRepository(tempDir);
        repo.record("myapp-dev", "1.2.3", 1, 1);
        repo.record("myapp-qa", "1.2.3", 1, 1);

        assertEquals(true, new File(tempDir, "myapp-dev.xml").exists());
        assertEquals(true, new File(tempDir, "myapp-qa.xml").exists());
    }
}
