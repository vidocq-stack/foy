package io.vidocq.foy.internal.session;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;

import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory implementation of {@link HttpSession}.
 *
 * <p>Delegates expiration management to {@link SessionManager} who consults
 * {@link #getLastAccessedTime()} and {@link #getMaxInactiveInterval()}.</p>
 */
public final class HttpSessionImpl implements HttpSession {

    private final String id;
    private final ServletContext servletContext;
    private final SessionManager manager;
    private final long creationTime;
    private volatile long lastAccessedTime;
    private volatile int maxInactiveInterval;
    private volatile boolean newSession = true;
    private volatile boolean invalidated;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

    public HttpSessionImpl(String id, ServletContext ctx, SessionManager manager, int maxInactiveSeconds) {
        this.id = id;
        this.servletContext = ctx;
        this.manager = manager;
        this.creationTime = System.currentTimeMillis();
        this.lastAccessedTime = creationTime;
        this.maxInactiveInterval = maxInactiveSeconds;
    }

    void markAccessed() {
        if (invalidated) throw new IllegalStateException("session invalidated");
        this.lastAccessedTime = System.currentTimeMillis();
        this.newSession = false;
    }

    @Override public long getCreationTime() { checkValid(); return creationTime; }
    @Override public String getId() { return id; }
    @Override public long getLastAccessedTime() { checkValid(); return lastAccessedTime; }
    @Override public ServletContext getServletContext() { return servletContext; }
    @Override public void setMaxInactiveInterval(int interval) { this.maxInactiveInterval = interval; }
    @Override public int getMaxInactiveInterval() { return maxInactiveInterval; }

    @Override public Object getAttribute(String name) { checkValid(); return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(attributes.keySet());
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
        invalidated = true;
        // Unbind attributes before destroying to trigger binding listeners + attribute removed events.
        for (String name : Collections.list(Collections.enumeration(attributes.keySet()))) {
            Object v = attributes.remove(name);
            if (v instanceof HttpSessionBindingListener l) {
                l.valueUnbound(new HttpSessionBindingEvent(this, name, v));
            }
            manager.listenerRegistry().fireSessionAttributeRemoved(this, name, v);
        }
        manager.onInvalidate(this);
    }

    @Override public boolean isNew() { checkValid(); return newSession; }

    public boolean isInvalidated() { return invalidated; }

    private void checkValid() {
        if (invalidated) throw new IllegalStateException("session invalidated");
    }
}
