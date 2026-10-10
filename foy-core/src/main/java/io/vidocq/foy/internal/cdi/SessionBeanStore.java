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

import io.vidocq.foy.internal.session.HttpSessionImpl;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@code @SessionScoped} instances of one HTTP session (foy#21), kept in a Foy-internal slot of
 * the session.
 *
 * <p>Keyed by the {@code Contextual}'s {@code equals}: Weld hands a passivating context serializable
 * wrappers, a new one per call. One lock per store, held while a bean is created, so two requests of
 * a session create a bean once; the lock is reentrant, so the creation of a session bean may use
 * another session bean of the same session. {@code ConcurrentHashMap.computeIfAbsent} is not used:
 * that nested creation would update the map recursively and throw.</p>
 *
 * <p>Package-private: only the session context of this package uses it.</p>
 */
final class SessionBeanStore {

    private static final System.Logger LOG = System.getLogger(SessionBeanStore.class.getName());

    private record Entry<T>(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext) {
        void destroy() {
            contextual.destroy(instance, creationalContext);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    /** In creation order; destroyed in reverse. Guarded by {@link #lock}. */
    private final Map<Contextual<?>, Entry<?>> entries = new LinkedHashMap<>();

    private SessionBeanStore() {}

    /** The store of {@code session}, created on first use. */
    static SessionBeanStore of(HttpSessionImpl session) {
        return (SessionBeanStore) session.scopeState(SessionBeanStore::new);
    }

    /** The store of {@code session}, or {@code null} when no session bean was created in it. */
    static SessionBeanStore existing(HttpSessionImpl session) {
        return session.scopeState() instanceof SessionBeanStore store ? store : null;
    }

    /** Detaches the store of {@code session} for its destruction; {@code null} when none. */
    static SessionBeanStore take(HttpSessionImpl session) {
        return session.takeScopeState() instanceof SessionBeanStore store ? store : null;
    }

    /** The instance of {@code contextual} in this session, or {@code null}. */
    <T> T get(Contextual<T> contextual) {
        lock.lock();
        try {
            Entry<?> entry = entries.get(contextual);
            return entry == null ? null : cast(entry.instance());
        } finally {
            lock.unlock();
        }
    }

    /** The instance of {@code contextual} in this session, created under the store lock when absent. */
    <T> T getOrCreate(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        lock.lock();
        try {
            Entry<?> entry = entries.get(contextual);
            if (entry != null) return cast(entry.instance());
            T instance = contextual.create(creationalContext);
            entries.put(contextual, new Entry<>(contextual, instance, creationalContext));
            return instance;
        } finally {
            lock.unlock();
        }
    }

    /** {@code AlterableContext.destroy}: a failure of the bean's destruction reaches the caller. */
    void destroy(Contextual<?> contextual) {
        Entry<?> entry;
        lock.lock();
        try {
            entry = entries.remove(contextual);
        } finally {
            lock.unlock();
        }
        if (entry != null) entry.destroy();
    }

    /** Destroys every instance, last created first; a failing destruction is logged, the others still run. */
    void destroyAll() {
        List<Entry<?>> all;
        lock.lock();
        try {
            all = new ArrayList<>(entries.values());
            entries.clear();
        } finally {
            lock.unlock();
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            Entry<?> entry = all.get(i);
            try {
                entry.destroy();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "destroying the session-scoped instance of " + entry.contextual() + " failed", e);
            }
        }
    }

    boolean isEmpty() {
        lock.lock();
        try {
            return entries.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object instance) {
        return (T) instance;
    }
}
