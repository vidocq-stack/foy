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

import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpSession;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Test doubles for the session context tests: no CDI container involved. */
final class CdiFakes {

    private CdiFakes() {}

    /** A session manager with a 30-minute timeout and no reaper. */
    static SessionManager manager() {
        return new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
    }

    static <T> CreationalContext<T> creationalContext() {
        return new CreationalContext<>() {
            @Override public void push(T incompleteInstance) {}
            @Override public void release() {}
        };
    }

    /**
     * A bean named {@code name}: its instances are {@code "<name>#<n>"}, its destructions are
     * recorded in {@code destroyed}. Equal by name, as Weld's serializable wrappers of one bean are.
     */
    static final class FakeBean implements Contextual<Object> {
        final String name;
        final AtomicInteger created = new AtomicInteger();
        final List<Object> destroyed;
        volatile Runnable onCreate = () -> {};
        /** Runs when an instance is destroyed, as a {@code @PreDestroy} callback would. */
        volatile Runnable onDestroy = () -> {};
        volatile RuntimeException destroyFailure;

        FakeBean(String name) {
            this(name, new CopyOnWriteArrayList<>());
        }

        FakeBean(String name, List<Object> destroyed) {
            this.name = name;
            this.destroyed = destroyed;
        }

        @Override
        public Object create(CreationalContext<Object> creationalContext) {
            onCreate.run();
            return name + "#" + created.incrementAndGet();
        }

        @Override
        public void destroy(Object instance, CreationalContext<Object> creationalContext) {
            destroyed.add(instance);
            onDestroy.run();
            if (destroyFailure != null) throw destroyFailure;
        }

        @Override public boolean equals(Object o) { return o instanceof FakeBean b && b.name.equals(name); }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public String toString() { return "FakeBean[" + name + "]"; }
    }

    /**
     * The session lookup of one request, as {@code HttpServletRequestImpl} does it: the session it
     * holds while valid, else a new one when asked to create one.
     */
    static final class FakeRequestSessions implements SessionContextBinding.SessionSource {
        private final SessionManager manager;
        private volatile HttpSessionImpl held;

        FakeRequestSessions(SessionManager manager) {
            this(manager, null);
        }

        FakeRequestSessions(SessionManager manager, HttpSessionImpl held) {
            this.manager = manager;
            this.held = held;
        }

        @Override
        public HttpSessionImpl session(boolean create) {
            HttpSessionImpl s = held;
            if (s != null && !s.isInvalidated()) return s;
            if (!create) return null;
            held = manager.createNew();
            return held;
        }

        @Override
        public HttpSessionImpl current() {
            return held;
        }
    }

    @FunctionalInterface
    interface Handler {
        Object handle(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<?> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> type.getSimpleName() + "@fake";
            default -> handler.handle(m.getName(), a == null ? new Object[0] : a);
        });
    }

    /** A request object used only as a map key by the listener. */
    static ServletRequest servletRequest() {
        return proxy(ServletRequest.class, (m, a) -> {
            throw new UnsupportedOperationException(m);
        });
    }

    /** Another container's session context, active on every thread. */
    static Context activeForeignContext() {
        return proxy(Context.class, (m, a) -> switch (m) {
            case "isActive" -> true;
            case "getScope" -> SessionScoped.class;
            default -> throw new UnsupportedOperationException(m);
        });
    }

    /** A BeanManager answering what CdiSessionScopeListener asks: session contexts, events, a RequestContextController. */
    static final class FakeBeanManager {
        final List<Context> sessionContexts = new CopyOnWriteArrayList<>();
        /** {@code "<qualifier simple names>:<session id>"} per fired event. */
        final List<String> events = new CopyOnWriteArrayList<>();
        /** {@code "activate@<thread>"} / {@code "deactivate@<thread>"}. */
        final List<String> requestContext = new CopyOnWriteArrayList<>();
        /** Runs after an event is recorded; may throw to emulate a failing observer. */
        volatile Consumer<String> onEvent = e -> {};

        BeanManager proxy() {
            return CdiFakes.proxy(BeanManager.class, (m, a) -> switch (m) {
                case "getContexts" -> a[0] == SessionScoped.class ? List.copyOf(sessionContexts) : List.of();
                case "getEvent" -> event(List.of());
                case "createInstance" -> instance();
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private Event<Object> event(List<Annotation> qualifiers) {
            return CdiFakes.proxy(Event.class, (m, a) -> switch (m) {
                case "select" -> {
                    if (a.length == 1 && a[0] instanceof Annotation[] more) {
                        var all = new ArrayList<>(qualifiers);
                        all.addAll(List.of(more));
                        yield event(all);
                    }
                    throw new UnsupportedOperationException("select with " + a.length + " arguments");
                }
                case "fire" -> {
                    String entry = qualifiers.stream().map(q -> q.annotationType().getSimpleName())
                            .collect(Collectors.joining(",")) + ":" + ((HttpSession) a[0]).getId();
                    events.add(entry);
                    onEvent.accept(entry);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private Instance<Object> instance() {
            RequestContextController controller = CdiFakes.proxy(RequestContextController.class, (m, a) -> switch (m) {
                case "activate" -> {
                    requestContext.add("activate@" + Thread.currentThread().getName());
                    yield true;
                }
                case "deactivate" -> {
                    requestContext.add("deactivate@" + Thread.currentThread().getName());
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
            Instance<Object> selected = CdiFakes.proxy(Instance.class, (m, a) -> switch (m) {
                case "isResolvable" -> true;
                case "get" -> controller;
                default -> throw new UnsupportedOperationException(m);
            });
            return CdiFakes.proxy(Instance.class, (m, a) -> switch (m) {
                case "select" -> selected;
                default -> throw new UnsupportedOperationException(m);
            });
        }
    }
}
