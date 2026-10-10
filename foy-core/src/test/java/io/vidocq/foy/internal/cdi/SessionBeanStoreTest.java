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
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBean;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.vidocq.foy.internal.cdi.CdiFakes.creationalContext;
import static org.junit.jupiter.api.Assertions.*;

class SessionBeanStoreTest {

    private final SessionManager manager = CdiFakes.manager();

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void theStoreIsCreatedOnFirstUseAndCanBeDetached() {
        HttpSessionImpl session = manager.createNew();
        assertNull(SessionBeanStore.existing(session));
        SessionBeanStore store = SessionBeanStore.of(session);
        assertSame(store, SessionBeanStore.of(session));
        assertSame(store, SessionBeanStore.existing(session));
        assertSame(store, SessionBeanStore.take(session));
        assertNull(SessionBeanStore.existing(session));
        assertNull(SessionBeanStore.take(session));
    }

    @Test
    void destroyAllRunsInReverseCreationOrder() {
        var order = new CopyOnWriteArrayList<Object>();
        var first = new FakeBean("first", order);
        var second = new FakeBean("second", order);
        SessionBeanStore store = SessionBeanStore.of(manager.createNew());
        Object a = store.getOrCreate(first, creationalContext());
        Object b = store.getOrCreate(second, creationalContext());
        store.destroyAll();
        assertEquals(List.of(b, a), order);
        assertTrue(store.isEmpty());
    }

    @Test
    void destroyAllLogsAFailureAndGoesOn() {
        var order = new CopyOnWriteArrayList<Object>();
        var failing = new FakeBean("failing", order);
        failing.destroyFailure = new IllegalStateException("boom");
        var healthy = new FakeBean("healthy", order);
        SessionBeanStore store = SessionBeanStore.of(manager.createNew());
        store.getOrCreate(healthy, creationalContext());
        store.getOrCreate(failing, creationalContext());
        try (var log = LogCapture.of(SessionBeanStore.class.getName())) {
            store.destroyAll();
            assertEquals(1, log.warnings().size(), log.warnings()::toString);
            assertTrue(log.warnings().getFirst().contains("FakeBean[failing]"), log.warnings()::toString);
        }
        assertEquals(List.of("failing#1", "healthy#1"), order);
    }

    @Test
    void aDetachedStoreIsNotRecreated() {
        HttpSessionImpl session = manager.createNew();
        SessionBeanStore.of(session);
        SessionBeanStore.take(session).destroyAll();
        try (var log = LogCapture.of(SessionBeanStore.class.getName())) {
            assertThrows(ContextNotActiveException.class, () -> SessionBeanStore.of(session));
            assertEquals(1, log.warnings().size(), log.warnings()::toString);
        }
        assertNull(SessionBeanStore.existing(session));
        assertNull(SessionBeanStore.take(session));
    }
}
