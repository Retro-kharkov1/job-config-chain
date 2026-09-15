package io.github.retrokharkov1.configtemplatesync.model;

import org.junit.Test;

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
                () -> new ConfigSet("proj", ConfigSetRole.COMMON, "dev", "Proj Common", ContentType.JSON));
    }

    // envRoleRequiresEnvironment DELETED (2026-09-14 full removal of ConfigSetRole.ENV): this test
    // asserted validation rules that only ever applied to the ENV role, which no longer exists as an
    // enum value — the scenario is not merely unreachable, it does not compile. No replacement test
    // needed: commonRoleRejectsEnvironment above already fully covers the surviving (COMMON-only)
    // half of the constructor's environment-field validation.

    @Test
    public void storageKeyIsDeterministicByProjectKey() {
        // Rewritten (2026-09-14): only COMMON survives as a ConfigSetRole, so the storage key is
        // always "<projectKey>--common" — the old env-role half of this test no longer compiles.
        ConfigSet common = new ConfigSet("sample-app", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        assertEquals("sample-app--common", common.getStorageKey());
    }

    @Test
    public void versionNumbersAreMonotonicAndNeverReused() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
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
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        assertThrows(IllegalArgumentException.class,
                () -> configSet.addVersion("{}", "", "alice", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> configSet.addVersion("{}", null, "alice", 1L));
    }

    @Test
    public void activateEnforcesExactlyOneActiveVersion() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
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
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        assertEquals(0, configSet.getActiveVersionNumber());
        assertNull(configSet.getActiveVersion());
    }

    @Test
    public void rejectsSaveWhereSecretPathHoldsRealValue() {
        // OQ-1: structural rejection — a manifest-declared secret path must hold the literal
        // placeholder, never a real value.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        configSet.putSecretManifestEntry("ConnectionStrings.Default", "my-credential-id");

        assertThrows(IllegalArgumentException.class, () -> configSet.addVersion(
                "{\"ConnectionStrings\":{\"Default\":\"Server=prod;Password=hunter2\"}}",
                "oops, pasted a real secret", "alice", 1L));
    }

    @Test
    public void acceptsSaveWhereSecretPathHoldsPlaceholder() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        configSet.putSecretManifestEntry("ConnectionStrings.Default", "my-credential-id");

        int version = configSet.addVersion(
                "{\"ConnectionStrings\":{\"Default\":\"" + SecretPlaceholder.VALUE + "\"}}",
                "correct placeholder", "alice", 1L);
        assertEquals(1, version);
    }

    @Test
    public void allowsSameCredentialIdReusedAcrossManifestEntries() {
        // OQ-5: no uniqueness constraint on credential ID reuse.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
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
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
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
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        assertFalse(configSet.removeSecretManifestEntry("Never.Bound"));
    }

    // addVersionWithBaseChainPersistsAndIsRetrievable DELETED (2026-09-14 full removal of
    // ConfigSetRole.ENV): this test proved a base chain persists on an ENV-role Config Set's own
    // version. With only COMMON surviving as a role, and addVersionRejectsNonEmptyBaseChainOnCommonRole
    // below unconditionally rejecting a non-empty baseChain on the only role left, ConfigSet-level
    // baseChain persistence-with-content is now unreachable production code — nothing can construct
    // a ConfigSet version that both has a role and carries a non-empty baseChain. Equivalent coverage
    // for "a version's own declared base chain persists and is retrievable" now lives on the
    // job-scoped sibling, JobConfigTemplateVersion (see JobConfigTemplateProperty.addVersion's own
    // callers in ConfigTemplatesJobActionTest, e.g. versionHistory_isAppendOnly_...), which is the
    // model's real base-chain-bearing type going forward.

    @Test
    public void addVersionRejectsNonEmptyBaseChainOnCommonRole() {
        // FR-53: a COMMON-role Config Set is always the root of a chain, never a chain member itself.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("team-a-common"));

        assertThrows(IllegalArgumentException.class,
                () -> configSet.addVersion("{}", "should be rejected", "alice", 1L, chain));
    }

    @Test
    public void fourArgAddVersionYieldsEmptyBaseChain() {
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int version = configSet.addVersion("{\"a\":1}", "no chain", "alice", 1L);
        assertTrue(configSet.getVersion(version).getBaseChain().isEmpty());
    }

    // addVersionWithExplicitlyStandalonePersistsAndIsRetrievable DELETED (2026-09-14 full removal
    // of ConfigSetRole.ENV): explicitlyStandalone=true is now UNREACHABLE production code for
    // ConfigSet — the only surviving role, COMMON, unconditionally rejects it
    // (addVersionRejectsExplicitlyStandaloneOnCommonRole below already covers that rejection). No
    // replacement needed: this flag's own concept has no analogue on the job-scoped model either
    // (JobConfigTemplateVersion deliberately has no explicitlyStandalone field at all — see that
    // class's own javadoc).

    @Test
    public void sixArgAddVersionWithoutExplicitFlagDefaultsFalse() {
        // Rewritten (2026-09-14): uses COMMON instead of the now-removed ENV role — an empty
        // baseChain is valid on either role, so this still exercises the same "six-arg addVersion
        // without an explicit flag defaults explicitlyStandalone to false" behavior.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int version = configSet.addVersion("{\"a\":1}", "no flag", "alice", 1L, Collections.emptyList());
        assertFalse(configSet.getVersion(version).isExplicitlyStandalone());
    }

    @Test
    public void addVersionRejectsExplicitlyStandaloneOnCommonRole() {
        // FR-86: mirrors FR-53's own restriction for the new flag.
        ConfigSet configSet = new ConfigSet("proj", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        assertThrows(IllegalArgumentException.class,
                () -> configSet.addVersion("{}", "should be rejected", "alice", 1L,
                        Collections.emptyList(), true));
    }

    // addVersionRejectsExplicitlyStandaloneContradictingNonEmptyBaseChain DELETED (2026-09-14 full
    // removal of ConfigSetRole.ENV): this test proved the FR-86/87 explicitlyStandalone-vs-baseChain
    // contradiction check specifically on an ENV-role Config Set. With only COMMON surviving, a
    // non-empty baseChain on COMMON is already rejected earlier, for a DIFFERENT reason (FR-53, "a
    // COMMON-role Config Set must not declare a base chain of its own" —
    // addVersionRejectsNonEmptyBaseChainOnCommonRole above), before the contradiction check this
    // test targeted would ever run. That contradiction-check branch is therefore unreachable dead
    // code now that ENV is gone; no replacement test is meaningful for it.
}
