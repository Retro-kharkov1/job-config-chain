package io.github.retrokharkov1.configtemplatesync.persistence;

import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class ConfigSetRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesAndLoadsByProjectKeyAndRole() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        ConfigSet common = new ConfigSet("sample-app", ConfigSetRole.COMMON, null, "Common");
        common.addVersion("{\"a\":1}", "initial", "alice", 1L);
        repository.save(common);

        ConfigSet env = new ConfigSet("sample-app", ConfigSetRole.ENV, "dev", "Dev");
        env.addVersion("{\"a\":2}", "dev override", "bob", 2L);
        repository.save(env);

        ConfigSet loadedCommon = repository.findCommon("sample-app");
        ConfigSet loadedEnv = repository.findEnv("sample-app", "dev");

        assertNotNull(loadedCommon);
        assertEquals("sample-app", loadedCommon.getProjectKey());
        assertEquals(1, loadedCommon.getVersions().size());

        assertNotNull(loadedEnv);
        assertEquals("dev", loadedEnv.getEnvironment());
    }

    @Test
    public void findReturnsNullWhenNoSuchConfigSetPersisted() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        assertNull(repository.findCommon("does-not-exist"));
        assertNull(repository.findEnv("does-not-exist", "dev"));
    }

    @Test
    public void projectKeyPairingIsStructuralNotFreeTextMatching() throws Exception {
        // OQ-2: two different projectKeys never collide, even with similar names.
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        ConfigSet projA = new ConfigSet("proj-a", ConfigSetRole.COMMON, null, "A Common");
        projA.addVersion("{\"a\":1}", "init", "alice", 1L);
        repository.save(projA);

        ConfigSet projAExtended = new ConfigSet("proj-a-extended", ConfigSetRole.COMMON, null, "A Extended Common");
        projAExtended.addVersion("{\"a\":2}", "init", "alice", 1L);
        repository.save(projAExtended);

        assertEquals(1, repository.findCommon("proj-a").getVersions().get(0).getVersionNumber());
        assertNotNull(repository.findCommon("proj-a-extended"));
        assertEquals("proj-a-extended", repository.findCommon("proj-a-extended").getProjectKey());
    }
}
