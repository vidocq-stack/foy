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

import io.vidocq.foy.spi.session.SessionStore;

import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private SessionManager manager;
    private InMemorySessionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        manager = new SessionManager(store, new VidocqServletContext("/"), 1800);
    }

    @Test
    void createNewStoresSessionWithUniqueId() {
        var s1 = manager.createNew();
        var s2 = manager.createNew();
        assertNotEquals(s1.getId(), s2.getId());
        assertEquals(2, store.size());
    }

    @Test
    void findReturnsStoredSessionAndUpdatesLastAccess() throws InterruptedException {
        var s = manager.createNew();
        long initial = s.getLastAccessedTime();
        Thread.sleep(5);
        var found = manager.find(s.getId());
        assertNotNull(found);
        assertEquals(s.getId(), found.getId());
        assertTrue(found.getLastAccessedTime() >= initial);
    }

    @Test
    void findReturnsNullForUnknownId() {
        assertNull(manager.find("ghost"));
    }

    @Test
    void findReturnsNullForExpiredSession() throws InterruptedException {
        var shortLived = new SessionManager(store, new VidocqServletContext("/"), 1);
        var s = shortLived.createNew();
        Thread.sleep(1500);
        assertNull(shortLived.find(s.getId()));
        assertEquals(0, store.size());
    }

    @Test
    void invalidateRemovesFromStore() {
        var s = manager.createNew();
        s.invalidate();
        assertNull(manager.find(s.getId()));
        assertEquals(0, store.size());
    }

    @Test
    void findReturnsNullForNullId() {
        assertNull(manager.find(null));
    }

    @Test
    void sessionIdIsHex32Chars() {
        var s = manager.createNew();
        assertEquals(32, s.getId().length());
        assertTrue(s.getId().matches("[0-9a-f]{32}"));
    }
}
