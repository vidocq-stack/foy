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

import io.vidocq.foy.spi.session.SessionStore;

import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates the creation, resolution, re-keying and expiration of {@link HttpSession}s of one
 * web application.
 *
 * <p>ID generation: 128 bits of {@link SecureRandom}, encoded in hex (32 chars).</p>
 * <p>Expiration (Servlet 6.1 section 7.5): a session idle beyond its maximum inactive interval,
 * and not in use by an in-flight request, is invalidated with the regular listeners
 * ({@code sessionDestroyed}, {@code valueUnbound}, {@code attributeRemoved}), either lazily when
 * a request asks for it ({@link #find}) or by the reaper ({@link #start()}), a single
 * virtual-thread scheduled task running every {@link #reaperPeriodFor min(60 s, max(1 s,
 * timeout / 2))}. {@link #close()} stops the reaper and invalidates every live session, firing
 * the same listeners: Foy does not persist sessions across deployments. After {@code close()} no
 * session can be created.</p>
 */
public final class SessionManager implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SessionManager.class.getName());

    public static final String COOKIE_NAME = "JSESSIONID";

    private final SessionStore store;
    private final SecureRandom random = new SecureRandom();
    private final ServletContext servletContext;
    private final int defaultMaxInactiveSeconds;
    private ListenerRegistry listenerRegistry = new ListenerRegistry();
    /** CDI session context (foy#21); set by the bootstrap before the deployment serves requests. */
    private volatile SessionLifecycleHook lifecycleHook = SessionLifecycleHook.NONE;
    /** Serialises the store re-keying of id changes with the removal of invalidated sessions. */
    private final Object renameLock = new Object();
    private ScheduledExecutorService reaper;
    private final ClassLoader applicationLoader;
    /** Set by {@link #close()}: no session is created afterwards. */
    private volatile boolean closed;
    /**
     * {@link #createNew()} holds the read lock from its {@code closed} check until
     * {@code sessionCreated} returned; {@link #close()} sets {@code closed} under the write lock.
     * So every session created before the close is in the store, fully announced, when close
     * scans it, and none is created afterwards.
     */
    private final java.util.concurrent.locks.ReadWriteLock closeLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    public SessionManager(SessionStore store, ServletContext servletContext,
                          int defaultMaxInactiveSeconds) {
        this.store = store;
        this.servletContext = servletContext;
        this.defaultMaxInactiveSeconds = defaultMaxInactiveSeconds;
        // Built during deployment: the application's class loader, set as the context class loader
        // of the reaper while it fires listeners.
        this.applicationLoader = servletContext == null ? null : servletContext.getClassLoader();
    }

    public void setListenerRegistry(ListenerRegistry registry) {
        this.listenerRegistry = registry;
    }

    public ListenerRegistry listenerRegistry() { return listenerRegistry; }

    /** Installs the internal session hook (the CDI session context); {@code null} removes it. */
    public void setLifecycleHook(SessionLifecycleHook hook) {
        this.lifecycleHook = hook == null ? SessionLifecycleHook.NONE : hook;
    }

    SessionLifecycleHook lifecycleHook() { return lifecycleHook; }

    /**
     * Resolves an existing session by its ID and begins an access by the calling request (the
     * request must {@linkplain HttpSessionImpl#endAccess() end} it). An expired session that no
     * request uses is invalidated, with its listeners, and reported as absent.
     */
    public HttpSessionImpl find(String id) {
        HttpSessionImpl impl = lookup(id);
        if (impl == null) return null;
        long now = System.currentTimeMillis();
        if (impl.tryAccess(now)) return impl;
        expireIfIdle(impl, now);
        return null;
    }

    /**
     * Resolves a session without beginning an access ({@code isRequestedSessionIdValid}); an
     * expired idle session is invalidated as in {@link #find}.
     */
    public HttpSessionImpl peek(String id) {
        HttpSessionImpl impl = lookup(id);
        if (impl == null) return null;
        if (expireIfIdle(impl, System.currentTimeMillis()) || impl.isInvalidated()) return null;
        return impl;
    }

    private HttpSessionImpl lookup(String id) {
        if (id == null) return null;
        HttpSession s = store.get(id).orElse(null);
        if (!(s instanceof HttpSessionImpl impl) || impl.isInvalidated()) return null;
        return impl;
    }

    /**
     * Creates a new session and stores it.
     *
     * @throws IllegalStateException once the manager is {@linkplain #close() closed} (the
     *         application is being undeployed): {@code getSession(true)} then throws it too
     */
    public HttpSessionImpl createNew() {
        closeLock.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("the web application is being undeployed");
            String id = generateId();
            HttpSessionImpl s = new HttpSessionImpl(id, servletContext, this, defaultMaxInactiveSeconds);
            store.put(s);
            // The hook first (foy#21): a session bean used from a sessionCreated listener must
            // resolve to this session, not re-enter getSession(true) and create another one.
            try {
                lifecycleHook.sessionCreated(s);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "session lifecycle hook failed on creation of session " + id, e);
            }
            listenerRegistry.fireSessionCreated(s);
            return s;
        } finally {
            closeLock.readLock().unlock();
        }
    }

    /**
     * {@code HttpServletRequest.changeSessionId} (section 7.3): gives {@code session} a fresh id,
     * re-keys it in the store (attributes, creation and access times are kept) and fires
     * {@code HttpSessionIdListener.sessionIdChanged} once. Returns the new id.
     *
     * <p>The change runs under the session's monitor, which every invalidation claim takes too:
     * a session whose invalidation already started is refused with
     * {@link IllegalStateException}, and an invalidation that starts during the change waits
     * until {@code sessionIdChanged} returned, so the listener never sees a destroyed session.
     * The store is re-keyed under {@code renameLock}, which {@link #onInvalidated} takes to
     * remove the session, so that a dead session is never re-inserted under its new id. Lock
     * order: session monitor, then {@code renameLock}.</p>
     */
    public String changeSessionId(HttpSessionImpl session) {
        synchronized (session) {
            if (!session.isLive()) throw new IllegalStateException("session invalidated");
            String oldId = session.getId();
            String newId;
            synchronized (renameLock) {
                do {
                    newId = generateId();
                } while (store.get(newId).isPresent());
                session.setId(newId);
                store.rename(oldId, session);
            }
            listenerRegistry.fireSessionIdChanged(session, oldId);
            return newId;
        }
    }

    /** Callback from {@link HttpSessionImpl#completeInvalidation()}. */
    void onInvalidated(HttpSessionImpl session) {
        synchronized (renameLock) {
            store.remove(session.getId());
        }
    }

    /** Expires {@code session} when it is idle beyond its interval and unused; reports it. */
    private boolean expireIfIdle(HttpSessionImpl session, long now) {
        if (!session.claimExpired(now)) return false;
        session.completeInvalidation();
        return true;
    }

    // ---- reaper ----

    /** The reaper period for a default timeout: {@code min(60 s, max(1 s, timeout / 2))}. */
    static Duration reaperPeriodFor(int timeoutSeconds) {
        if (timeoutSeconds <= 0) return Duration.ofSeconds(60);
        return Duration.ofSeconds(Math.min(60, Math.max(1, timeoutSeconds / 2)));
    }

    /** Starts the expiry reaper with the period derived from the default timeout. */
    public void start() {
        startReaper(reaperPeriodFor(defaultMaxInactiveSeconds));
    }

    /** Test seam: starts the reaper with an explicit period. */
    synchronized void startReaper(Duration period) {
        if (reaper != null) return;
        reaper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("foy-session-reaper").factory());
        long millis = Math.max(1, period.toMillis());
        reaper.scheduleWithFixedDelay(this::reapSafely, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Test seam for the integration tests of other modules (foy-it-weld, foy-cdi-vauban), where the
     * minute granularity of the session timeout makes the default period too long: replaces the
     * running reaper with one of {@code period}. A no-op once the manager is closed.
     */
    public synchronized void restartReaper(Duration period) {
        if (closed) return;
        if (reaper != null) reaper.shutdown();
        reaper = null;
        startReaper(period);
    }

    synchronized boolean isReaperRunning() {
        return reaper != null && !reaper.isShutdown();
    }

    /** Invalidates every expired, unused session of the store. */
    void reap() {
        long now = System.currentTimeMillis();
        for (HttpSession s : store.sessions()) {
            if (s instanceof HttpSessionImpl impl) expireIfIdle(impl, now);
        }
    }

    private void reapSafely() {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        // Listeners fired by the reaper run with the application's class loader, as on a request.
        if (applicationLoader != null) current.setContextClassLoader(applicationLoader);
        try {
            reap();
        } catch (RuntimeException e) {
            // A failing scan must not cancel the periodic task.
            LOG.log(System.Logger.Level.WARNING, "session expiry scan failed", e);
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    /**
     * Undeploy: stops the reaper, then invalidates every live session with its listeners. A
     * second call is a no-op for the reaper and finds no session left.
     */
    @Override
    public void close() {
        closeLock.writeLock().lock();
        try {
            closed = true;
        } finally {
            closeLock.writeLock().unlock();
        }
        ScheduledExecutorService r;
        synchronized (this) {
            r = reaper;
        }
        if (r != null) {
            // Let a scan in progress finish its listeners; interrupt it only if it overruns.
            r.shutdown();
            try {
                if (!r.awaitTermination(5, TimeUnit.SECONDS)) r.shutdownNow();
            } catch (InterruptedException e) {
                r.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        for (HttpSession s : store.sessions()) {
            if (s instanceof HttpSessionImpl impl && impl.claimInvalidation()) impl.completeInvalidation();
        }
    }

    public SessionStore store() { return store; }

    public int defaultMaxInactiveSeconds() { return defaultMaxInactiveSeconds; }

    private String generateId() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
