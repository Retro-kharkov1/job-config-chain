package io.github.retrokharkov1.configtemplatesync.persistence;

import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class ConfigSetRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesAndLoadsByProjectKeyAndRole() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        ConfigSet common = new ConfigSet("sample-app", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.addVersion("{\"a\":1}", "initial", "alice", 1L);
        repository.save(common);

        ConfigSet env = new ConfigSet("sample-app", ConfigSetRole.ENV, "dev", "Dev", ContentType.JSON);
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

        ConfigSet projA = new ConfigSet("proj-a", ConfigSetRole.COMMON, null, "A Common", ContentType.JSON);
        projA.addVersion("{\"a\":1}", "init", "alice", 1L);
        repository.save(projA);

        ConfigSet projAExtended = new ConfigSet("proj-a-extended", ConfigSetRole.COMMON, null, "A Extended Common", ContentType.JSON);
        projAExtended.addVersion("{\"a\":2}", "init", "alice", 1L);
        repository.save(projAExtended);

        assertEquals(1, repository.findCommon("proj-a").getVersions().get(0).getVersionNumber());
        assertNotNull(repository.findCommon("proj-a-extended"));
        assertEquals("proj-a-extended", repository.findCommon("proj-a-extended").getProjectKey());
    }

    /**
     * FR-69: a Config Set persisted before the multi-format feature existed has no
     * {@code <contentType>} XML element at all — {@link ConfigSet#readResolve()} must default it to
     * {@link ContentType#JSON} rather than leaving it {@code null}. Simulated here by round-tripping
     * a real save through the repository, then stripping the {@code <contentType>} element from the
     * persisted XML on disk (reproducing the pre-feature file shape byte-for-byte) before reloading.
     */
    @Test
    public void oldShapeFixtureWithNoContentTypeElementDefaultsToJson() throws Exception {
        File baseDir = temporaryFolder.newFolder();
        ConfigSetRepository repository = new ConfigSetRepository(baseDir);

        ConfigSet common = new ConfigSet("legacy-app", ConfigSetRole.COMMON, null, "Legacy Common", ContentType.JSON);
        common.addVersion("{\"a\":1}", "initial", "alice", 1L);
        repository.save(common);

        File xmlFile = new File(baseDir, "legacy-app--common.xml");
        String xml = new String(Files.readAllBytes(xmlFile.toPath()), StandardCharsets.UTF_8);
        String stripped = xml.replaceAll("<contentType>[^<]*</contentType>\\s*", "");
        Files.write(xmlFile.toPath(), stripped.getBytes(StandardCharsets.UTF_8));

        ConfigSet reloaded = repository.findCommon("legacy-app");
        assertNotNull(reloaded);
        assertEquals(ContentType.JSON, reloaded.getContentType());
    }
}
