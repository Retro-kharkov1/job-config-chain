package io.jenkins.plugins.jobconfigchain.ui;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Plain JUnit unit tests for {@link JobConfigTemplateProperty} — no {@code JenkinsRule} needed for
 * pure model/property logic, matching this project's own testing convention (see
 * {@code ConfigSetTest}).
 */
public class JobConfigTemplatePropertyTest {

    @Test
    public void nothingConfigured_isAValidEmptyState() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        assertTrue(property.getVersions().isEmpty());
        assertEquals(0, property.getActiveVersionNumber());
        assertNull(property.getActiveVersion());
        assertNull("contentType is null until the first version exists", property.getContentType());
        assertTrue(property.getSecretsManifest().isEmpty());
    }

    @Test
    public void versionNumbersAreMonotonicAndNeverReused() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v1 = property.addVersion("{\"a\":1}", "first", "alice", 1L, Collections.emptyList(), null);
        int v2 = property.addVersion("{\"a\":2}", "second", "alice", 2L, Collections.emptyList(), null);
        property.activate(v2);
        property.activate(v1); // rollback must not renumber/reuse
        int v3 = property.addVersion("{\"a\":3}", "third", "alice", 3L, Collections.emptyList(), null);
        assertEquals(1, v1);
        assertEquals(2, v2);
        assertEquals(3, v3);
    }

    @Test
    public void activate_flipsActivePointerAndValidatesExistence() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v1 = property.addVersion("{}", "v1", "alice", 1L, Collections.emptyList(), null);

        property.activate(v1);
        assertEquals(v1, property.getActiveVersionNumber());
        assertEquals(v1, property.getActiveVersion().getVersionNumber());

        assertThrows(IllegalArgumentException.class, () -> property.activate(999));
    }

    @Test
    public void contentTypeLocksAfterFirstSave_evenWithEmptyChain_thePickerPath() {
        // The "picker" path: no base chain at all (nothing to resolve a type from), so the first
        // save's own contentType parameter governs — and locks thereafter.
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        assertNull(property.getContentType());

        property.addVersion("<a/>", "first, XML picked", "alice", 1L, Collections.emptyList(), "XML");
        assertEquals(ContentType.XML, property.getContentType());

        // A second save's contentType parameter must be ignored — the committed type is immutable.
        property.addVersion("<b/>", "second, ignored param", "alice", 2L, Collections.emptyList(), "YAML");
        assertEquals("contentType must remain locked to the first save's value",
                ContentType.XML, property.getContentType());
    }

    @Test
    public void contentTypeDefaultsToJsonWhenAbsentOnFirstSave() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{}", "first, no type param", "alice", 1L, Collections.emptyList(), null);
        assertEquals(ContentType.JSON, property.getContentType());
    }

    @Test
    public void addVersionWithBaseChainPersistsAndIsRetrievable() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        List<BaseConfigReference> chain = Arrays.asList(
                BaseConfigReference.active("team-a-common"),
                BaseConfigReference.pinned("team-b-common", 3));

        int version = property.addVersion("{}", "override-only-becomes-chain", "alice", 1L, chain, null);

        assertEquals(chain, property.getVersion(version).getBaseChain());
    }

    @Test
    public void emptyBaseChain_isValidInBothNothingConfiguredAndOverrideOnlyStates() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        // "nothing configured" — no chain, no manifest, first save.
        int v1 = property.addVersion("{}", "nothing configured", "alice", 1L, Collections.emptyList(), null);
        assertTrue(property.getVersion(v1).getBaseChain().isEmpty());

        // "override-only" — still an empty chain, but real content this time; empty baseChain is
        // unambiguously "no bases" for a job version (no self-reference default, unlike an env
        // Config Set) — see JobConfigTemplateVersion's own javadoc.
        int v2 = property.addVersion("{\"x\":1}", "override-only content", "alice", 2L,
                Collections.emptyList(), null);
        assertTrue(property.getVersion(v2).getBaseChain().isEmpty());
        assertEquals("{\"x\":1}", property.getVersion(v2).getContentJson());
    }

    @Test
    public void secretsManifest_putAndRemove() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.putSecretManifestEntry("db.password", "cred-a");
        assertEquals("cred-a", property.getSecretsManifest().get("db.password"));

        boolean removed = property.removeSecretManifestEntry("db.password");
        assertTrue(removed);
        assertFalse(property.getSecretsManifest().containsKey("db.password"));

        assertFalse("removing a never-bound path returns false",
                property.removeSecretManifestEntry("never.bound"));
    }

    @Test
    public void secretsManifest_isNotVersioned() {
        // Adding/removing a manifest entry must not create a new version and must not affect
        // already-saved versions' own content.
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.addVersion("{}", "v1", "alice", 1L, Collections.emptyList(), null);
        property.putSecretManifestEntry("a.b", "cred-1");
        assertEquals("adding a manifest entry must not append a new version",
                1, property.getVersions().size());
        property.removeSecretManifestEntry("a.b");
        assertEquals("removing a manifest entry must not append a new version",
                1, property.getVersions().size());
    }

    @Test
    public void secretPlaceholderEnforcement_rejectsRealValue_acceptsPlaceholder() {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        property.putSecretManifestEntry("ConnectionStrings.Default", "my-credential-id");

        assertThrows("a real value at a manifest-declared path must be rejected",
                IllegalArgumentException.class,
                () -> property.addVersion(
                        "{\"ConnectionStrings\":{\"Default\":\"Server=prod;Password=hunter2\"}}",
                        "oops, real secret", "alice", 1L, Collections.emptyList(), null));

        int version = property.addVersion(
                "{\"ConnectionStrings\":{\"Default\":\"" + SecretPlaceholder.VALUE + "\"}}",
                "correct placeholder", "alice", 1L, Collections.emptyList(), null);
        assertEquals(1, version);
    }
}
