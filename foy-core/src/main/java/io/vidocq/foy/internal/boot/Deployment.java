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
 * A deployed web application, produced by {@link WebAppDeployer#deploy}. It does not own
 * the HTTP server: stop the server first, then {@link #close()} the deployment.
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
     * Undeploys: Servlet 6.1 §2.3.4 — {@code destroy()} in reverse {@code init()} order
     * (filters, then servlets), then the session reaper stops and every live session is
     * invalidated with its listeners ({@code sessionDestroyed}, {@code valueUnbound}; Foy does not
     * persist sessions across deployments), then {@code contextDestroyed}, then the context leaves
     * the cross-context registry and its temp dir is removed. A second call is a no-op.
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
        CrossContextRegistry.unregister(servletContext);
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
