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
 * the same listeners: Foy does not persist sessions across deployments.</p>
 */
public final class SessionManager implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SessionManager.class.getName());

    public static final String COOKIE_NAME = "JSESSIONID";

    private final SessionStore store;
    private final SecureRandom random = new SecureRandom();
    private final ServletContext servletContext;
    private final int defaultMaxInactiveSeconds;
    private ListenerRegistry listenerRegistry = new ListenerRegistry();
    /** Serialises id changes, so that an id is never handed out twice. */
    private final Object renameLock = new Object();
    private ScheduledExecutorService reaper;

    public SessionManager(SessionStore store, ServletContext servletContext,
                          int defaultMaxInactiveSeconds) {
        this.store = store;
        this.servletContext = servletContext;
        this.defaultMaxInactiveSeconds = defaultMaxInactiveSeconds;
    }

    public void setListenerRegistry(ListenerRegistry registry) {
        this.listenerRegistry = registry;
    }

    public ListenerRegistry listenerRegistry() { return listenerRegistry; }

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

    /** Creates a new session and stores it. */
    public HttpSessionImpl createNew() {
        String id = generateId();
        HttpSessionImpl s = new HttpSessionImpl(id, servletContext, this, defaultMaxInactiveSeconds);
        store.put(s);
        listenerRegistry.fireSessionCreated(s);
        return s;
    }

    /**
     * {@code HttpServletRequest.changeSessionId} (section 7.3): gives {@code session} a fresh id,
     * re-keys it in the store (attributes, creation and access times are kept) and fires
     * {@code HttpSessionIdListener.sessionIdChanged} once. Returns the new id.
     */
    public String changeSessionId(HttpSessionImpl session) {
        String oldId;
        String newId;
        synchronized (renameLock) {
            if (session.isInvalidated()) throw new IllegalStateException("session invalidated");
            oldId = session.getId();
            do {
                newId = generateId();
            } while (store.get(newId).isPresent());
            session.setId(newId);
            store.rename(oldId, session);
        }
        listenerRegistry.fireSessionIdChanged(session, oldId);
        return newId;
    }

    /** Callback from {@link HttpSessionImpl#completeInvalidation()}. */
    void onInvalidated(HttpSessionImpl session) {
        store.remove(session.getId());
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
        try {
            reap();
        } catch (RuntimeException e) {
            // A failing scan must not cancel the periodic task.
            LOG.log(System.Logger.Level.WARNING, "session expiry scan failed", e);
        }
    }

    /**
     * Undeploy: stops the reaper, then invalidates every live session with its listeners. A
     * second call is a no-op for the reaper and finds no session left.
     */
    @Override
    public void close() {
        ScheduledExecutorService r;
        synchronized (this) {
            r = reaper;
        }
        if (r != null) {
            r.shutdownNow();
            try {
                r.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
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
