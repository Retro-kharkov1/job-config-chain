package io.jenkins.plugins.jobconfigchain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResolvedBaseVersionTest {

    @Test
    public void constructorRejectsNonPositiveVersionNumber() {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBaseVersion("team-a-common", 0));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBaseVersion("team-a-common", -1));
    }

    @Test
    public void constructorRejectsEmptyProjectKey() {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBaseVersion("", 1));
        assertThrows(NullPointerException.class, () -> new ResolvedBaseVersion(null, 1));
    }

    @Test
    public void equalsAndHashCodeAreValueBased() {
        ResolvedBaseVersion a = new ResolvedBaseVersion("team-a-common", 7);
        ResolvedBaseVersion b = new ResolvedBaseVersion("team-a-common", 7);
        ResolvedBaseVersion c = new ResolvedBaseVersion("team-a-common", 8);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void toStringIsReadable() {
        assertEquals("team-a-common@v7", new ResolvedBaseVersion("team-a-common", 7).toString());
    }
}
