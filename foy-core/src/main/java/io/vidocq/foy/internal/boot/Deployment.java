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
 * {@code "the application is stopping"}: the container closed it (Jakarta WebSocket 2.2 section 2.1.5).
 * The other way round, the server's stop
 * cuts those connections first: their peers get no Close frame, and an endpoint may get
 * {@code onError} for the cut connection before an {@code onClose(1006)}.
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
     * reverse {@code init()} order (filters, then servlets), then the session reaper stops and every live session is
     * invalidated with its listeners ({@code sessionDestroyed}, {@code valueUnbound}; Foy does not
     * persist sessions across deployments), then {@code contextDestroyed}, then its temp dir is removed.
     * A second call is a no-op.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        undeploy(servletContext, listeners, initializedServlets, initializedFilters, tempDir, sessionManager);
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
        // The application's destroy(), session and context listeners run with its class loader as
        // context loader; the calling thread gets its own back (BUG-20261022-06).
        try (var _ = ContextLoaderScope.enter(servletContext.applicationClassLoader())) {
            undeployWithinScope(servletContext, contextListeners, initializedServlets, initializedFilters, tempDir,
                    sessions);
        }
    }

    private static void undeployWithinScope(VidocqServletContext servletContext, ListenerRegistry contextListeners,
                                            List<Servlet> initializedServlets, List<Filter> initializedFilters,
                                            Path tempDir, SessionManager sessions) {
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
        if (sessions != null) {
            try { sessions.close(); }
            catch (RuntimeException e) { warn("session invalidation failed", e); }
        }
        if (contextListeners != null) {
            try { contextListeners.fireContextDestroyed(servletContext); }
            catch (RuntimeException e) { warn("contextDestroyed failed", e); }
        }
        try { servletContext.closeJspEngineApplication(); }
        catch (RuntimeException failure) { warn("JSP application cleanup failed", failure); }
        if (tempDir != null) deleteRecursively(tempDir);
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
