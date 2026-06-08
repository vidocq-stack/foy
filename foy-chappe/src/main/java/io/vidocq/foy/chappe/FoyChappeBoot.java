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
package io.vidocq.foy.chappe;

import io.vidocq.chappe.api.Handler;
import io.vidocq.foy.internal.boot.WebAppDiscovery;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterMapping;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.EventListener;
import java.util.List;
import java.util.Optional;

/**
 * Bootstrap Foy on Chappe HTTP transport.
 *
 * <p>Discovers the beans {@code @WebServlet}, {@code @WebFilter} and
 * {@code @WebListener} via le {@link BeanManager} fourni, monte la stack
 * Servlet 6.1 and exposes a {@link Handler} Chappe ready to be saved to
 * a {@code ChappeMountPoint}.</p>
 *
 * <h3>Example of usage</h3>
 * <pre>{@code
 * Optional<FoyChappeBoot.Mounted> opt = FoyChappeBoot.builder()
 *         .beanManager(CDI.current().getBeanManager())
 *         .contextPath("/app")
 *         .sessionTimeoutSeconds(1800)
 *         .build();
 * opt.ifPresent(mounted -> chappeMountPoint.mount(listener, mounted.mountPrefix(), mounted.handler()));
 * }</pre>
 *
 * <p>The {@link Optional} is empty if no Servlet/Filter/Listener bean has
 * been discovered (the application has no use).</p>
 */
public final class FoyChappeBoot {

    private static final System.Logger LOG = System.getLogger(FoyChappeBoot.class.getName());

    private FoyChappeBoot() {}

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Result of a successful bootstrap: a handler ready to mount and the
     * mount prefix to use ({@code ""} if contextPath is {@code "/"}).
     */
    public record Mounted(Handler handler, String mountPrefix, VidocqServletContext servletContext,
                          ListenerRegistry listenerRegistry) {

        /** Hook lifecycle to call after mount Chappe to notify listeners. */
        public void fireContextInitialized() {
            listenerRegistry.fireContextInitialized(servletContext);
        }

        /** Lifecycle hook to call before shutdown to notify listeners. */
        public void fireContextDestroyed() {
            listenerRegistry.fireContextDestroyed(servletContext);
        }
    }

    public static final class Builder {
        private BeanManager beanManager;
        private String contextPath = "/";
        private int sessionTimeoutSeconds = 30 * 60;

        public Builder beanManager(BeanManager bm) { this.beanManager = bm; return this; }
        public Builder contextPath(String path) { this.contextPath = path == null ? "/" : path; return this; }
        public Builder sessionTimeoutSeconds(int seconds) { this.sessionTimeoutSeconds = seconds; return this; }

        public Optional<Mounted> build() {
            if (beanManager == null) {
                throw new IllegalStateException("beanManager is required");
            }

            List<ServletDispatcher.Mapping> servletMappings = WebAppDiscovery.discoverServlets(beanManager);
            List<FilterMapping> filterMappings = WebAppDiscovery.discoverFilters(beanManager);
            List<EventListener> eventListeners = WebAppDiscovery.discoverListeners(beanManager);

            if (servletMappings.isEmpty() && filterMappings.isEmpty() && eventListeners.isEmpty()) {
                LOG.log(System.Logger.Level.INFO,
                        "No @WebServlet / @WebFilter / @WebListener beans discovered — Foy inactive");
                return Optional.empty();
            }

            ServletDispatcher dispatcher = new ServletDispatcher(servletMappings);
            FilterRegistry filterRegistry = new FilterRegistry(filterMappings);
            ListenerRegistry listeners = new ListenerRegistry();
            listeners.registerAll(eventListeners);

            VidocqServletContext servletContext = new VidocqServletContext(contextPath);
            servletContext.setListenerRegistry(listeners);
            SessionManager sessionManager = new SessionManager(
                    new InMemorySessionStore(), servletContext, sessionTimeoutSeconds);
            sessionManager.setListenerRegistry(listeners);

            ChappeServletBridge bridge = new ChappeServletBridge(
                    dispatcher, filterRegistry, servletContext, sessionManager, contextPath);

            String mountPrefix = "/".equals(contextPath) ? "" : contextPath;

            for (ServletDispatcher.Mapping m : servletMappings) {
                LOG.log(System.Logger.Level.INFO,
                        "Mapped servlet {0} -> {1}", m.servletName(), m.matcher().pattern());
            }
            for (FilterMapping m : filterMappings) {
                LOG.log(System.Logger.Level.INFO,
                        "Mapped filter {0} -> {1} [{2}]",
                        m.filterName(), m.matcher().pattern(), m.dispatcherTypes());
            }
            for (EventListener l : eventListeners) {
                LOG.log(System.Logger.Level.INFO, "Registered listener: {0}", l.getClass().getName());
            }

            return Optional.of(new Mounted(bridge, mountPrefix, servletContext, listeners));
        }
    }
}
