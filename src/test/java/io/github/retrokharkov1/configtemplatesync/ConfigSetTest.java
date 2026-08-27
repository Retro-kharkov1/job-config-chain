package io.github.retrokharkov1.configtemplatesync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure in-memory tests of the {@link ConfigSet} save/activate invariant — no Jenkins runtime
 * needed.
 */
class ConfigSetTest {

    @Test
    void saveWithoutActivateLeavesOldVersionActive() {
        ConfigSet cs = new ConfigSet("test", "Test");
        int v1 = cs.save("{\"A\":\"1\"}", "seed", "alice", true);
        assertEquals(1, v1);
        assertTrue(cs.getVersion(v1).isActive());

        int v2 = cs.save("{\"A\":\"2\"}", "second, not activated", "bob", false);
        assertEquals(2, v2);
        assertFalse(cs.getVersion(v2).isActive(), "v2 must stay inactive when activateOnSave=false");
        assertTrue(cs.getVersion(v1).isActive(), "v1 must remain the active version");
        assertEquals(cs.getVersion(v1), cs.getActiveVersion());
    }

    @Test
    void saveWithActivateFlipsCorrectly() {
        ConfigSet cs = new ConfigSet("test", "Test");
        int v1 = cs.save("{\"A\":\"1\"}", "seed", "alice", true);
        int v2 = cs.save("{\"A\":\"2\"}", "activate this one", "bob", true);

        assertFalse(cs.getVersion(v1).isActive());
        assertTrue(cs.getVersion(v2).isActive());
        assertEquals(v2, cs.getActiveVersion().getVersionNumber());
    }

    @Test
    void activateOldVersionFlipsAndDoesNotCreateNewVersion() {
        ConfigSet cs = new ConfigSet("test", "Test");
        int v1 = cs.save("{\"A\":\"1\"}", "seed", "alice", true);
        int v2 = cs.save("{\"A\":\"2\"}", "next", "bob", true);
        assertEquals(2, cs.listVersionsNewestFirst().size());

        cs.activate(v1); // rollback

        assertTrue(cs.getVersion(v1).isActive());
        assertFalse(cs.getVersion(v2).isActive());
        assertEquals(2, cs.listVersionsNewestFirst().size(), "activate must not mint a new version");
        assertEquals(v1, cs.getActiveVersion().getVersionNumber());
    }

    @Test
    void versionNumbersAreMaxPlusOneAndNeverReused() {
        ConfigSet cs = new ConfigSet("test", "Test");
        int v1 = cs.save("{\"A\":\"1\"}", null, "alice", true);
        int v2 = cs.save("{\"A\":\"2\"}", null, "alice", true);
        cs.activate(v1); // roll back to v1 - must not affect the next save's numbering
        int v3 = cs.save("{\"A\":\"3\"}", null, "alice", true);

        assertEquals(1, v1);
        assertEquals(2, v2);
        assertEquals(3, v3, "next version number must be max(existing)+1, never reused, even after rollback");
    }

    @Test
    void activateUnknownVersionThrows() {
        ConfigSet cs = new ConfigSet("test", "Test");
        cs.save("{\"A\":\"1\"}", null, "alice", true);
        assertThrows(IllegalArgumentException.class, () -> cs.activate(99));
    }

    @Test
    void freshConfigSetHasNoActiveVersion() {
        ConfigSet cs = new ConfigSet("test", "Test");
        assertNull(cs.getActiveVersion());
        cs.save("{\"A\":\"1\"}", null, "alice", false);
        assertNull(cs.getActiveVersion(), "save without activateOnSave on an empty set stays inactive");
    }

    @Test
    void concurrentSaveActivateSequenceKeepsExactlyOneActive() {
        ConfigSet cs = new ConfigSet("test", "Test");
        cs.save("{\"A\":\"1\"}", null, "a", true);
        cs.save("{\"A\":\"2\"}", null, "b", false);
        cs.save("{\"A\":\"3\"}", null, "c", true);
        cs.activate(1);
        cs.save("{\"A\":\"4\"}", null, "d", true);
        cs.activate(2);

        long activeCount = cs.listVersionsNewestFirst().stream().filter(v -> v.isActive()).count();
        assertEquals(1, activeCount, "exactly one active version must hold after any save/activate sequence");
        assertEquals(2, cs.getActiveVersion().getVersionNumber());
    }
}
