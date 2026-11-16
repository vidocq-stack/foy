/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.foy.internal.session;

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

    /** BUG-20261116-02: a session id is a credential, so a failing listener's log line names only its prefix. */
    @Test
    void aFailingInvalidationListenerLogsOnlyThePrefixOfTheId() {
        var registry = new io.vidocq.foy.internal.listener.ListenerRegistry();
        registry.register(new jakarta.servlet.http.HttpSessionListener() {
            @Override public void sessionDestroyed(jakarta.servlet.http.HttpSessionEvent se) {
                throw new IllegalStateException("listener failed");
            }
        });
        registry.register(new jakarta.servlet.http.HttpSessionAttributeListener() {
            @Override public void attributeRemoved(jakarta.servlet.http.HttpSessionBindingEvent event) {
                throw new IllegalStateException("listener failed");
            }
        });
        manager.setListenerRegistry(registry);
        var s = manager.createNew();
        s.setAttribute("user", "alice");
        String id = s.getId();

        List<String> warnings;
        try (var log = io.vidocq.foy.internal.LogCapture.of(HttpSessionImpl.class.getName())) {
            s.invalidate();
            warnings = log.warnings();
        }

        assertEquals(2, warnings.size(), warnings::toString);
        warnings.forEach(w -> assertTrue(w.contains(id.substring(0, 8)), w));
        warnings.forEach(w -> assertFalse(w.contains(id), w));
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
        assertThrows(IllegalStateException.class, s::getAttributeNames);
        assertThrows(IllegalStateException.class, s::invalidate);
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
