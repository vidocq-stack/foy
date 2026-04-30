package io.vidocq.foy.internal.session;

import io.vidocq.foy.spi.session.SessionStore;

import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HttpSessionImplTest {

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
    }

    @Test
    void newSessionFlagsAsNew() {
        var s = manager.createNew();
        assertTrue(s.isNew());
    }

    @Test
    void accessClearsNewFlag() {
        var s = manager.createNew();
        manager.find(s.getId());
        assertFalse(s.isNew());
    }

    @Test
    void attributesRoundTrip() {
        var s = manager.createNew();
        s.setAttribute("user", "alice");
        assertEquals("alice", s.getAttribute("user"));
    }

    @Test
    void nullValueRemovesAttribute() {
        var s = manager.createNew();
        s.setAttribute("k", "v");
        s.setAttribute("k", null);
        assertNull(s.getAttribute("k"));
    }

    @Test
    void getAttributeNamesListsAllKeys() {
        var s = manager.createNew();
        s.setAttribute("a", 1);
        s.setAttribute("b", 2);
        List<String> names = Collections.list(s.getAttributeNames());
        var expected = new ArrayList<>(List.of("a", "b"));
        expected.sort(String::compareTo);
        names.sort(String::compareTo);
        assertEquals(expected, names);
    }

    @Test
    void invalidateBlocksSubsequentAccess() {
        var s = manager.createNew();
        s.invalidate();
        assertThrows(IllegalStateException.class, () -> s.getAttribute("k"));
        assertThrows(IllegalStateException.class, () -> s.getAttributeNames());
        assertThrows(IllegalStateException.class, () -> s.invalidate());
    }

    @Test
    void creationTimeIsStable() {
        var s = manager.createNew();
        long ct = s.getCreationTime();
        assertEquals(ct, s.getCreationTime());
    }

    @Test
    void maxInactiveIntervalIsMutable() {
        var s = manager.createNew();
        s.setMaxInactiveInterval(60);
        assertEquals(60, s.getMaxInactiveInterval());
    }
}
