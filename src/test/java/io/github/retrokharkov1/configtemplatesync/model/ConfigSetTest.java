package io.github.retrokharkov1.configtemplatesync.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConfigSetTest {

    @Test
    public void commonRoleRejectsEnvironment() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSet("proj", ConfigSetRole.COMMON, "dev", "Proj Common"));
    }

    @Test
    public void envRoleRequiresEnvironment() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSet("proj", ConfigSetRole.ENV, null, "Proj Dev"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSet("proj", ConfigSetRole.ENV, "  ", "Proj Dev"));
    }

    @Test
    public void storageKeyIsDeterministicByProjectKey() {
        ConfigSet common = new ConfigSet("sample-app", ConfigSetRole.COMMON, null, "Common");
        ConfigSet env = new ConfigSet("sample-app", ConfigSetRole.ENV, "dev", "Dev");
        assertEquals("sample-app--common", common.getStorageKey());
        assertEquals("sample-app--env--dev", env.getStorageKey());
    }

    @Test
    public void versionNumbersAreMonotonicAndNeverReused() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        int v1 = configSet.addVersion("{\"a\":1}", "initial", "alice", 1L);
        int v2 = configSet.addVersion("{\"a\":2}", "second", "alice", 2L);
        configSet.activate(v2);
        // rolling back to v1 must not renumber or reuse v1/v2
        configSet.activate(v1);
        int v3 = configSet.addVersion("{\"a\":3}", "third", "alice", 3L);
        assertEquals(1, v1);
        assertEquals(2, v2);
        assertEquals(3, v3);
    }

    @Test
    public void addVersionRejectsEmptyNote() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        assertThrows(IllegalArgumentException.class,
                () -> configSet.addVersion("{}", "", "alice", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> configSet.addVersion("{}", null, "alice", 1L));
    }

    @Test
    public void activateEnforcesExactlyOneActiveVersion() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        int v1 = configSet.addVersion("{\"a\":1}", "v1", "alice", 1L);
        int v2 = configSet.addVersion("{\"a\":2}", "v2", "alice", 2L);

        configSet.activate(v1);
        assertEquals(v1, configSet.getActiveVersionNumber());

        configSet.activate(v2);
        assertEquals(v2, configSet.getActiveVersionNumber());
        assertTrue(configSet.getActiveVersionNumber() != v1);
    }

    @Test
    public void noActiveVersionByDefault() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        assertEquals(0, configSet.getActiveVersionNumber());
        assertNull(configSet.getActiveVersion());
    }

    @Test
    public void rejectsSaveWhereSecretPathHoldsRealValue() {
        // OQ-1: structural rejection — a manifest-declared secret path must hold the literal
        // placeholder, never a real value.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        configSet.putSecretManifestEntry("ConnectionStrings.Default", "my-credential-id");

        assertThrows(IllegalArgumentException.class, () -> configSet.addVersion(
                "{\"ConnectionStrings\":{\"Default\":\"Server=prod;Password=hunter2\"}}",
                "oops, pasted a real secret", "alice", 1L));
    }

    @Test
    public void acceptsSaveWhereSecretPathHoldsPlaceholder() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        configSet.putSecretManifestEntry("ConnectionStrings.Default", "my-credential-id");

        int version = configSet.addVersion(
                "{\"ConnectionStrings\":{\"Default\":\"" + SecretPlaceholder.VALUE + "\"}}",
                "correct placeholder", "alice", 1L);
        assertEquals(1, version);
    }

    @Test
    public void allowsSameCredentialIdReusedAcrossManifestEntries() {
        // OQ-5: no uniqueness constraint on credential ID reuse.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        configSet.putSecretManifestEntry("A.Secret", "shared-credential-id");
        configSet.putSecretManifestEntry("B.Secret", "shared-credential-id");
        int version = configSet.addVersion(
                "{\"A\":{\"Secret\":\"" + SecretPlaceholder.VALUE + "\"},"
                        + "\"B\":{\"Secret\":\"" + SecretPlaceholder.VALUE + "\"}}",
                "shared credential reused", "alice", 1L);
        assertEquals(1, version);
    }

    @Test
    public void removeSecretManifestEntry_removesABoundPathAndReturnsTrue() {
        // OQ-1 CRUD completion: a bound secret path can be unbound again, no longer appearing in
        // getSecretsManifest(), without disturbing any other manifest entry.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        configSet.putSecretManifestEntry("A.Secret", "cred-a");
        configSet.putSecretManifestEntry("B.Secret", "cred-b");

        boolean removed = configSet.removeSecretManifestEntry("A.Secret");

        assertTrue(removed);
        assertFalse("removed path must no longer appear in the manifest",
                configSet.getSecretsManifest().containsKey("A.Secret"));
        assertEquals("unrelated manifest entries must be untouched",
                "cred-b", configSet.getSecretsManifest().get("B.Secret"));
    }

    @Test
    public void removeSecretManifestEntry_returnsFalseWhenPathWasNeverBound() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        assertFalse(configSet.removeSecretManifestEntry("Never.Bound"));
    }

    @Test
    public void addVersionWithBaseChainPersistsAndIsRetrievable() {
        // FR-51: an env Config Set's declared base chain is part of that version's own record.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.ENV, "dev", "Dev");
        List<BaseConfigReference> chain = Arrays.asList(
                BaseConfigReference.active("team-a-common"),
                BaseConfigReference.pinned("team-b-common", 3));

        int version = configSet.addVersion("{\"a\":1}", "multi-base", "alice", 1L, chain);

        assertEquals(chain, configSet.getVersion(version).getBaseChain());
    }

    @Test
    public void addVersionRejectsNonEmptyBaseChainOnCommonRole() {
        // FR-53: a COMMON-role Config Set is always the root of a chain, never a chain member itself.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("team-a-common"));

        assertThrows(IllegalArgumentException.class,
                () -> configSet.addVersion("{}", "should be rejected", "alice", 1L, chain));
    }

    @Test
    public void fourArgAddVersionYieldsEmptyBaseChain() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common");
        int version = configSet.addVersion("{\"a\":1}", "no chain", "alice", 1L);
        assertTrue(configSet.getVersion(version).getBaseChain().isEmpty());
    }
}
