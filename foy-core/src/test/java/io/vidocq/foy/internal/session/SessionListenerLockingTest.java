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
import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-20261009-14: session listeners run outside the manager's and the session's locks, so a
 * listener that waits for another thread working on sessions cannot deadlock it, while the
 * notification order (created before destroyed, id changed before destroyed) is kept.
 */
class SessionListenerLockingTest {

    private static void awaitWaiting(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(t.isAlive(), "thread ended early");
            assertTrue(System.nanoTime() < deadline, "thread never blocked");
            Thread.onSpinWait();
        }
    }

    /** An id-change listener waits for another thread that invalidates the same session. */
    @Test
    void anIdChangeListenerMayWaitForAnInvalidationOfItsSession() throws Exception {
        var manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
        var registry = new ListenerRegistry();
        List<String> events = new CopyOnWriteArrayList<>();
        var invalidated = new AtomicReference<Boolean>();
        registry.register(new HttpSessionIdListener() {
            @Override public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
                events.add("idChanged");
                var other = Thread.ofVirtual().start(() -> {
                    event.getSession().invalidate();
                    events.add("invalidate returned");
                });
                try {
                    invalidated.set(other.join(java.time.Duration.ofSeconds(2)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                events.add("idChanged end");
            }
        });
        registry.register(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) { events.add("destroyed"); }
        });
        manager.setListenerRegistry(registry);
        var session = manager.createNew();
        manager.changeSessionId(session);
        assertTrue(invalidated.get(), "invalidate() blocked behind the id-change listener");
        // The listener never sees a destroyed session: sessionDestroyed comes after it returned.
        assertEquals(List.of("idChanged", "invalidate returned", "idChanged end", "destroyed"), events);
        assertTrue(session.isInvalidated());
        assertTrue(manager.store().get(session.getId()).isEmpty());
    }

    /**
     * A sessionCreated listener waits for another request that creates a session while the
     * application is being undeployed: the other request is refused at once instead of queueing
     * behind the undeploy, which itself waits for the listener.
     */
    @Test
    void aCreationListenerMayWaitForAnotherCreationDuringUndeploy() throws Exception {
        var manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
        var registry = new ListenerRegistry();
        List<String> events = new CopyOnWriteArrayList<>();
        var otherOutcome = new AtomicReference<String>();
        var closer = new AtomicReference<Thread>();
        registry.register(new HttpSessionListener() {
            @Override public void sessionCreated(HttpSessionEvent se) {
                if (closer.get() != null) return;
                events.add("created");
                var c = Thread.ofVirtual().start(manager::close);
                closer.set(c);
                awaitWaiting(c);
                var other = Thread.ofVirtual().start(() -> {
                    try {
                        manager.createNew();
                        otherOutcome.compareAndSet(null, "created");
                    } catch (IllegalStateException e) {
                        otherOutcome.compareAndSet(null, "refused");
                    }
                });
                try {
                    if (!other.join(java.time.Duration.ofSeconds(2))) otherOutcome.compareAndSet(null, "blocked");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                events.add("created end");
            }
            @Override public void sessionDestroyed(HttpSessionEvent se) { events.add("destroyed"); }
        });
        manager.setListenerRegistry(registry);
        manager.createNew();
        closer.get().join(5_000);
        assertEquals("refused", otherOutcome.get());
        // Undeploy announced the destruction only after the creation was fully announced.
        assertEquals(List.of("created", "created end", "destroyed"), events);
    }
}
