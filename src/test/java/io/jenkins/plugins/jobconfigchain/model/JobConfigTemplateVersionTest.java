package io.jenkins.plugins.jobconfigchain.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JobConfigTemplateVersionTest {

    @Test
    public void rejectsEmptyOrNullNote() {
        assertThrows(IllegalArgumentException.class,
                () -> new JobConfigTemplateVersion(1, "{}", "", "alice", 1L, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new JobConfigTemplateVersion(1, "{}", null, "alice", 1L, Collections.emptyList()));
    }

    @Test
    public void emptyBaseChainIsValid_andNeverNull() {
        JobConfigTemplateVersion v = new JobConfigTemplateVersion(1, "{}", "note", "alice", 1L,
                Collections.emptyList());
        assertTrue("empty baseChain must round-trip as empty, never null", v.getBaseChain().isEmpty());
    }

    @Test
    public void nonEmptyBaseChainPersistsAndIsRetrievable() {
        List<BaseConfigReference> chain = Arrays.asList(
                BaseConfigReference.active("team-a-common"),
                BaseConfigReference.pinned("team-b-common", 3));
        JobConfigTemplateVersion v = new JobConfigTemplateVersion(1, "{}", "note", "alice", 1L, chain);
        assertEquals(chain, v.getBaseChain());
    }

    @Test
    public void rejectsNullBaseChain() {
        assertThrows(NullPointerException.class,
                () -> new JobConfigTemplateVersion(1, "{}", "note", "alice", 1L, null));
    }

    @Test
    public void rejectsNullContentOrAuthor() {
        assertThrows(NullPointerException.class,
                () -> new JobConfigTemplateVersion(1, null, "note", "alice", 1L, Collections.emptyList()));
        assertThrows(NullPointerException.class,
                () -> new JobConfigTemplateVersion(1, "{}", "note", null, 1L, Collections.emptyList()));
    }

    @Test
    public void fieldsRoundTripVerbatim() {
        List<BaseConfigReference> chain = Collections.singletonList(BaseConfigReference.active("proj"));
        JobConfigTemplateVersion v = new JobConfigTemplateVersion(7, "{\"a\":1}", "note text", "bob", 123L, chain);
        assertEquals(7, v.getVersionNumber());
        assertEquals("{\"a\":1}", v.getContentJson());
        assertEquals("note text", v.getNote());
        assertEquals("bob", v.getAuthor());
        assertEquals(123L, v.getTimestampEpochMillis());
        assertEquals(chain, v.getBaseChain());
    }
}
