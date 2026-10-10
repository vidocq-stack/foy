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

import io.vidocq.foy.internal.Await;
import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vidocq.foy.spi.session.SessionStore;
import jakarta.servlet.http.HttpSession;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The internal hook the CDI session context uses (foy#21): when the manager calls it. */
class SessionLifecycleHookTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final InMemorySessionStore store = new InMemorySessionStore();
    private final ListenerRegistry registry = new ListenerRegistry();
    private volatile String destructionThread;
    private SessionManager manager;

    /** Records its calls, the thread of the destruction, and whether the session is still stored. */
    private final class RecordingHook implements SessionLifecycleHook {
        @Override
        public void sessionCreated(HttpSessionImpl session) {
            log.add("hook:created:stored=" + store.get(session.getId()).isPresent());
        }

        @Override
        public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
            destructionThread = Thread.currentThread().getName();
            log.add("hook:before");
            destruction.run();
            log.add("hook:after:stored=" + store.get(session.getId()).isPresent());
        }
    }

    @BeforeEach
    void setUp() {
        registry.register(new HttpSessionListener() {
            @Override public void sessionCreated(HttpSessionEvent se) { log.add("listener:created"); }
            @Override public void sessionDestroyed(HttpSessionEvent se) { log.add("listener:destroyed"); }
        });
        manager = new SessionManager(store, new VidocqServletContext("/"), 1800);
        manager.setListenerRegistry(registry);
        manager.setLifecycleHook(new RecordingHook());
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private HttpSessionImpl sessionWithUnbindProbe() {
        HttpSessionImpl session = manager.createNew();
        session.setAttribute("probe", new HttpSessionBindingListener() {
            @Override public void valueUnbound(HttpSessionBindingEvent event) { log.add("unbound"); }
        });
        log.clear();
        return session;
    }

    @Test
    void creationCallsTheHookOnTheStoredSessionBeforeTheListeners() {
        manager.createNew();
        assertEquals(List.of("hook:created:stored=true", "listener:created"), log);
    }

    @Test
    void invalidateRunsTheServletDestructionInsideTheHook() {
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.invalidate();
        assertEquals(List.of("hook:before", "listener:destroyed", "unbound", "hook:after:stored=true"), log);
        assertTrue(session.isInvalidated());
        assertEquals(0, store.size());
    }

    @Test
    void theReaperGoesThroughTheHookOnItsOwnThread() {
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.setMaxInactiveInterval(1);
        manager.restartReaper(Duration.ofMillis(50));
        Await.until(() -> store.size() == 0, "the reaper to expire the session");
        assertEquals("foy-session-reaper", destructionThread);
        assertEquals(List.of("hook:before", "listener:destroyed", "unbound", "hook:after:stored=true"), log);
    }

    @Test
    void lazyExpiryGoesThroughTheHook() {
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.setMaxInactiveInterval(1);
        Await.until(() -> manager.peek(session.getId()) == null, "the session to expire");
        assertEquals(List.of("hook:before", "listener:destroyed", "unbound", "hook:after:stored=true"), log);
    }

    @Test
    void closeGoesThroughTheHookForEveryLiveSession() {
        manager.createNew();
        manager.createNew();
        log.clear();
        manager.close();
        assertEquals(2, log.stream().filter("hook:after:stored=true"::equals).count(), log::toString);
    }

    @Test
    void aHookFailingBeforeTheDestructionStillInvalidatesOnce() {
        manager.setLifecycleHook(new SessionLifecycleHook() {
            @Override public void sessionCreated(HttpSessionImpl session) {}
            @Override public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
                throw new IllegalStateException("hook before");
            }
        });
        HttpSessionImpl session = sessionWithUnbindProbe();
        try (var warnings = LogCapture.of(HttpSessionImpl.class.getName())) {
            session.invalidate();
            assertEquals(1, warnings.warnings().size(), warnings.warnings()::toString);
        }
        assertEquals(List.of("listener:destroyed", "unbound"), log);
        assertTrue(session.isInvalidated());
        assertEquals(0, store.size());
    }

    @Test
    void aHookFailingAfterTheDestructionDoesNotRunItTwice() {
        manager.setLifecycleHook(new SessionLifecycleHook() {
            @Override public void sessionCreated(HttpSessionImpl session) {}
            @Override public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
                destruction.run();
                throw new IllegalStateException("hook after");
            }
        });
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.invalidate();
        assertEquals(List.of("listener:destroyed", "unbound"), log);
        assertEquals(0, store.size());
    }

    @Test
    void aHookFailingOnCreationDoesNotFailTheCreationNorSkipTheListeners() {
        manager.setLifecycleHook(new SessionLifecycleHook() {
            @Override public void sessionCreated(HttpSessionImpl session) { throw new IllegalStateException("created"); }
            @Override public void aroundDestruction(HttpSessionImpl session, Runnable destruction) { destruction.run(); }
        });
        try (var warnings = LogCapture.of(SessionManager.class.getName())) {
            assertNotNull(manager.createNew());
            assertEquals(1, warnings.warnings().size(), warnings.warnings()::toString);
        }
        assertEquals(List.of("listener:created"), log);
    }

    @Test
    void restartReaperAfterCloseStartsNothing() {
        manager.close();
        manager.restartReaper(Duration.ofMillis(50));
        assertFalse(manager.isReaperRunning());
    }

    @Test
    void restartReaperWaitsForTheReplacedReaperSoThatNoScanOutlivesClose() throws Exception {
        var scanning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var scans = new AtomicInteger();
        SessionStore blocking = new SessionStore() {
            @Override public Optional<HttpSession> get(String id) { return Optional.empty(); }
            @Override public void put(HttpSession session) { }
            @Override public void remove(String id) { }
            @Override public int size() { return 0; }
            @Override public Collection<HttpSession> sessions() {
                scans.incrementAndGet();
                scanning.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        var blocked = new SessionManager(blocking, new VidocqServletContext("/"), 1800);
        try {
            blocked.restartReaper(Duration.ofMillis(1));
            assertTrue(scanning.await(5, TimeUnit.SECONDS), "the first reaper never scanned");
            Thread restart = Thread.ofVirtual().start(() -> blocked.restartReaper(Duration.ofHours(1)));
            restart.join(Duration.ofMillis(300));
            assertTrue(restart.isAlive(), "restartReaper returned while the replaced reaper was scanning");
            release.countDown();
            restart.join(Duration.ofSeconds(5));
            assertFalse(restart.isAlive(), "restartReaper never returned");
            blocked.restartReaper(Duration.ofHours(1));
        } finally {
            release.countDown();
            blocked.close();
        }
        int afterClose = scans.get();
        Thread.sleep(100);
        assertEquals(afterClose, scans.get(), "a scan ran after close() returned");
        assertFalse(blocked.isReaperRunning());
    }
}
