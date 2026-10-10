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

import io.vidocq.foy.internal.Await;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBean;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeRequestSessions;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.vidocq.foy.internal.cdi.CdiFakes.creationalContext;
import static org.junit.jupiter.api.Assertions.*;

class SessionContextBindingTest {

    private final SessionManager manager = CdiFakes.manager();
    private final FakeBean cart = new FakeBean("cart");

    @AfterEach
    void tearDown() {
        for (int i = 0; i < 10 && SessionContextBinding.current() != null; i++) {
            SessionContextBinding.current().unbind();
        }
        manager.close();
        assertNull(SessionContextBinding.current(), "a binding leaked on the test thread");
    }

    @Test
    void withoutABindingEveryOperationThrowsContextNotActive() {
        assertFalse(SessionContextBinding.isActive());
        assertThrows(ContextNotActiveException.class, () -> SessionContextBinding.get(cart, creationalContext()));
        assertThrows(ContextNotActiveException.class, () -> SessionContextBinding.get(cart));
        assertThrows(ContextNotActiveException.class, () -> SessionContextBinding.destroy(cart));
    }

    @Test
    void oneInstancePerBeanPerSession() {
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object first = SessionContextBinding.get(cart, creationalContext());
        Object second = SessionContextBinding.get(cart, creationalContext());
        assertSame(first, second);
        assertEquals(1, cart.created.get());
    }

    @Test
    void equalContextualsShareTheInstance() {
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object first = SessionContextBinding.get(cart, creationalContext());
        assertSame(first, SessionContextBinding.get(new FakeBean("cart"), creationalContext()));
    }

    @Test
    void anotherSessionGetsAnotherInstance() {
        var first = SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object a = SessionContextBinding.get(cart, creationalContext());
        first.unbind();
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object b = SessionContextBinding.get(cart, creationalContext());
        assertNotEquals(a, b);
    }

    @Test
    void getWithoutCreationalContextNeverCreatesASession() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        assertNull(SessionContextBinding.get(cart));
        assertNull(SessionContextBinding.get(cart, null));
        assertNull(request.current());
        assertEquals(0, manager.store().size());
    }

    @Test
    void getWithCreationalContextCreatesTheSessionLazily() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        assertEquals(0, manager.store().size());
        Object instance = SessionContextBinding.get(cart, creationalContext());
        assertNotNull(request.current());
        assertEquals(1, manager.store().size());
        assertSame(instance, SessionContextBinding.get(cart));
    }

    @Test
    void theInstancesAreNotSessionAttributes() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        SessionContextBinding.get(cart, creationalContext());
        assertFalse(request.current().getAttributeNames().hasMoreElements());
    }

    @Test
    void destroyCallsTheContextualAndTheNextGetCreatesAgain() {
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object first = SessionContextBinding.get(cart, creationalContext());
        SessionContextBinding.destroy(cart);
        assertEquals(List.of(first), cart.destroyed);
        assertNotEquals(first, SessionContextBinding.get(cart, creationalContext()));
    }

    @Test
    void changeSessionIdKeepsTheInstances() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        Object before = SessionContextBinding.get(cart, creationalContext());
        manager.changeSessionId(request.current());
        assertSame(before, SessionContextBinding.get(cart, creationalContext()));
    }

    @Test
    void aNestedBindingRestoresThePreviousOne() {
        var outer = SessionContextBinding.bind(new FakeRequestSessions(manager));
        HttpSessionImpl other = manager.createNew();
        var inner = SessionContextBinding.bindTo(other);
        assertSame(inner, SessionContextBinding.current());
        assertFalse(inner.isRequestBinding());
        inner.unbind();
        assertSame(outer, SessionContextBinding.current());
        outer.unbind();
        assertNull(SessionContextBinding.current());
    }

    @Test
    void aDestructionBindingServesItsSessionEvenInvalidated() {
        var request = new FakeRequestSessions(manager);
        var binding = SessionContextBinding.bind(request);
        Object instance = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl session = request.current();
        binding.unbind();
        session.invalidate();   // no lifecycle hook yet: the store stays attached to the session
        SessionContextBinding.bindTo(session);
        assertSame(instance, SessionContextBinding.get(cart));
    }

    @Test
    void invalidateThenNewSessionServesTheNewSession() {
        var request = new FakeRequestSessions(manager);
        var binding = SessionContextBinding.bind(request);
        Object before = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl dying = request.current();
        binding.markInvalidatedHere(dying);   // what the lifecycle hook does on an in-request invalidate()
        dying.invalidate();
        assertSame(before, SessionContextBinding.get(cart), "the dying session is served until a new one exists");

        HttpSessionImpl fresh = request.session(true);   // the login idiom: invalidate, then a new session
        Object after = SessionContextBinding.get(cart, creationalContext());

        assertNotEquals(before, after);
        assertSame(after, SessionBeanStore.existing(fresh).get(cart));
        assertSame(before, SessionBeanStore.existing(dying).get(cart), "the dying instance waits for the request end");
        assertEquals(List.of(dying), binding.invalidatedHere());
        assertTrue(binding.owns(fresh));
    }

    @Test
    void aSessionAdoptedDuringItsCreationServesTheNestedUses() {
        var inner = new FakeBean("inner");
        var binding = new AtomicReference<SessionContextBinding>();
        var held = new AtomicReference<HttpSessionImpl>();
        var creations = new AtomicInteger();
        // HttpServletRequestImpl sets its session only after createNew() returns, while the lifecycle
        // hook and the application's sessionCreated listeners run inside createNew().
        var source = new SessionContextBinding.SessionSource() {
            @Override
            public HttpSessionImpl session(boolean create) {
                if (held.get() != null) return held.get();
                if (!create) return null;
                if (creations.incrementAndGet() > 1) throw new IllegalStateException("a second session was created");
                HttpSessionImpl s = manager.createNew();
                binding.get().adopt(s);                                  // the lifecycle hook
                SessionContextBinding.get(inner, creationalContext());   // a sessionCreated listener
                held.set(s);
                return s;
            }

            @Override
            public HttpSessionImpl current() {
                return held.get();
            }
        };
        binding.set(SessionContextBinding.bind(source));

        Object outer = SessionContextBinding.get(cart, creationalContext());

        assertEquals(1, creations.get());
        assertEquals(1, manager.store().size());
        SessionBeanStore store = SessionBeanStore.existing(held.get());
        assertSame(outer, store.get(cart));
        assertEquals("inner#1", store.get(inner));
        assertTrue(binding.get().owns(held.get()));
    }

    @Test
    void concurrentFirstUseOnOneSessionCreatesOneInstance() throws Exception {
        HttpSessionImpl session = manager.createNew();
        var creating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        cart.onCreate = () -> {
            creating.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        var results = new ConcurrentLinkedQueue<Object>();
        Runnable firstUse = () -> {
            var binding = SessionContextBinding.bind(new FakeRequestSessions(manager, session));
            try {
                results.add(SessionContextBinding.get(cart, creationalContext()));
            } finally {
                binding.unbind();
            }
        };
        Thread first = Thread.ofVirtual().start(firstUse);
        assertTrue(creating.await(10, TimeUnit.SECONDS));
        Thread second = Thread.ofVirtual().start(firstUse);
        Await.until(() -> second.getState() == Thread.State.WAITING, "the second request to wait for the store lock");
        release.countDown();
        assertTrue(first.join(Duration.ofSeconds(10)));
        assertTrue(second.join(Duration.ofSeconds(10)));
        assertEquals(2, results.size());
        assertEquals(1, cart.created.get());
        assertEquals(1, new HashSet<>(results).size());
    }

    @Test
    void aSessionBeanCreatedWhileCreatingAnotherOneDoesNotDeadlock() throws Exception {
        var inner = new FakeBean("inner");
        cart.onCreate = () -> SessionContextBinding.get(inner, creationalContext());
        var failure = new AtomicReference<Throwable>();
        Thread t = Thread.ofVirtual().start(() -> {
            var binding = SessionContextBinding.bind(new FakeRequestSessions(manager));
            try {
                SessionContextBinding.get(cart, creationalContext());
                SessionContextBinding.get(inner, creationalContext());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                binding.unbind();
            }
        });
        assertTrue(t.join(Duration.ofSeconds(10)), "deadlock: the creation did not end");
        assertNull(failure.get());
        assertEquals(1, cart.created.get());
        assertEquals(1, inner.created.get());
    }
}
