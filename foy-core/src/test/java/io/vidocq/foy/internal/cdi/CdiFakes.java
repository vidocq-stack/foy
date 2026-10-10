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
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
}
