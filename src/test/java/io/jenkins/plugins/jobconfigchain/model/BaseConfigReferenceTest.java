package io.jenkins.plugins.jobconfigchain.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class BaseConfigReferenceTest {

    @Test
    public void activeFactoryProducesActivePinModeWithZeroPinnedVersion() {
        BaseConfigReference ref = BaseConfigReference.active("team-a-common");
        assertEquals("team-a-common", ref.getProjectKey());
        assertEquals(PinMode.ACTIVE, ref.getPinMode());
        assertEquals(0, ref.getPinnedVersionNumber());
    }

    @Test
    public void pinnedFactoryRequiresPositiveVersionNumber() {
        BaseConfigReference ref = BaseConfigReference.pinned("team-a-common", 3);
        assertEquals(PinMode.PINNED, ref.getPinMode());
        assertEquals(3, ref.getPinnedVersionNumber());

        assertThrows(IllegalArgumentException.class, () -> BaseConfigReference.pinned("team-a-common", 0));
        assertThrows(IllegalArgumentException.class, () -> BaseConfigReference.pinned("team-a-common", -1));
    }

    @Test
    public void constructorRejectsEmptyProjectKey() {
        assertThrows(IllegalArgumentException.class, () -> new BaseConfigReference("", PinMode.ACTIVE, 0));
        assertThrows(IllegalArgumentException.class, () -> new BaseConfigReference("  ", PinMode.ACTIVE, 0));
        assertThrows(NullPointerException.class, () -> new BaseConfigReference(null, PinMode.ACTIVE, 0));
    }

    @Test
    public void activeReferenceNormalizesPinnedVersionNumberToZero() {
        BaseConfigReference ref = new BaseConfigReference("team-a-common", PinMode.ACTIVE, 99);
        assertEquals("stray pinnedVersionNumber must be normalized away for ACTIVE", 0, ref.getPinnedVersionNumber());
    }

    @Test
    public void equalsAndHashCodeIgnoreNormalizedNoise() {
        BaseConfigReference a = new BaseConfigReference("team-a-common", PinMode.ACTIVE, 42);
        BaseConfigReference b = BaseConfigReference.active("team-a-common");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        BaseConfigReference pinned1 = BaseConfigReference.pinned("team-a-common", 3);
        BaseConfigReference pinned2 = BaseConfigReference.pinned("team-a-common", 4);
        assertNotEquals(pinned1, pinned2);
    }

    @Test
    public void toStringDistinguishesActiveAndPinned() {
        assertEquals("team-a-common@ACTIVE", BaseConfigReference.active("team-a-common").toString());
        assertEquals("team-a-common@PINNED(v3)", BaseConfigReference.pinned("team-a-common", 3).toString());
    }
}
