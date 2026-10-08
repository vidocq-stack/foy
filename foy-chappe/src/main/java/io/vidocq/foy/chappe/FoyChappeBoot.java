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
import io.vidocq.foy.internal.boot.ComponentFactory;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.DescriptorMerger;
import io.vidocq.foy.internal.boot.DescriptorMerger.AnnotatedComponents;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppDiscovery;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.gen.RegistryComponentFactory;
import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Bootstrap Foy on Chappe HTTP transport.
 *
 * <p>Merges the CDI-discovered {@code @WebServlet}, {@code @WebFilter} and
 * {@code @WebListener} components with the {@code web.xml} descriptor (explicit stream,
 * else {@code WEB-INF/web.xml}, else {@code META-INF/web.xml} from the class loader),
 * deploys the result through {@link WebAppDeployer} and exposes a {@link Handler} ready to be
 * mounted on Chappe.</p>
 *
 * <h3>Example of usage</h3>
 * <pre>{@code
 * Optional<FoyChappeBoot.Mounted> opt = FoyChappeBoot.builder()
 *         .beanManager(CDI.current().getBeanManager())
 *         .contextPath("/app")
 *         .sessionTimeoutSeconds(1800)
 *         .build();
 * opt.ifPresent(mounted -> chappeMountPoint.mount(listener, mounted.mountPrefix(), mounted.handler()));
 * // on shutdown: mounted.close()
 * }</pre>
 *
 * <p>The {@link Optional} is empty if the application declares no servlet, filter or listener.
 * {@code contextInitialized} fires during {@code build()}; {@link Mounted#close()} fires
 * {@code contextDestroyed}.</p>
 */
public final class FoyChappeBoot {

    private static final System.Logger LOG = System.getLogger(FoyChappeBoot.class.getName());

    private FoyChappeBoot() {}

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Result of a successful bootstrap: a handler ready to mount, the mount prefix to use
     * ({@code ""} if contextPath is {@code "/"}) and the live deployment.
     */
    public record Mounted(Handler handler, String mountPrefix, Deployment deployment)
            implements AutoCloseable {

        public VidocqServletContext servletContext() {
            return deployment.servletContext();
        }

        /** Destroys the application: servlets, filters, then {@code contextDestroyed}. */
        @Override
        public void close() {
            deployment.close();
        }
    }

    public static final class Builder {
        private BeanManager beanManager;
        private String contextPath = "/";
        private int sessionTimeoutSeconds = 30 * 60;
        private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        private InputStream webXml;

        public Builder beanManager(BeanManager bm) { this.beanManager = bm; return this; }
        public Builder contextPath(String path) { this.contextPath = path == null ? "/" : path; return this; }
        public Builder sessionTimeoutSeconds(int seconds) { this.sessionTimeoutSeconds = seconds; return this; }

        /** Class loader used to load components and to look up {@code web.xml}. */
        public Builder classLoader(ClassLoader cl) { this.classLoader = cl; return this; }

        /** Explicit descriptor; wins over the class loader lookup. */
        public Builder webXml(InputStream in) { this.webXml = in; return this; }

        public Optional<Mounted> build() throws ServletException {
            ClassLoader loader = classLoader != null ? classLoader : FoyChappeBoot.class.getClassLoader();
            // One registry for discovery, web.xml classes, dynamic registrations and @HandlesTypes.
            WebComponentRegistry registry = WebComponentRegistry.forClassLoader(loader);
            ComponentFactory factory = new RegistryComponentFactory(registry, loader);
            AnnotatedComponents annotated = beanManager == null
                    ? AnnotatedComponents.none() : WebAppDiscovery.discover(beanManager, registry);
            WebAppDescriptor descriptor = loadDescriptor(loader);

            if (descriptor.isEmpty() && annotated.servlets().isEmpty() && annotated.filters().isEmpty()
                    && annotated.listeners().isEmpty()) {
                LOG.log(System.Logger.Level.INFO,
                        "No @WebServlet / @WebFilter / @WebListener beans or web.xml discovered — Foy inactive");
                return Optional.empty();
            }

            WebAppModel.Builder modelBuilder = WebAppModel.builder(contextPath);
            DescriptorMerger.merge(descriptor, annotated, factory, modelBuilder);
            // The merger copies the web.xml timeout (-1 when absent); the builder default applies then.
            if (descriptor.sessionTimeoutMinutes() < 0) {
                modelBuilder.sessionTimeoutMinutes((sessionTimeoutSeconds + 59) / 60);
            }
            WebAppModel model = modelBuilder.build();

            Deployment deployment;
            try {
                deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(loader, registry).withComponentFactory(factory));
            } catch (RuntimeException e) {
                throw new ServletException("Foy deployment failed: " + e.getMessage(), e);
            }

            String mountPrefix = "/".equals(contextPath) ? "" : contextPath;
            for (var s : model.servlets()) {
                LOG.log(System.Logger.Level.INFO, "Mapped servlet {0} -> {1}", s.name(), s.urlPatterns());
            }
            for (var m : model.filterMappings()) {
                LOG.log(System.Logger.Level.INFO, "Mapped filter {0} -> {1} [{2}]", m.filterName(),
                        m.urlPattern() != null ? m.urlPattern() : "servlet:" + m.servletName(),
                        m.dispatcherTypes());
            }
            for (var l : model.listeners()) {
                LOG.log(System.Logger.Level.INFO, "Registered listener: {0}", l.type().getName());
            }
            return Optional.of(new Mounted(deployment.handler(), mountPrefix, deployment));
        }

        private WebAppDescriptor loadDescriptor(ClassLoader loader) throws ServletException {
            InputStream found = webXml;
            if (found == null) found = loader.getResourceAsStream("WEB-INF/web.xml");
            if (found == null) found = loader.getResourceAsStream("META-INF/web.xml");
            if (found == null) return WebAppDescriptor.empty();
            try (InputStream in = found) {
                return WebXmlParser.parse(in);
            } catch (IOException e) {
                throw new ServletException("Cannot read web.xml", e);
            }
        }
    }
}
