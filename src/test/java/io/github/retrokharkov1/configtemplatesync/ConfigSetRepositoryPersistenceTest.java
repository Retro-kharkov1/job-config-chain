package io.github.retrokharkov1.configtemplatesync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies ConfigSet + version history round-trips through the XStream2-backed
 * {@link ConfigSetRepository} exactly as it would across a real Jenkins controller restart: this
 * test writes with one repository instance (bound to a temp dir standing in for
 * {@code $JENKINS_HOME/config-template-sync}) and reads back with a brand-new instance pointed at
 * the same directory, simulating the process boundary.
 */
class ConfigSetRepositoryPersistenceTest {

    @Test
    void roundTripsAcrossSimulatedRestart(@TempDir File tempDir) {
        ConfigSetRepository writer = new ConfigSetRepository(tempDir);

        Map<String, String> manifest = Collections.singletonMap("Db.Password", "myapp-dev-secrets");
        int v1 = writer.save("myapp-dev", "{\"AppSettings\":{\"TimeoutSeconds\":\"30\"}}",
                manifest, "seed", "alice", true);
        int v2 = writer.save("myapp-dev", "{\"AppSettings\":{\"TimeoutSeconds\":\"45\"}}",
                null, "tune timeout", "bob", true);

        // Fresh instance, same directory -> simulates a controller restart re-reading $JENKINS_HOME.
        ConfigSetRepository reader = new ConfigSetRepository(tempDir);

        List<ConfigSetVersion> versions = reader.listVersions("myapp-dev");
        assertEquals(2, versions.size());
        assertEquals(v2, versions.get(0).getVersionNumber(), "listVersions must be newest-first");
        assertEquals(v1, versions.get(1).getVersionNumber());
        assertTrue(versions.get(0).isActive());
        assertEquals("{\"AppSettings\":{\"TimeoutSeconds\":\"45\"}}", reader.getActive("myapp-dev"));
        assertEquals("myapp-dev-secrets", reader.getConfigSet("myapp-dev")
                .getSecretsManifest().get("Db.Password"));

        // Rollback also survives a restart.
        reader.activate("myapp-dev", v1, "alice");
        ConfigSetRepository reader2 = new ConfigSetRepository(tempDir);
        assertEquals("{\"AppSettings\":{\"TimeoutSeconds\":\"30\"}}", reader2.getActive("myapp-dev"));
        assertEquals(2, reader2.listVersions("myapp-dev").size(), "rollback must not delete history");
    }

    @Test
    void oneXmlFilePerConfigSetKey(@TempDir File tempDir) {
        ConfigSetRepository repo = new ConfigSetRepository(tempDir);
        repo.save("myapp-common", "{\"Shared\":\"c\"}", null, null, "alice", true);
        repo.save("myapp-dev", "{\"Only\":\"e\"}", null, null, "alice", true);

        assertTrue(new File(tempDir, "myapp-common.xml").exists());
        assertTrue(new File(tempDir, "myapp-dev.xml").exists());
    }
}
