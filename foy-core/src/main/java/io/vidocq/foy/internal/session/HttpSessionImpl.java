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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    /** {@code sessionIdChanged} notifications in progress; guarded by {@code this}. */
    private int idChanges;
    /** An invalidation was claimed during an id change and completes when it ends; guarded by {@code this}. */
    private boolean destructionDeferred;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    /**
     * Container-private values (authentication state). They are not attributes: no listener, no
     * {@code getAttributeNames}, out of the application's reach. They stay with the session object
     * across {@code changeSessionId} and are dropped on invalidation.
     */
    private final ConcurrentMap<String, Object> notes = new ConcurrentHashMap<>();

    public Object note(String key) { return notes.get(key); }
    public void setNote(String key, Object value) { notes.put(key, value); }
    public Object removeNote(String key) { return notes.remove(key); }

    public HttpSessionImpl(String id, ServletContext ctx, SessionManager manager, int maxInactiveSeconds) {
        this(id, ctx, manager, maxInactiveSeconds, System.currentTimeMillis());
    }

    private HttpSessionImpl(String id, ServletContext ctx, SessionManager manager, int maxInactiveSeconds,
                            long creationTime) {
        this.id = id;
        this.servletContext = ctx;
        this.manager = manager;
        this.creationTime = creationTime;
        this.lastAccessedTime = creationTime;
        this.thisAccessedTime = creationTime;
        this.maxInactiveInterval = maxInactiveSeconds;
    }

    /**
     * A session imported from another deployment ({@link SessionManager#importSessions}): its id, times, interval,
     * attributes and notes as they were exported, and no longer new. {@link #getLastAccessedTime()} is the end of the
     * last request that completed before the export; the idle time counts from {@link #idleSince(long)} at the export.
     * No listener fires.
     */
    static HttpSessionImpl imported(ServletContext ctx, SessionManager manager, SessionExport.ExportedSession exported,
                                    Map<String, Object> attributes, Map<String, Object> notes) {
        var session = new HttpSessionImpl(exported.id(), ctx, manager, exported.maxInactiveInterval(),
                exported.creationTime());
        session.lastAccessedTime = exported.lastAccessedTime();
        session.thisAccessedTime = exported.idleSince();
        session.newSession = false;
        session.attributes.putAll(attributes);
        session.notes.putAll(notes);
        return session;
    }

    /** The first 8 characters of a session id: a whole id is a credential, never logged. */
    static String idPrefix(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
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

    /**
     * The reference of the idle time for an export at {@code now}: the end of the last request that completed, or
     * {@code now} while a request still uses the session. A session in use never expires on this side, however
     * long the request runs (a server-sent event stream), so its idle time starts with the export.
     */
    synchronized long idleSince(long now) {
        return accessCount > 0 ? now : Math.max(lastAccessedTime, thisAccessedTime);
    }

    /** {@code true} when the session is idle beyond its maximum inactive interval. */
    private boolean isIdleExpired(long now) {
        return idleBeyond(maxInactiveInterval, thisAccessedTime, now);
    }

    /** {@code true} when a session idle since {@code since} is beyond {@code maxInactiveSeconds} at {@code now}. */
    static boolean idleBeyond(int maxInactiveSeconds, long since, long now) {
        return maxInactiveSeconds > 0 && now - since >= maxInactiveSeconds * 1000L;
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
        synchronized (this) {
            // An id-change listener is running: it must not see a destroyed session, and the
            // invalidating thread must not wait for it. The id change completes the invalidation.
            if (idChanges > 0) {
                destructionDeferred = true;
                return;
            }
        }
        try {
            manager.listenerRegistry().fireSessionDestroyed(this);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "sessionDestroyed failed for session " + idPrefix(id) + "...", e);
        }
        invalidated = true;
        notes.clear();
        for (String name : new ArrayList<>(attributes.keySet())) {
            Object v = attributes.remove(name);
            if (v == null) continue;
            try {
                if (v instanceof HttpSessionBindingListener l) {
                    l.valueUnbound(new HttpSessionBindingEvent(this, name, v));
                }
                manager.listenerRegistry().fireSessionAttributeRemoved(this, name, v);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "unbinding " + name + " failed for session " + idPrefix(id) + "...", e);
            }
        }
        manager.onInvalidated(this);
    }

    /**
     * Second half of an export claimed by {@link #claimInvalidation}: the session moved to another deployment, so it
     * leaves without ending. It is marked invalidated, so that a stale reference throws
     * {@link IllegalStateException}, its attributes and notes are cleared without any event, and it leaves the
     * store. No {@code sessionDestroyed}, {@code valueUnbound} or {@code attributeRemoved} fires.
     */
    void depart() {
        invalidated = true;
        attributes.clear();
        notes.clear();
        manager.onInvalidated(this);
    }

    /** A copy of the attributes, for an export. */
    Map<String, Object> attributeSnapshot() {
        return new LinkedHashMap<>(attributes);
    }

    /** A copy of the container notes, for an export. */
    Map<String, Object> noteSnapshot() {
        return new LinkedHashMap<>(notes);
    }

    /**
     * {@code true} while no invalidation started. {@link SessionManager#changeSessionId} checks it
     * while holding this session's monitor, which every invalidation claim also takes, so that an
     * id change and an invalidation never interleave.
     */
    synchronized boolean isLive() { return !invalidating && !invalidated; }

    /** An id change claimed under this session's monitor is about to notify its listeners. */
    synchronized void beginIdChange() { idChanges++; }

    /** The id-change listeners returned: completes an invalidation claimed meanwhile, if any. */
    void endIdChange() {
        boolean complete;
        synchronized (this) {
            idChanges--;
            complete = idChanges == 0 && destructionDeferred;
            if (complete) destructionDeferred = false;
        }
        if (complete) completeInvalidation();
    }

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

    private void checkValid() {
        if (invalidated) throw new IllegalStateException("session invalidated");
    }
}
