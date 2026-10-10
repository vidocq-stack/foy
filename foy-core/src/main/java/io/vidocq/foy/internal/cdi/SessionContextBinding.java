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

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The state of Foy's session context on the current thread (foy#21). {@link FoySessionContext} is
 * active exactly while a binding exists on the calling thread (CDI 4.1 §6.2: a context is active
 * with respect to a thread).
 *
 * <p>Two kinds of binding: a <em>request binding</em> ({@link #bind}), set for each request by
 * {@code CdiSessionScopeListener}, finds the session lazily through the request; a <em>destruction
 * binding</em> ({@link #bindTo}) serves one dying session while its listeners and its beans'
 * destruction run. Bindings nest: {@link #unbind()} restores the previous one.</p>
 *
 * <p>A request that invalidates its own session keeps being served the old instances until it ends
 * ({@link #markInvalidatedHere}); they are destroyed then. Once the request holds a new valid
 * session (invalidate, then {@code getSession(true)}: the session-fixation idiom of a login), that
 * new session is served instead.</p>
 *
 * <p>Package-private: only the session context and its request listener, both in this package,
 * use it. A binding belongs to one thread and is not thread-safe.</p>
 */
final class SessionContextBinding {

    private static final System.Logger LOG = System.getLogger(SessionContextBinding.class.getName());
    private static final ThreadLocal<SessionContextBinding> CURRENT = new ThreadLocal<>();

    /** How a request reaches its session. */
    interface SessionSource {

        /** {@code request.getSession(create)}; {@code null} when there is none and {@code create} is false. */
        HttpSessionImpl session(boolean create);

        /** The session the request already holds, even invalidated, without lookup or creation. */
        HttpSessionImpl current();

        /** The source of a Foy request. */
        static SessionSource of(HttpServletRequestImpl request) {
            return new SessionSource() {
                @Override
                public HttpSessionImpl session(boolean create) {
                    return request.getSession(create) instanceof HttpSessionImpl s ? s : null;
                }

                @Override
                public HttpSessionImpl current() {
                    return request.boundSession();
                }
            };
        }
    }

    private final SessionContextBinding previous;
    /** {@code null} for a destruction binding. */
    private final SessionSource source;
    private final List<HttpSessionImpl> invalidatedHere = new ArrayList<>(1);
    /** The session last served. */
    private HttpSessionImpl session;
    private boolean unbound;

    private SessionContextBinding(SessionContextBinding previous, SessionSource source, HttpSessionImpl session) {
        this.previous = previous;
        this.source = source;
        this.session = session;
    }

    /** Binds the context to a request on the current thread. */
    static SessionContextBinding bind(SessionSource source) {
        var binding = new SessionContextBinding(CURRENT.get(), Objects.requireNonNull(source, "source"), null);
        CURRENT.set(binding);
        return binding;
    }

    /** Binds the context to {@code session}, served even once invalidated, on the current thread. */
    static SessionContextBinding bindTo(HttpSessionImpl session) {
        var binding = new SessionContextBinding(CURRENT.get(), null, Objects.requireNonNull(session, "session"));
        binding.invalidatedHere.add(session);
        CURRENT.set(binding);
        return binding;
    }

    static SessionContextBinding current() {
        return CURRENT.get();
    }

    static boolean isActive() {
        return CURRENT.get() != null;
    }

    /** Ends this binding and restores the previous live one. Idempotent; call it in a {@code finally}. */
    void unbind() {
        if (unbound) return;
        unbound = true;
        if (CURRENT.get() != this) {
            LOG.log(System.Logger.Level.WARNING, "session context binding ended out of order on {0}",
                    Thread.currentThread());
            return;
        }
        SessionContextBinding restored = previous;
        while (restored != null && restored.unbound) restored = restored.previous;
        if (restored == null) CURRENT.remove();
        else CURRENT.set(restored);
    }

    boolean isRequestBinding() {
        return source != null;
    }

    /** {@code true} when {@code candidate} is the session this binding serves or its request holds. */
    boolean owns(HttpSessionImpl candidate) {
        return candidate == session || (source != null && candidate == source.current());
    }

    /**
     * Serves {@code created} from now on: the request is creating it, and the creation callbacks
     * (lifecycle hook, {@code sessionCreated} listeners, {@code @Initialized} observers) run before
     * the request holds it. Without this, a session bean used from them would ask the request for a
     * session again and create another one, recursively.
     */
    void adopt(HttpSessionImpl created) {
        session = Objects.requireNonNull(created, "created");
    }

    /** Keeps serving {@code dying}, invalidated by this request, until the request ends. */
    void markInvalidatedHere(HttpSessionImpl dying) {
        if (!invalidatedHere.contains(dying)) invalidatedHere.add(dying);
        session = dying;
    }

    /** The sessions this request invalidated, whose beans are destroyed when it ends. */
    List<HttpSessionImpl> invalidatedHere() {
        return List.copyOf(invalidatedHere);
    }

    HttpSessionImpl session(boolean create) {
        HttpSessionImpl s = session;
        if (source != null) {
            // the request moved to another valid session, e.g. a new one after invalidating its own
            HttpSessionImpl held = source.current();
            if (held != null && held != s && !held.isInvalidated()) {
                session = held;
                return held;
            }
        }
        if (s != null && (!s.isInvalidated() || invalidatedHere.contains(s))) return s;
        if (source == null) return null;
        s = source.session(create);
        if (s != null) session = s;
        return s;
    }

    // ---- the operations of FoySessionContext ----

    static <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        SessionContextBinding binding = requireActive();
        if (creationalContext == null) return get(contextual);
        HttpSessionImpl s = binding.session(true);
        if (s == null) throw new IllegalStateException("no HTTP session can be created for this request");
        return SessionBeanStore.of(s).getOrCreate(contextual, creationalContext);
    }

    static <T> T get(Contextual<T> contextual) {
        HttpSessionImpl s = requireActive().session(false);
        if (s == null) return null;
        SessionBeanStore store = SessionBeanStore.existing(s);
        return store == null ? null : store.get(contextual);
    }

    static void destroy(Contextual<?> contextual) {
        HttpSessionImpl s = requireActive().session(false);
        if (s == null) return;
        SessionBeanStore store = SessionBeanStore.existing(s);
        if (store != null) store.destroy(contextual);
    }

    private static SessionContextBinding requireActive() {
        SessionContextBinding binding = CURRENT.get();
        if (binding == null) {
            throw new ContextNotActiveException("the Foy session context is not active on " + Thread.currentThread());
        }
        return binding;
    }
}
