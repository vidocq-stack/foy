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

import io.vidocq.foy.internal.container.ContextLoaderScope;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.SessionExport.ExportedSession;
import io.vidocq.foy.internal.session.SessionExport.ValueTransferException;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionEvent;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 * the same listeners. After {@code close()} no session can be created.</p>
 * <p>A swap of the application in the same JVM (a dev reload) moves the sessions instead:
 * {@link #closeExporting()} undeploys like {@code close()} but exports the live sessions, and the
 * manager of the new deployment {@linkplain #importSessions imports} them through its own class
 * loader before it serves a request. A session that moves fires {@code sessionWillPassivate} and
 * {@code sessionDidActivate} (Servlet 6.1 section 7.7.2), and no lifecycle, attribute or binding
 * event. The export is a private format of this Foy version that never leaves the process.</p>
 */
public final class SessionManager implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SessionManager.class.getName());

    public static final String COOKIE_NAME = "JSESSIONID";

    /** The import scope of a manager that was given none: it runs the import as is. */
    private static final SessionImportScope RUN_AS_IS = new SessionImportScope() {
        @Override
        public <T> T around(Supplier<T> importer) {
            return importer.get();
        }
    };

    /**
     * What {@link #importSessions} did with the sessions of an export.
     *
     * @param imported the sessions put in the store
     * @param expired the sessions idle beyond their interval at the import, the swap time included
     * @param dropped the sessions left out because a value could not be read or their id is in use
     */
    public record SessionImport(int imported, int expired, int dropped) {}

    private final SessionStore store;
    private final SecureRandom random = new SecureRandom();
    private final ServletContext servletContext;
    private final int defaultMaxInactiveSeconds;
    private ListenerRegistry listenerRegistry = new ListenerRegistry();
    /** Serialises the store re-keying of id changes with the removal of invalidated sessions. */
    private final Object renameLock = new Object();
    private ScheduledExecutorService reaper;
    private final ClassLoader applicationLoader;
    /**
     * Set by {@link #close()} or {@link #closeExporting()}: no session is created or imported
     * afterwards. Guarded by {@link #creationLock}.
     */
    private boolean closed;
    /**
     * Guards {@link #closed} and {@link #creating}. {@link #createNew()} and
     * {@link #importSessions} check {@code closed} and count themselves under it, then store their
     * sessions and fire {@code sessionCreated} or {@code sessionDidActivate} after releasing it;
     * {@link #close()} sets {@code closed} and waits until no creation is being announced. So every
     * session created before the close is in the store, fully announced, when close scans it,
     * none is created afterwards, and no listener runs under the lock (BUG-20261009-14).
     */
    private final java.util.concurrent.locks.ReentrantLock creationLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition creationsAnnounced = creationLock.newCondition();
    /**
     * Creations and imports whose sessions are not stored and announced yet. Guarded by
     * {@link #creationLock}.
     */
    private int creating;
    /** Wraps every import: set by the deployment, for instance to bind its CDI container. */
    private final AtomicReference<SessionImportScope> importScope = new AtomicReference<>(RUN_AS_IS);
    /** The clock that dates an export and an import; a test seam. */
    private final AtomicReference<LongSupplier> clock = new AtomicReference<>(System::currentTimeMillis);

    public SessionManager(SessionStore store, ServletContext servletContext,
                          int defaultMaxInactiveSeconds) {
        this.store = store;
        this.servletContext = servletContext;
        this.defaultMaxInactiveSeconds = defaultMaxInactiveSeconds;
        // Built during deployment: the application's class loader, set as the context class loader
        // of the reaper while it fires listeners.
        // The deployment's loader, or none (the reaper then leaves the context loader untouched).
        this.applicationLoader = ContextLoaderScope.applicationLoaderOf(servletContext);
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

    /**
     * Creates a new session and stores it.
     *
     * @throws IllegalStateException once the manager is {@linkplain #close() closed} (the
     *         application is being undeployed): {@code getSession(true)} then throws it too
     */
    public HttpSessionImpl createNew() {
        beginCreation();
        try {
            HttpSessionImpl s = new HttpSessionImpl(generateId(), servletContext, this, defaultMaxInactiveSeconds);
            store.put(s);
            listenerRegistry.fireSessionCreated(s);
            return s;
        } finally {
            endCreation();
        }
    }

    /**
     * Counts a creation or an import that is about to store and announce sessions: the close waits
     * for it. Refused once the manager is closed.
     */
    private void beginCreation() {
        creationLock.lock();
        try {
            if (closed) throw new IllegalStateException("the web application is being undeployed");
            creating++;
        } finally {
            creationLock.unlock();
        }
    }

    /** The sessions of a creation or an import are stored and announced. */
    private void endCreation() {
        creationLock.lock();
        try {
            if (--creating == 0) creationsAnnounced.signalAll();
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * {@code HttpServletRequest.changeSessionId} (section 7.3): gives {@code session} a fresh id,
     * re-keys it in the store (attributes, creation and access times are kept) and fires
     * {@code HttpSessionIdListener.sessionIdChanged} once. Returns the new id.
     *
     * <p>The change is claimed under the session's monitor, which every invalidation claim takes
     * too: a session whose invalidation already started is refused with
     * {@link IllegalStateException}. {@code sessionIdChanged} then fires outside every lock; an
     * invalidation claimed meanwhile is completed (with {@code sessionDestroyed}) only once the
     * listener returned, so the listener never sees a destroyed session and never blocks the
     * invalidating thread (BUG-20261009-14). The store is re-keyed under {@code renameLock}, which
     * {@link #onInvalidated} takes to remove the session, so that a dead session is never
     * re-inserted under its new id. Lock order: session monitor, then {@code renameLock}.</p>
     */
    public String changeSessionId(HttpSessionImpl session) {
        String oldId;
        String newId;
        synchronized (session) {
            if (!session.isLive()) throw new IllegalStateException("session invalidated");
            oldId = session.getId();
            synchronized (renameLock) {
                do {
                    newId = generateId();
                } while (store.get(newId).isPresent());
                session.setId(newId);
                store.rename(oldId, session);
            }
            session.beginIdChange();
        }
        try {
            listenerRegistry.fireSessionIdChanged(session, oldId);
        } finally {
            session.endIdChange();
        }
        return newId;
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
        return Duration.ofSeconds(Math.clamp(timeoutSeconds / 2, 1, 60));
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
        // The periodic task never completes on its own and reapSafely catches its failures: its future is not needed.
        var _ = reaper.scheduleWithFixedDelay(this::reapSafely, millis, millis, TimeUnit.MILLISECONDS);
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
        // Listeners fired by the reaper run with the application's class loader, as on a request.
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
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
        shutDown();
        for (HttpSession s : store.sessions()) {
            if (s instanceof HttpSessionImpl impl && impl.claimInvalidation()) impl.completeInvalidation();
        }
    }

    /**
     * Refuses new creations and imports, waits for those being announced, then stops the reaper.
     * Returns {@code false} when the manager was already closed.
     */
    private boolean shutDown() {
        boolean wasOpen;
        creationLock.lock();
        try {
            wasOpen = !closed;
            closed = true;
            // Every session already stored is announced before the close ends or exports it.
            while (creating > 0) creationsAnnounced.awaitUninterruptibly();
        } finally {
            creationLock.unlock();
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
            } catch (InterruptedException _) {
                r.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        return wasOpen;
    }

    // ---- moving the sessions to another deployment ----

    /**
     * Undeploy of a swap: stops creations and the reaper as {@link #close()} does, then exports the
     * live sessions instead of invalidating them. Under the application's class loader, for each
     * session of the store:
     * <ul>
     *   <li>a session idle beyond its interval is invalidated with its listeners, as today, and not
     *       exported;</li>
     *   <li>any other live session, even one still used by a request whose grace ran out, is
     *       passivated ({@code sessionWillPassivate}) and written as the passivation left it, each
     *       value on its own. If an
     *       attribute cannot be written, the session does not move: it is activated back, then
     *       invalidated with its listeners, and one WARNING line names the attribute and its class.
     *       A container note that cannot be written is left out alone (one INFO line): its user
     *       logs in again;</li>
     *   <li>a session written leaves silently: no {@code sessionDestroyed}, {@code valueUnbound} or
     *       {@code attributeRemoved}. It leaves the store, and a stale reference to it throws
     *       {@link IllegalStateException}.</li>
     * </ul>
     *
     * @return the export, for {@link #importSessions}; an export of no session when the manager was
     *         already closed, by {@code close()} or by this method
     */
    public byte[] closeExporting() {
        if (!shutDown()) return SessionExport.empty();
        List<ExportedSession> exported = new ArrayList<>();
        // The listeners and the writeObject methods run with the application's class loader, as on a request.
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            long now = clock.get().getAsLong();
            for (HttpSession s : store.sessions()) {
                if (s instanceof HttpSessionImpl impl) export(impl, now, exported);
            }
        }
        return SessionExport.write(exported);
    }

    /** Exports one session into {@code exported}, or ends it as the close would. */
    private void export(HttpSessionImpl session, long now, List<ExportedSession> exported) {
        if (session.claimExpired(now)) {
            session.completeInvalidation();
            return;
        }
        if (!session.claimInvalidation()) return;
        Map<String, Object> passivated = session.attributeSnapshot();
        notifyActivationListeners(session, passivated, "sessionWillPassivate",
                HttpSessionActivationListener::sessionWillPassivate);
        // A passivation listener may have flushed its state into the session: write what it left.
        Map<String, byte[]> written = new LinkedHashMap<>();
        for (Map.Entry<String, Object> attribute : session.attributeSnapshot().entrySet()) {
            try {
                written.put(attribute.getKey(), SessionExport.serialize(attribute.getValue()));
            } catch (ValueTransferException e) {
                LOG.log(System.Logger.Level.WARNING, () -> label(session.getId())
                        + " cannot move to the new deployment: its attribute " + attribute.getKey()
                        + ", of class " + e.className() + ", cannot be written (" + e.getCause()
                        + "). The session ends here.");
                notifyActivationListeners(session, passivated, "sessionDidActivate",
                        HttpSessionActivationListener::sessionDidActivate);
                session.completeInvalidation();
                return;
            }
        }
        exported.add(new ExportedSession(session.getId(), session.getCreationTime(), session.getLastAccessedTime(),
                session.idleSince(now), session.getMaxInactiveInterval(), written, writeNotes(session)));
        session.depart();
    }

    /** Writes the container notes of {@code session}; a note that cannot be written is left out alone. */
    private static Map<String, byte[]> writeNotes(HttpSessionImpl session) {
        Map<String, byte[]> written = new LinkedHashMap<>();
        for (Map.Entry<String, Object> note : session.noteSnapshot().entrySet()) {
            try {
                written.put(note.getKey(), SessionExport.serialize(note.getValue()));
            } catch (ValueTransferException e) {
                LOG.log(System.Logger.Level.INFO, () -> label(session.getId())
                        + " moves without its container note of class " + e.className()
                        + ", which cannot be written (" + e.getCause() + "). A login it held must be done again.");
            }
        }
        return written;
    }

    /**
     * An export of no session, as {@link #closeExporting()} returns for a manager already closed: what a deployment
     * that hands over no session gives the next one.
     */
    public static byte[] emptyExport() {
        return SessionExport.empty();
    }

    /**
     * Imports the sessions of an export written by {@link #closeExporting()}. Call it before any
     * request reaches the deployment. The import runs inside the {@linkplain #importScope import
     * scope}, with {@code loader} as the context class loader, and every value is read through
     * {@code loader}. A session idle beyond its interval, counting from the end of its last request,
     * or from the export for a session that a request still used then, is {@code expired} and
     * skipped silently; a session with an attribute that cannot be read or reads back as
     * {@code null}, or whose id is already in the store, is {@code dropped} (one WARNING line gives
     * the count and the first cause), and a container note that cannot be read is left out alone.
     * An imported session keeps its id, its times, its interval, its attributes and notes, and is
     * not new; {@code sessionDidActivate} fires on its activation listeners, and no lifecycle,
     * attribute or binding event.
     *
     * @param export the bytes {@link #closeExporting()} returned
     * @param loader the application's class loader, through which every value is read
     * @return what was imported, expired and dropped
     * @throws IllegalArgumentException when {@code export} is not an export of this Foy version
     * @throws IllegalStateException once the manager is closed
     */
    public SessionImport importSessions(byte[] export, ClassLoader loader) {
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(loader, "loader");
        beginCreation();
        try {
            return importUnder(SessionExport.read(export), loader);
        } finally {
            endCreation();
        }
    }

    /**
     * Wraps every later {@linkplain #importSessions import}, the deserialization and the
     * {@code sessionDidActivate} listeners included; for instance to bind the CDI container that
     * rebinds the session's beans. Without one, the import runs as is.
     */
    public void importScope(SessionImportScope scope) {
        this.importScope.set(Objects.requireNonNull(scope, "scope"));
    }

    /** Test seam: the clock that dates the next export or import. */
    void clock(LongSupplier clock) {
        this.clock.set(clock);
    }

    private SessionImport importUnder(List<ExportedSession> sessions, ClassLoader loader) {
        try (var _ = ContextLoaderScope.enter(loader)) {
            return importScope.get().around(() -> importAll(sessions, loader));
        }
    }

    private SessionImport importAll(List<ExportedSession> sessions, ClassLoader loader) {
        long now = clock.get().getAsLong();
        var tally = new ImportTally();
        for (ExportedSession exported : sessions) importOne(exported, loader, now, tally);
        if (tally.dropped > 0) {
            LOG.log(System.Logger.Level.WARNING, () -> "Importing the HTTP sessions dropped " + tally.dropped
                    + " session(s) that could not be restored, as new ones start instead; first cause: "
                    + tally.firstCause);
        }
        return new SessionImport(tally.imported, tally.expired, tally.dropped);
    }

    private void importOne(ExportedSession exported, ClassLoader loader, long now, ImportTally tally) {
        if (exported.idleExpiredAt(now)) {
            tally.expired++;
            return;
        }
        if (store.get(exported.id()).isPresent()) {
            tally.drop("a session id already in use");
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> attribute : exported.attributes().entrySet()) {
            try {
                attributes.put(attribute.getKey(), SessionExport.deserialize(attribute.getValue(), loader));
            } catch (ValueTransferException e) {
                tally.drop(e.className() == null ? "an unreadable value" : "class " + e.className());
                return;
            }
        }
        HttpSessionImpl session = HttpSessionImpl.imported(servletContext, this, exported, attributes,
                readNotes(exported, loader));
        store.put(session);
        notifyActivationListeners(session, attributes, "sessionDidActivate",
                HttpSessionActivationListener::sessionDidActivate);
        tally.imported++;
    }

    /** Reads the container notes of an exported session; a note that cannot be read is left out alone. */
    private static Map<String, Object> readNotes(ExportedSession exported, ClassLoader loader) {
        Map<String, Object> notes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> note : exported.notes().entrySet()) {
            try {
                notes.put(note.getKey(), SessionExport.deserialize(note.getValue(), loader));
            } catch (ValueTransferException e) {
                LOG.log(System.Logger.Level.INFO, () -> label(exported.id())
                        + " moves without a container note of class " + e.className()
                        + ", which cannot be read (" + e.getCause() + "). A login it held must be done again.");
            }
        }
        return notes;
    }

    /**
     * Fires {@code event} on each value of {@code attributes} that is an
     * {@link HttpSessionActivationListener}; a listener that fails is logged and the others still run. An
     * {@link Error} counts as a failure of the listener, except a {@link VirtualMachineError}.
     */
    private static void notifyActivationListeners(HttpSessionImpl session, Map<String, Object> attributes,
            String eventName, BiConsumer<HttpSessionActivationListener, HttpSessionEvent> event) {
        for (Object value : attributes.values()) {
            if (value instanceof HttpSessionActivationListener listener) {
                try {
                    event.accept(listener, new HttpSessionEvent(session));
                } catch (VirtualMachineError e) {
                    throw e;
                } catch (RuntimeException | Error e) { // NOSONAR java:S1181 - deliberate: the listener's boundary
                    // A class of the old or the new layer whose initializer fails (an ExceptionInInitializerError, a
                    // LinkageError) fails this listener alone: the swap never fails on the application's code. An
                    // Error escaping here would end the undeploy halfway (the BUG-20261116-01 pattern).
                    LOG.log(System.Logger.Level.WARNING, () -> eventName + " failed on a value of class "
                            + value.getClass().getName() + " in " + label(session.getId()), e);
                }
            }
        }
    }

    /** A session as a log line names it: by the first 8 characters of its id, never the whole id. */
    private static String label(String id) {
        return "HTTP session " + HttpSessionImpl.idPrefix(id) + "...";
    }

    /** The counts of an import, and the cause of its first drop. */
    private static final class ImportTally {
        private int imported;
        private int expired;
        private int dropped;
        private String firstCause;

        void drop(String cause) {
            if (dropped == 0) firstCause = cause;
            dropped++;
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
