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
package io.vidocq.foy.internal.boot;

import io.vidocq.chappe.api.Handler;
import io.vidocq.foy.internal.container.ContextLoaderScope;
import io.vidocq.foy.internal.container.CrossContextRegistry;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A deployed web application, produced by {@link WebAppDeployer#deploy}. It does not own the HTTP
 * server: {@link #close()} the deployment first, then stop the server. The undeploy closes the
 * application's upgraded connections while the server can still write, so a WebSocket peer gets its
 * 1001 Close frame and the endpoint its {@code onClose}, with 1006 and the reason
 * {@code "the application is stopping"}: the container closed it (Jakarta WebSocket 2.2 section 2.1.5). The
 * other way round, the server's stop cuts those connections first: their peers get no Close frame, and an
 * endpoint may get {@code onError} for the cut connection before an {@code onClose(1006)} with no reason.
 *
 * <p>{@link #close()} invalidates every live session. {@link #closeExportingSessions()} undeploys the same way but
 * exports the sessions instead, for {@link #importSessions(byte[])} into the next deployment.</p>
 */
public final class Deployment implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Deployment.class.getName());

    private final Handler handler;
    private final VidocqServletContext servletContext;
    private final ListenerRegistry listeners;
    private final List<Servlet> initializedServlets;
    private final List<Filter> initializedFilters;
    private final Path tempDir;
    private final SessionManager sessionManager;
    private final AtomicBoolean closed = new AtomicBoolean();

    Deployment(Handler handler, VidocqServletContext servletContext, ListenerRegistry listeners,
               List<Servlet> initializedServlets, List<Filter> initializedFilters, Path tempDir,
               SessionManager sessionManager) {
        this.handler = handler;
        this.servletContext = servletContext;
        this.listeners = listeners;
        this.initializedServlets = List.copyOf(initializedServlets);
        this.initializedFilters = List.copyOf(initializedFilters);
        this.tempDir = tempDir;
        this.sessionManager = sessionManager;
    }

    /** The request handler to mount on a Chappe server. */
    public Handler handler() { return handler; }

    public VidocqServletContext servletContext() { return servletContext; }

    public ListenerRegistry listeners() { return listeners; }

    /** The application's session manager. */
    public SessionManager sessionManager() { return sessionManager; }

    /** Servlets whose {@code init()} succeeded, in init order. */
    public List<Servlet> initializedServlets() { return initializedServlets; }

    /** Filters whose {@code init()} succeeded, in init order. */
    public List<Filter> initializedFilters() { return initializedFilters; }

    /**
     * Undeploys. From its start, a new request gets 503 with {@code Retry-After} set to
     * {@link VidocqServletContext#UNDEPLOY_RETRY_AFTER_SECONDS} and reaches no filter or servlet, and
     * {@code getContext} no longer finds the application; a cross-context dispatch into it gets a temporary
     * {@code UnavailableException}. Open upgraded
     * connections close first, all at once and for 3 s at most (their {@code HttpUpgradeHandler.destroy()}
     * runs, after a WebSocket's {@code onClose}); the requests in service get up to 2 s to finish; both
     * graces count from the start of the undeploy, so it reaches {@code destroy()} 3 s after it began at
     * most; then
     * Servlet 6.1 §2.3.4 — {@code destroy()} in
     * reverse {@code init()} order (filters, then servlets), then the session reaper stops and every live
     * session is invalidated with its listeners ({@code sessionDestroyed}, {@code valueUnbound}; to keep them
     * for the next deployment, call {@link #closeExportingSessions()} instead), then {@code contextDestroyed},
     * then its temp dir is removed.
     * A second call is a no-op.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        undeploy(parts(), SessionStep.INVALIDATE);
    }

    /**
     * Undeploys like {@link #close()}, in the same order, but the session step exports the live sessions instead
     * of invalidating them: no {@code sessionDestroyed} fires for a session that is written (see
     * {@link SessionManager#closeExporting()}). It shares the {@code close()} flag: a second call, or a call after
     * {@code close()}, even one still running, undeploys nothing and returns an export of no session; the sessions
     * stay with that {@code close()}, which ends them with {@code sessionDestroyed}. A failure of the session step
     * is logged and the undeploy goes on; the export is then that of no session.
     *
     * @return the export, for {@link #importSessions(byte[])} on the next deployment; its format is private
     */
    public byte[] closeExportingSessions() {
        // A close() under way (a stop during a swap) has not drained the requests or run destroy() yet: its
        // sessions are its own to end, so this call exports none of them.
        if (!closed.compareAndSet(false, true)) return SessionManager.emptyExport();
        return undeploy(parts(), SessionStep.EXPORT);
    }

    /**
     * Imports the sessions of an export written by {@link #closeExportingSessions()} of the previous deployment,
     * under this application's class loader, else, when the deployment named none, under the caller's context
     * class loader ({@link VidocqServletContext#getClassLoader()}). Call it before this deployment serves a
     * request. See {@link SessionManager#importSessions(byte[], ClassLoader)}.
     *
     * @param export bytes that {@code closeExportingSessions()} returned in this JVM
     * @return what was imported, expired and dropped
     * @throws IllegalArgumentException when {@code export} is not an export of this Foy version
     * @throws IllegalStateException once this deployment is closed
     */
    public SessionManager.SessionImport importSessions(byte[] export) {
        return sessionManager.importSessions(export, servletContext.getClassLoader());
    }

    private Parts parts() {
        return new Parts(servletContext, listeners, initializedServlets, initializedFilters, tempDir, sessionManager);
    }

    /**
     * Tears down a full or partial deployment. {@code contextListeners} is {@code null} when
     * {@code contextInitialized} was never fired, so {@code contextDestroyed} is skipped;
     * {@code tempDir} is {@code null} when none was created, {@code sessions} when the session
     * manager was not created yet.
     */
    static void undeploy(VidocqServletContext servletContext, ListenerRegistry contextListeners,
                         List<Servlet> initializedServlets, List<Filter> initializedFilters, Path tempDir,
                         SessionManager sessions) {
        undeploy(new Parts(servletContext, contextListeners, initializedServlets, initializedFilters, tempDir,
                sessions), SessionStep.INVALIDATE);
    }

    /** What an undeploy tears down. */
    private record Parts(VidocqServletContext servletContext, ListenerRegistry contextListeners,
                         List<Servlet> initializedServlets, List<Filter> initializedFilters, Path tempDir,
                         SessionManager sessions) {}

    /** What the session step does: invalidate the live sessions, or export them for the next deployment. */
    private enum SessionStep {
        INVALIDATE("session invalidation failed") {
            @Override byte[] run(SessionManager sessions) {
                sessions.close();
                return SessionManager.emptyExport();
            }
        },
        EXPORT("session export failed") {
            @Override byte[] run(SessionManager sessions) {
                return sessions.closeExporting();
            }
        };

        final String failure;

        SessionStep(String failure) { this.failure = failure; }

        /** Runs the step; returns the export, which is that of no session when the step invalidates. */
        abstract byte[] run(SessionManager sessions);
    }

    private static byte[] undeploy(Parts parts, SessionStep step) {
        // The application's destroy(), session and context listeners run with its class loader as
        // context loader; the calling thread gets its own back (BUG-20261022-06).
        try (var _ = ContextLoaderScope.enter(parts.servletContext().applicationClassLoader())) {
            return undeployWithinScope(parts, step);
        }
    }

    /** Returns the export when the session step exports and succeeded, an export of no session otherwise. */
    private static byte[] undeployWithinScope(Parts parts, SessionStep step) {
        VidocqServletContext servletContext = parts.servletContext();
        List<Servlet> initializedServlets = parts.initializedServlets();
        List<Filter> initializedFilters = parts.initializedFilters();
        SessionManager sessions = parts.sessions();
        ListenerRegistry contextListeners = parts.contextListeners();
        Path tempDir = parts.tempDir();
        // The graces of the upgraded connections and of the requests in service both count from here: the
        // requests keep running while the connections close, so the two waits overlap.
        long started = System.nanoTime();
        // From now on a new request gets 503 and reaches no filter or servlet (BUG-20261115-02).
        servletContext.refuseRequests();
        // Nor does a new cross-context dispatcher reach it; one obtained earlier passes the same gate in the
        // bridge (BUG-20261115-04).
        CrossContextRegistry.unregister(servletContext);
        // Upgraded connections next: their handlers' destroy(), and a WebSocket's onClose before it, run
        // while the application is intact (BUG-20261114-08).
        try { servletContext.closeUpgradedConnections(started); }
        catch (RuntimeException e) { warn("closing the upgraded connections failed", e); }
        // Servlet 6.1 section 2.3.4: the requests in service finish first, for a bounded time (BUG-20261115-03).
        servletContext.awaitRequestsInService(started);
        for (int i = initializedFilters.size() - 1; i >= 0; i--) {
            Filter f = initializedFilters.get(i);
            try { f.destroy(); } catch (RuntimeException e) { warn("destroy failed for filter " + f, e); }
        }
        for (int i = initializedServlets.size() - 1; i >= 0; i--) {
            Servlet s = initializedServlets.get(i);
            try { s.destroy(); } catch (RuntimeException e) { warn("destroy failed for servlet " + s, e); }
        }
        byte[] export = SessionManager.emptyExport();
        if (sessions != null) {
            try { export = step.run(sessions); }
            catch (RuntimeException e) { warn(step.failure, e); }
        }
        if (contextListeners != null) {
            try { contextListeners.fireContextDestroyed(servletContext); }
            catch (RuntimeException e) { warn("contextDestroyed failed", e); }
        }
        try { servletContext.closeJspEngineApplication(); }
        catch (RuntimeException failure) { warn("JSP application cleanup failed", failure); }
        if (tempDir != null) deleteRecursively(tempDir);
        return export;
    }

    /** Best effort: a failure is logged at FINE and the remaining entries are still tried. */
    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { LOG.log(System.Logger.Level.DEBUG, "cannot delete " + p, e); }
            });
        } catch (IOException | UncheckedIOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot delete temp dir " + root, e);
        }
    }

    private static void warn(String message, RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, message, e);
    }
}
