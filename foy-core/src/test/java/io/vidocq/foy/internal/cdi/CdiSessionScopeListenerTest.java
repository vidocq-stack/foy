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
import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBean;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBeanManager;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeRequestSessions;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.vidocq.foy.internal.cdi.CdiFakes.creationalContext;
import static org.junit.jupiter.api.Assertions.*;

class CdiSessionScopeListenerTest {

    private final FakeBeanManager cdi = new FakeBeanManager();
    private final ListenerRegistry registry = new ListenerRegistry();
    private final VidocqServletContext servletContext = new VidocqServletContext("/");
    private final ServletRequest request = CdiFakes.servletRequest();
    private final FakeBean cart = new FakeBean("cart");
    private SessionManager manager;
    private CdiSessionScopeListener listener;

    @BeforeEach
    void setUp() {
        cdi.sessionContexts.add(new FoySessionContext());
        manager = new SessionManager(new InMemorySessionStore(), servletContext, 1800);
        manager.setListenerRegistry(registry);
        listener = new CdiSessionScopeListener(cdi.proxy());
        manager.setLifecycleHook(listener);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        for (int i = 0; i < 10 && SessionContextBinding.current() != null; i++) {
            SessionContextBinding.current().unbind();
        }
    }

    private FakeRequestSessions begin() {
        var sessions = new FakeRequestSessions(manager);
        listener.bind(request, sessions);
        return sessions;
    }

    private void end() {
        listener.requestDestroyed(new ServletRequestEvent(servletContext, request));
    }

    @Test
    void aRequestBindsTheContextUntilItEnds() {
        begin();
        assertTrue(SessionContextBinding.isActive());
        end();
        assertFalse(SessionContextBinding.isActive());
    }

    @Test
    void aRequestThatIsNotAFoyRequestIsNotBound() {
        listener.requestInitialized(new ServletRequestEvent(servletContext, request));
        assertFalse(SessionContextBinding.isActive());
        end();
    }

    @Test
    void creatingTheSessionFiresInitialized() {
        var sessions = begin();
        SessionContextBinding.get(cart, creationalContext());
        end();
        assertEquals(List.of("Initialized:" + sessions.current().getId()), cdi.events);
    }

    @Test
    void aSessionBeanUsedWhileItsSessionIsCreatedResolvesInThatSession() {
        var fromObserver = new CopyOnWriteArrayList<Object>();
        cdi.onEvent = e -> {
            if (e.startsWith("Initialized")) fromObserver.add(SessionContextBinding.get(cart, creationalContext()));
        };
        var fromListener = new CopyOnWriteArrayList<Object>();
        registry.register(new HttpSessionListener() {
            @Override public void sessionCreated(HttpSessionEvent se) {
                fromListener.add(se.getSession().getId() + "=" + SessionContextBinding.get(cart, creationalContext()));
            }
        });
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        String id = sessions.current().getId();
        end();
        assertEquals(1, manager.store().size(), "a single session is created");
        assertEquals(1, cart.created.get(), "a single instance is created");
        assertEquals(List.of(instance), fromObserver);
        assertEquals(List.of(id + "=" + instance), fromListener);
        assertEquals(List.of("Initialized:" + id), cdi.events);
    }

    @Test
    void invalidateDuringTheRequestDefersDestructionToRequestEnd() {
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl session = sessions.current();
        session.invalidate();
        assertEquals(List.of(), cart.destroyed, "destroyed only at the end of the request");
        assertSame(instance, SessionContextBinding.get(cart, creationalContext()), "the old instance is still served");
        end();
        assertEquals(List.of(instance), cart.destroyed);
        String id = session.getId();
        assertEquals(List.of("Initialized:" + id, "BeforeDestroyed:" + id, "Destroyed:" + id), cdi.events);
        assertNull(SessionContextBinding.current());
    }

    @Test
    void invalidateBeforeAnyBeanUseStillDefers() {
        var sessions = begin();
        HttpSessionImpl session = sessions.session(true);
        session.invalidate();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        assertEquals(List.of(), cart.destroyed);
        end();
        assertEquals(List.of(instance), cart.destroyed);
    }

    @Test
    void theApplicationsSessionDestroyedListenerSeesTheBeansOnInvalidate() {
        var seen = new CopyOnWriteArrayList<Object>();
        registry.register(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) { seen.add(SessionContextBinding.get(cart)); }
        });
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        sessions.current().invalidate();
        end();
        assertEquals(List.of(instance), seen);
    }

    @Test
    void reaperExpiryDestroysTheBeansAfterTheApplicationsListeners() {
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl session = sessions.current();
        end();
        var seen = new CopyOnWriteArrayList<String>();
        registry.register(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) {
                seen.add("active=" + SessionContextBinding.isActive() + " bean=" + SessionContextBinding.get(cart)
                        + " destroyedYet=" + !cart.destroyed.isEmpty());
            }
        });
        session.setMaxInactiveInterval(1);
        manager.restartReaper(Duration.ofMillis(50));
        String id = session.getId();
        Await.until(() -> cdi.requestContext.size() == 2, "the reaper to end the destruction");
        assertEquals(List.of("active=true bean=" + instance + " destroyedYet=false"), seen);
        assertEquals(List.of(instance), cart.destroyed);
        assertEquals(List.of("Initialized:" + id, "BeforeDestroyed:" + id, "Destroyed:" + id), cdi.events);
        assertEquals(List.of("activate@foy-session-reaper", "deactivate@foy-session-reaper"), cdi.requestContext);
    }

    @Test
    void undeployDestroysTheBeansOfEveryLiveSession() {
        begin();
        Object first = SessionContextBinding.get(cart, creationalContext());
        end();
        begin();
        Object second = SessionContextBinding.get(cart, creationalContext());
        end();
        manager.close();
        assertEquals(2, cart.destroyed.size());
        assertTrue(cart.destroyed.containsAll(List.of(first, second)));
        assertEquals(2, cdi.events.stream().filter(e -> e.startsWith("Destroyed:")).count(), cdi.events::toString);
    }

    @Test
    void aThrowingSessionListenerStillDestroysTheBeans() {
        registry.register(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) { throw new IllegalStateException("listener"); }
        });
        begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        end();
        manager.close();
        assertEquals(List.of(instance), cart.destroyed);
    }

    @Test
    void aThrowingObserverStillDestroysTheBeans() {
        cdi.onEvent = e -> {
            if (e.startsWith("BeforeDestroyed")) throw new IllegalStateException("observer");
        };
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        sessions.current().invalidate();
        try (var log = LogCapture.of(CdiSessionScopeListener.class.getName())) {
            end();
            assertEquals(1, log.warnings().size(), log.warnings()::toString);
        }
        assertEquals(List.of(instance), cart.destroyed);
        assertTrue(cdi.events.contains("Destroyed:" + sessions.current().getId()), cdi.events::toString);
        assertNull(SessionContextBinding.current());
    }

    @Test
    void aSessionBeanResolvedWhileItsSessionsBeansAreDestroyedIsRefused() {
        var token = new FakeBean("token");
        var refused = new CopyOnWriteArrayList<Throwable>();
        // cart's @PreDestroy uses another session bean, not created in this session
        cart.onDestroy = () -> {
            try {
                SessionContextBinding.get(token, creationalContext());
            } catch (ContextNotActiveException e) {
                refused.add(e);
            }
        };
        var sessions = begin();
        SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl session = sessions.current();
        session.invalidate();
        try (var log = LogCapture.of(SessionBeanStore.class.getName())) {
            end();
            assertEquals(1, log.warnings().size(), log.warnings()::toString);
        }
        assertEquals(1, refused.size(), "the creation is refused, not leaked into a store nobody destroys");
        assertEquals(0, token.created.get());
        assertNull(SessionBeanStore.existing(session));
    }

    @Test
    void anotherActiveSessionContextMakesFoyStepAside() {
        cdi.sessionContexts.add(CdiFakes.activeForeignContext());
        var sessions = begin();
        assertFalse(SessionContextBinding.isActive());
        sessions.session(true);
        end();
        assertEquals(List.of(), cdi.events, "no Foy event while another context serves the thread");
    }

    @Test
    void theExpiryOfAnotherSessionDuringARequestKeepsTheRequestBinding() {
        var first = begin();
        Object old = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl expiring = first.current();
        end();
        expiring.setMaxInactiveInterval(1);
        begin();
        SessionContextBinding requestBinding = SessionContextBinding.current();
        Await.until(() -> manager.peek(expiring.getId()) == null, "the lazy expiry of the other session");
        assertEquals(List.of(old), cart.destroyed);
        assertSame(requestBinding, SessionContextBinding.current());
        end();
    }
}
