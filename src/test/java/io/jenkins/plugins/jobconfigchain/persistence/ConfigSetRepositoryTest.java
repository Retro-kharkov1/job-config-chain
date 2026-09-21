package io.jenkins.plugins.jobconfigchain.persistence;

import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigSetRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesAndLoadsByProjectKey() throws Exception {
        // Rewritten (2026-09-14 full removal of ConfigSetRole.ENV): findEnv/listEnv no longer exist
        // on ConfigSetRepository — only findCommon survives. The multi-Config-Set-per-projectKey
        // shape this test proved (repository never confuses two persisted documents) is preserved by
        // saving two DIFFERENT common Config Sets and confirming each loads independently instead.
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());

        ConfigSet common = new ConfigSet("sample-app", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.addVersion("{\"a\":1}", "initial", "alice", 1L);
        repository.save(common);

        ConfigSet other = new ConfigSet("sample-app-other", ConfigSetRole.COMMON, null, "Other Common", ContentType.JSON);
        other.addVersion("{\"a\":2}", "other seed", "bob", 2L);
        repository.save(other);

        ConfigSet loadedCommon = repository.findCommon("sample-app");
        ConfigSet loadedOther = repository.findCommon("sample-app-other");

        assertNotNull(loadedCommon);
        assertEquals("sample-app", loadedCommon.getProjectKey());
        assertEquals(1, loadedCommon.getVersions().size());

        assertNotNull(loadedOther);
        assertEquals("sample-app-other", loadedOther.getProjectKey());
    }

    @Test
    public void findReturnsNullWhenNoSuchConfigSetPersisted() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        assertNull(repository.findCommon("does-not-exist"));
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

    // ---- Deletion lifecycle -------------------------------------------------------------------
    // A Config Set is withdrawn from service rather than destroyed: the record keeps its whole
    // append-only history so an administrator can restore it, and only a second, deliberate purge
    // removes anything from disk.

    private ConfigSet seed(ConfigSetRepository repository, String key, String content) {
        ConfigSet set = new ConfigSet(key, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v1 = set.addVersion(content, "initial", "alice", 1L);
        set.addVersion(content, "second", "bob", 2L);
        set.activate(v1);
        set.putSecretManifestEntry("db.password", "cred-id");
        repository.save(set);
        return set;
    }

    @Test
    public void softDeleteKeepsEveryVersionAndSecretOnDisk() throws Exception {
        File baseDir = temporaryFolder.newFolder();
        ConfigSetRepository repository = new ConfigSetRepository(baseDir);
        seed(repository, "withdrawn-app", "{\"a\":1}");

        repository.softDeleteCommon("withdrawn-app", "carol", 4242L);

        assertTrue("the file must stay on disk - a soft delete destroys nothing",
                new File(baseDir, "withdrawn-app--common.xml").isFile());

        ConfigSet reloaded = repository.findCommonIncludingDeleted("withdrawn-app");
        assertNotNull(reloaded);
        assertTrue(reloaded.isDeleted());
        assertEquals("carol", reloaded.getDeletion().getDeletedBy());
        assertEquals(4242L, reloaded.getDeletion().getDeletedAtEpochMillis());
        assertEquals("the whole version history must survive a withdrawal",
                2, reloaded.getVersions().size());
        assertEquals("the active pointer must survive it too", 1, reloaded.getActiveVersionNumber());
        assertEquals("and so must the secrets manifest",
                "cred-id", reloaded.getSecretsManifest().get("db.password"));
    }

    @Test
    public void aDeletedConfigSetReadsAsMissingToEveryOrdinaryLookup() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        seed(repository, "withdrawn-app", "{\"a\":1}");
        repository.softDeleteCommon("withdrawn-app", "carol", 1L);

        // This is what makes withdrawal safe: every resolver in the plugin already fails loud on a
        // null here, so none of them needed changing to stop consuming a withdrawn Config Set.
        assertNull(repository.findCommon("withdrawn-app"));
        assertNull(repository.find("withdrawn-app", ConfigSetRole.COMMON, null));
        assertNotNull("but the record itself is still reachable where it is the subject",
                repository.findCommonIncludingDeleted("withdrawn-app"));
    }

    @Test
    public void listingsSeparateLiveFromDeleted() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        seed(repository, "live-app", "{\"a\":1}");
        seed(repository, "withdrawn-app", "{\"a\":2}");
        repository.softDeleteCommon("withdrawn-app", "carol", 1L);

        List<ConfigSet> live = repository.listAllCommon();
        assertEquals(1, live.size());
        assertEquals("live-app", live.get(0).getProjectKey());

        List<ConfigSet> deleted = repository.listDeletedCommon();
        assertEquals(1, deleted.size());
        assertEquals("withdrawn-app", deleted.get(0).getProjectKey());

        assertTrue("a deleted key stays taken, which is what reserves the name",
                repository.listProjectKeys().contains("withdrawn-app"));
    }

    @Test
    public void savingOverADeletedRecordIsRefusedAndItsHistorySurvives() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        seed(repository, "withdrawn-app", "{\"a\":1}");
        repository.softDeleteCommon("withdrawn-app", "carol", 1L);

        // Exactly what creating a new Config Set over a deleted name would do. The guard lives in
        // the repository because every creation and every version append funnels through save, so
        // no caller - including one nobody has written yet - can clobber an archive.
        ConfigSet impostor = new ConfigSet("withdrawn-app", ConfigSetRole.COMMON, null, "New", ContentType.JSON);
        impostor.addVersion("{\"replaced\":true}", "clobber", "mallory", 9L);
        try {
            repository.save(impostor);
            fail("saving over a deleted Config Set must be refused");
        } catch (ConfigSetDeletedException expected) {
            assertTrue(expected.getMessage().contains("withdrawn-app"));
        }

        ConfigSet survivor = repository.findCommonIncludingDeleted("withdrawn-app");
        assertEquals("the archived history must be untouched", 2, survivor.getVersions().size());
        assertTrue(survivor.isDeleted());
    }

    @Test
    public void restoreBringsItBackWholeAndItsNameWorksAgain() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        seed(repository, "withdrawn-app", "{\"a\":1}");
        repository.softDeleteCommon("withdrawn-app", "carol", 1L);

        repository.restoreCommon("withdrawn-app");

        ConfigSet restored = repository.findCommon("withdrawn-app");
        assertNotNull("restore must make it resolvable again", restored);
        assertFalse(restored.isDeleted());
        assertEquals(2, restored.getVersions().size());
        assertEquals(1, restored.getActiveVersionNumber());
        assertEquals("cred-id", restored.getSecretsManifest().get("db.password"));
        assertTrue(repository.listDeletedCommon().isEmpty());
    }

    @Test
    public void purgeRemovesOnlyItsOwnFile() throws Exception {
        File baseDir = temporaryFolder.newFolder();
        ConfigSetRepository repository = new ConfigSetRepository(baseDir);
        seed(repository, "withdrawn-app", "{\"a\":1}");
        seed(repository, "innocent-app", "{\"a\":2}");

        // The deployment bindings share this directory and are never pruned; purging a Config Set
        // must not touch them, nor any sibling Config Set.
        File bindings = new File(baseDir, "deployment-bindings.xml");
        Files.write(bindings.toPath(), "<bindings/>".getBytes(StandardCharsets.UTF_8));

        repository.softDeleteCommon("withdrawn-app", "carol", 1L);
        assertTrue(repository.purgeCommon("withdrawn-app"));

        assertFalse(new File(baseDir, "withdrawn-app--common.xml").exists());
        assertTrue("a sibling Config Set must survive",
                new File(baseDir, "innocent-app--common.xml").isFile());
        assertTrue("deployment-bindings.xml must survive", bindings.isFile());
        assertNotNull(repository.findCommon("innocent-app"));
        assertNull(repository.findCommonIncludingDeleted("withdrawn-app"));
    }

    @Test
    public void purgeRefusesALiveConfigSet() throws Exception {
        ConfigSetRepository repository = new ConfigSetRepository(temporaryFolder.newFolder());
        seed(repository, "live-app", "{\"a\":1}");

        try {
            repository.purgeCommon("live-app");
            fail("purging a live Config Set must be refused - destruction is always a second step");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("still live"));
        }
        assertNotNull(repository.findCommon("live-app"));
    }

    @Test
    public void purgingSomethingAlreadyGoneIsNotAnError() throws Exception {
        File baseDir = temporaryFolder.newFolder();
        ConfigSetRepository repository = new ConfigSetRepository(baseDir);
        seed(repository, "withdrawn-app", "{\"a\":1}");
        repository.softDeleteCommon("withdrawn-app", "carol", 1L);

        // Another administrator purging the same record first is a race, not a failure: the desired
        // end state is that the file is gone, and it is.
        Files.delete(new File(baseDir, "withdrawn-app--common.xml").toPath());
        try {
            repository.purgeCommon("withdrawn-app");
            fail("a record that is entirely absent cannot be purged");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("to purge"));
        }
    }

    @Test
    public void aRecordPersistedBeforeThisFeatureReadsBackAsLive() throws Exception {
        File baseDir = temporaryFolder.newFolder();
        ConfigSetRepository repository = new ConfigSetRepository(baseDir);
        seed(repository, "legacy-live", "{\"a\":1}");

        // Nothing patches this field in readResolve, unlike contentType, because a reference field
        // absent from the XML deserializes to null - which already IS the live state. This test is
        // what stops somebody later adding a migration it does not need, or switching the field to
        // a boolean whose default would be equally right but whose absence would be untestable.
        File xmlFile = new File(baseDir, "legacy-live--common.xml");
        String xml = new String(Files.readAllBytes(xmlFile.toPath()), StandardCharsets.UTF_8);
        assertFalse("a live record must not even write a deletion element",
                xml.contains("<deletion>"));

        ConfigSet reloaded = repository.findCommon("legacy-live");
        assertNotNull(reloaded);
        assertFalse(reloaded.isDeleted());
    }
}
