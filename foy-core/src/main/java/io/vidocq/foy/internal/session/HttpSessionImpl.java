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

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Thread-safe in-memory implementation of {@link HttpSession}.
 *
 * <p>Access times follow the {@code HttpSession} Javadoc: {@link #getLastAccessedTime()} is the
 * time of the <em>previous</em> request associated with the session. A request begins an access
 * ({@link #tryAccess}/{@link #beginAccess}) and ends it once processed ({@link #endAccess}); only
 * the end of an access moves the last-accessed time. A session in use by an in-flight request is
 * never expired, whatever its idle time.</p>
 *
 * <p>Invalidation (explicit or by expiry) fires {@code sessionDestroyed} while the session is
 * still valid, so that listeners can read its attributes, then unbinds every attribute
 * ({@code valueUnbound} and {@code attributeRemoved}) and removes the session from its store.</p>
 */
public final class HttpSessionImpl implements HttpSession {

    private static final System.Logger LOG = System.getLogger(HttpSessionImpl.class.getName());

    private volatile String id;
    private final ServletContext servletContext;
    private final SessionManager manager;
    private final long creationTime;
    /** End of the previous access: the value of {@link #getLastAccessedTime()}. */
    private volatile long lastAccessedTime;
    /** Start or end of the most recent access: the idle time is measured from it. */
    private volatile long thisAccessedTime;
    private volatile int maxInactiveInterval;
    private volatile boolean newSession = true;
    /** Set once an invalidation started; guarded by {@code this}. */
    private boolean invalidating;
    private volatile boolean invalidated;
    /** Requests currently using the session; guarded by {@code this}. */
    private int accessCount;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    /**
     * Foy-internal state of the CDI session context (foy#21): never an attribute, so the
     * application does not see it, no attribute listener fires, and an id change keeps it.
     */
    private final AtomicReference<Object> scopeState = new AtomicReference<>();

    public HttpSessionImpl(String id, ServletContext ctx, SessionManager manager, int maxInactiveSeconds) {
        this.id = id;
        this.servletContext = ctx;
        this.manager = manager;
        this.creationTime = System.currentTimeMillis();
        this.lastAccessedTime = creationTime;
        this.thisAccessedTime = creationTime;
        this.maxInactiveInterval = maxInactiveSeconds;
    }

    // ---- access tracking ----

    /**
     * Begins an access by a request that found the session by its id. Returns {@code false},
     * leaving the session untouched, when it is invalidated or has expired while unused.
     */
    synchronized boolean tryAccess(long now) {
        if (invalidating || invalidated) return false;
        if (accessCount == 0 && isIdleExpired(now)) return false;
        accessCount++;
        thisAccessedTime = now;
        newSession = false;
        return true;
    }

    /** Begins an access by the request that created the session. */
    public synchronized void beginAccess() {
        accessCount++;
        thisAccessedTime = System.currentTimeMillis();
    }

    /**
     * Ends an access: the end of this request becomes the session's last-accessed time. The
     * {@code isNew} flag is left alone: only a request that finds the session by the id the
     * client sent ({@link #tryAccess}) shows that the client joined it.
     */
    public synchronized void endAccess() {
        if (accessCount > 0) accessCount--;
        long now = System.currentTimeMillis();
        thisAccessedTime = now;
        lastAccessedTime = now;
    }

    /** {@code true} when the session is idle beyond its maximum inactive interval. */
    private boolean isIdleExpired(long now) {
        int max = maxInactiveInterval;
        return max > 0 && now - thisAccessedTime >= max * 1000L;
    }

    /**
     * Claims the session for expiry: succeeds only when no request uses it, it has been idle
     * beyond its maximum inactive interval and no invalidation started. The caller then
     * {@linkplain #completeInvalidation() completes} the invalidation.
     */
    synchronized boolean claimExpired(long now) {
        if (invalidating || invalidated || accessCount > 0 || !isIdleExpired(now)) return false;
        invalidating = true;
        return true;
    }

    /** Claims the session for an invalidation that does not depend on idleness (undeploy). */
    synchronized boolean claimInvalidation() {
        if (invalidating || invalidated) return false;
        invalidating = true;
        return true;
    }

    /**
     * Second half of an invalidation claimed by {@link #claimExpired}, {@link #claimInvalidation}
     * or {@link #invalidate()}: {@code sessionDestroyed}, then the unbinding of the attributes,
     * then the removal from the store. A failing listener is logged and does not stop the
     * invalidation.
     */
    void completeInvalidation() {
        try {
            manager.listenerRegistry().fireSessionDestroyed(this);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "sessionDestroyed failed for session " + id, e);
        }
        invalidated = true;
        for (String name : new ArrayList<>(attributes.keySet())) {
            Object v = attributes.remove(name);
            if (v == null) continue;
            try {
                if (v instanceof HttpSessionBindingListener l) {
                    l.valueUnbound(new HttpSessionBindingEvent(this, name, v));
                }
                manager.listenerRegistry().fireSessionAttributeRemoved(this, name, v);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "unbinding " + name + " failed for session " + id, e);
            }
        }
        manager.onInvalidated(this);
    }

    /**
     * {@code true} while no invalidation started. {@link SessionManager#changeSessionId} checks it
     * while holding this session's monitor, which every invalidation claim also takes, so that an
     * id change and an invalidation never interleave.
     */
    synchronized boolean isLive() { return !invalidating && !invalidated; }

    /** Re-keys the session ({@link SessionManager#changeSessionId}, under this session's monitor). */
    void setId(String newId) { this.id = newId; }

    // ---- HttpSession ----

    @Override public long getCreationTime() { checkValid(); return creationTime; }
    @Override public String getId() { return id; }
    @Override public long getLastAccessedTime() { checkValid(); return lastAccessedTime; }
    @Override public ServletContext getServletContext() { return servletContext; }
    @Override public void setMaxInactiveInterval(int interval) { this.maxInactiveInterval = interval; }
    @Override public int getMaxInactiveInterval() { return maxInactiveInterval; }

    @Override public Object getAttribute(String name) { checkValid(); return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(new ArrayList<>(attributes.keySet()));
    }
    @Override public void setAttribute(String name, Object value) {
        checkValid();
        if (value == null) { removeAttribute(name); return; }
        Object previous = attributes.put(name, value);
        // HttpSessionBindingListener (spec Servlet 6.1 §7.7.3)
        if (value instanceof HttpSessionBindingListener l) {
            l.valueBound(new HttpSessionBindingEvent(this, name, value));
        }
        if (previous instanceof HttpSessionBindingListener l) {
            l.valueUnbound(new HttpSessionBindingEvent(this, name, previous));
        }
        if (previous == null) {
            manager.listenerRegistry().fireSessionAttributeAdded(this, name, value);
        } else {
            manager.listenerRegistry().fireSessionAttributeReplaced(this, name, previous);
        }
    }
    @Override public void removeAttribute(String name) {
        checkValid();
        Object previous = attributes.remove(name);
        if (previous == null) return;
        if (previous instanceof HttpSessionBindingListener l) {
            l.valueUnbound(new HttpSessionBindingEvent(this, name, previous));
        }
        manager.listenerRegistry().fireSessionAttributeRemoved(this, name, previous);
    }

    @Override public void invalidate() {
        checkValid();
        if (!claimInvalidation()) throw new IllegalStateException("session already invalidated");
        completeInvalidation();
    }

    @Override public boolean isNew() { checkValid(); return newSession; }

    public boolean isInvalidated() { return invalidated; }

    /** The CDI session context's state of this session, or {@code null}. */
    public Object scopeState() {
        return scopeState.get();
    }

    /** The CDI session context's state of this session, installed from {@code factory} on first use. */
    public Object scopeState(Supplier<?> factory) {
        Object current = scopeState.get();
        if (current != null) return current;
        Object created = factory.get();
        Object witness = scopeState.compareAndExchange(null, created);
        return witness == null ? created : witness;
    }

    /** Detaches the CDI session context's state, for its destruction; {@code null} when none. */
    public Object takeScopeState() {
        return scopeState.getAndSet(null);
    }

    private void checkValid() {
        if (invalidated) throw new IllegalStateException("session invalidated");
    }
}
