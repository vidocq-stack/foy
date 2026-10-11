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
import io.vidocq.foy.internal.boot.ApplicationSources;
import io.vidocq.foy.internal.boot.ApplicationSources.Initializer;
import io.vidocq.foy.internal.boot.ComponentFactory;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.DescriptorMerger;
import io.vidocq.foy.internal.boot.DescriptorMerger.AnnotatedComponents;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppDiscovery;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.container.ClassPathResourceProvider;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.gen.RegistryComponentFactory;
import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.internal.http.RequestLimits;
import io.vidocq.foy.internal.webxml.Fragment;
import io.vidocq.foy.internal.webxml.FragmentMerger;
import io.vidocq.foy.internal.webxml.FragmentOrderer;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import io.vidocq.foy.internal.listener.CdiRequestScopeListener;
import io.vidocq.foy.internal.security.InMemoryIdentityStore;
import io.vidocq.foy.internal.security.SecuritySettings;
import io.vidocq.foy.spi.cdi.CdiContextListeners;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.Set;

/**
 * Bootstrap Foy on Chappe HTTP transport.
 *
 * <p>Merges the CDI-discovered {@code @WebServlet}, {@code @WebFilter} and
 * {@code @WebListener} components with the {@code web.xml} descriptor (explicit stream,
 * else {@code WEB-INF/web.xml}, else {@code META-INF/web.xml} from the class loader) and the
 * web fragments of the jars on the class and module path, registers the
 * {@code ServletContainerInitializer}s found by {@link java.util.ServiceLoader} that the
 * fragment ordering retains, deploys the result through {@link WebAppDeployer} and exposes a {@link Handler} ready to be
 * mounted on Chappe.</p>
 *
 * <h3>Example of usage</h3>
 * <pre>{@code
 * Optional<FoyChappeBoot.Mounted> opt = FoyChappeBoot.builder()
 *         .beanManager(CDI.current().getBeanManager())
 *         .contextPath("/app")
 *         .sessionTimeoutSeconds(1800)
 *         .build();
 * opt.ifPresent(mounted -> routerBuilder.mount(mounted.mountPrefix(), mounted.handler(), false));
 * // on shutdown: mounted.close()
 * }</pre>
 *
 * <p>The handler dispatches on the full request path, context path included. Mount it so that
 * the prefix routes the request without being stripped ({@code stripPrefix=false}): a stripping
 * mount, such as Chappe's two-argument {@code Router.Builder.mount(prefix, handler)}, hides the
 * context path and every servlet under a non-root context path then answers 404.</p>
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
     * ({@code ""} if contextPath is {@code "/"}) and the live deployment. The prefix routes
     * requests to the handler and must not be stripped from the path it receives.
     */
    public record Mounted(Handler handler, String mountPrefix, Deployment deployment)
            implements AutoCloseable {

        /**
         * The live {@link ServletContext} of the deployed application. Returned as the Jakarta
         * type so that named-module consumers do not need to read foy-core.
         */
        public ServletContext servletContext() {
            return deployment.servletContext();
        }

        /**
         * The number of live HTTP sessions of the deployment, for observability. A cheap live
         * read of the session store ({@code SessionStore.size()}, a {@code ConcurrentHashMap}
         * count in the default store): no lock, no I/O and no expiry sweep, so a session that
         * expired but has not been reaped yet is still counted. It is {@code 0} once the
         * deployment is closed, which invalidates every session.
         */
        public int activeSessions() {
            return deployment.sessionManager().store().size();
        }

        /**
         * The class names of every listener the deployment holds, in registration order, for
         * observability: those of {@code web.xml}, {@code @WebListener}, the fragments, the
         * {@code ServletContainerInitializer}s and {@code addListener}, plus the container's
         * own listeners (they come first). Returned as an unmodifiable snapshot; reading it has
         * no side effect.
         */
        public List<String> listenerClassNames() {
            return deployment.listeners().classNames();
        }

        /** Destroys the application: servlets, filters, then {@code contextDestroyed}. */
        @Override
        public void close() {
            deployment.close();
        }
    }

    public static final class Builder {
        private BeanManager beanManager;
        private String contextPath;
        private int sessionTimeoutSeconds = 30 * 60;
        private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        private InputStream webXml;
        private boolean discoverPluggability = true;
        private final List<URL> applicationRoots = new ArrayList<>();
        private VidocqServletContext.ResourceProvider resourceProvider;
        private String virtualServerName = "vidocq";
        private IdentityStore identityStore;
        private InMemoryIdentityStore.Builder users;
        private int confidentialPort = -1;
        private boolean rotateSessionIdOnLogin = true;
        private RequestLimits requestLimits = RequestLimits.DEFAULTS;

        public Builder beanManager(BeanManager bm) { this.beanManager = bm; return this; }

        /**
         * Context path of the application; when never set, the merged descriptors'
         * {@code <default-context-path>} applies, else {@code "/"}.
         */
        public Builder contextPath(String path) { this.contextPath = path == null ? "/" : path; return this; }

        /**
         * {@code ServletContext.getVirtualServerName()} of the application (the host Foy serves it
         * on, as a container would name it); {@code "vidocq"} by default.
         */
        public Builder virtualServerName(String name) {
            this.virtualServerName = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Session timeout used only when neither web.xml nor any merged web fragment declares a
         * {@code <session-timeout>}; rounded up to whole minutes. Zero or less: sessions never
         * expire.
         */
        public Builder sessionTimeoutSeconds(int seconds) { this.sessionTimeoutSeconds = seconds; return this; }

        /** Seconds rounded up to whole minutes; zero or less gives 0 (sessions never expire). */
        static int sessionTimeoutMinutes(int seconds) {
            return seconds <= 0 ? 0 : (int) ((seconds + 59L) / 60);
        }

        /** Class loader used to load components and to look up {@code web.xml}. */
        public Builder classLoader(ClassLoader cl) { this.classLoader = cl; return this; }

        /** Explicit descriptor; wins over the class loader lookup. */
        public Builder webXml(InputStream in) { this.webXml = in; return this; }

        /**
         * Whether the pluggability sources visible to the class loader are discovered: the web
         * fragments ({@code META-INF/web-fragment.xml}) <em>and</em> the
         * {@link jakarta.servlet.ServletContainerInitializer}s ({@link java.util.ServiceLoader});
         * {@code true} by default. Embedders that assemble the application themselves turn it off.
         */
        public Builder discoverPluggability(boolean discover) { this.discoverPluggability = discover; return this; }

        /**
         * Declares jars or directories as the application itself (code-source URLs, compared
         * through {@code Fragment.sourceKey}): their initializers always run, whatever the
         * absolute ordering. The roots holding a {@code META-INF/web.xml} or
         * {@code WEB-INF/web.xml} without a fragment, and those of the CDI-discovered annotated
         * components, are application roots without being declared.
         */
        public Builder applicationRoot(URL... roots) {
            for (URL r : roots) applicationRoots.add(Objects.requireNonNull(r, "root"));
            return this;
        }

        /**
         * Source of the static resources behind {@code ServletContext.getResource*}. When never
         * set, a {@link ClassPathResourceProvider} serves the {@code META-INF/resources/} of the
         * application roots, then of the ordered fragments' jars, then of any other root of the
         * class loader, never those of a jar whose fragment the absolute ordering excludes.
         */
        public Builder resourceProvider(VidocqServletContext.ResourceProvider provider) {
            this.resourceProvider = provider;
            return this;
        }

        /**
         * The user base behind BASIC, FORM and {@code HttpServletRequest.login}. With neither this nor
         * {@link #user}, every login fails. Exclusive with {@link #user}.
         */
        public Builder identityStore(IdentityStore store) {
            this.identityStore = Objects.requireNonNull(store, "store");
            return this;
        }

        /**
         * Declares a user of Foy's in-memory identity store, with its application roles. The password is
         * digested at once (salted SHA-256) and not kept: the caller may wipe the array afterwards.
         */
        public Builder user(String name, char[] password, String... roles) {
            if (users == null) users = InMemoryIdentityStore.builder();
            users.user(name, password, roles);
            return this;
        }

        /**
         * HTTPS port a CONFIDENTIAL/INTEGRAL constraint redirects plain HTTP to; by default ({@code -1})
         * such a request gets 403.
         *
         * @throws IllegalArgumentException when {@code port} is neither {@code -1} nor a TCP port
         */
        public Builder confidentialPort(int port) {
            this.confidentialPort = SecuritySettings.checkConfidentialPort(port);
            return this;
        }

        /** Whether a successful login changes the session id (session fixation defence); {@code true} by default. */
        public Builder rotateSessionIdOnLogin(boolean rotate) { this.rotateSessionIdOnLogin = rotate; return this; }

        /**
         * Bytes Foy reads from a request body that declares no Content-Length (HTTP/1.1 chunked,
         * HTTP/2 without content-length) before the read fails with an {@code IOException}: 10 MiB by
         * default, {@code -1} for no ceiling. A body with a Content-Length is bounded by chappe
         * ({@code Server.Builder.maxRequestSize}, 10 MiB by default), never by this setting.
         *
         * @throws IllegalArgumentException when {@code bytes} is below {@code -1}
         */
        public Builder maxChunkedRequestBodySize(long bytes) {
            this.requestLimits = requestLimits.withMaxChunkedRequestBodySize(bytes);
            return this;
        }

        /**
         * Bytes of an {@code application/x-www-form-urlencoded} body parsed into request parameters:
         * 2 MiB by default, {@code -1} for no ceiling. A larger form makes {@code getParameter*}
         * throw {@code IllegalStateException} once; the query-string parameters stay available.
         *
         * @throws IllegalArgumentException when {@code bytes} is below {@code -1}
         */
        public Builder maxFormBodySize(long bytes) {
            this.requestLimits = requestLimits.withMaxFormBodySize(bytes);
            return this;
        }

        /**
         * Parts in one {@code multipart/form-data} body: 50 by default (CVE-2023-24998), {@code -1}
         * for no ceiling. Beyond, {@code getParts()} throws {@code IllegalStateException}.
         *
         * @throws IllegalArgumentException when {@code parts} is {@code 0} or below {@code -1}
         */
        public Builder maxMultipartParts(int parts) {
            this.requestLimits = requestLimits.withMaxMultipartParts(parts);
            return this;
        }

        /**
         * Bytes of the header section of one multipart part: 8 KiB by default. Beyond,
         * {@code getParts()} throws {@code IllegalStateException}.
         *
         * @throws IllegalArgumentException when {@code bytes} is below {@code 1}
         */
        public Builder maxMultipartPartHeaderSize(int bytes) {
            this.requestLimits = requestLimits.withMaxMultipartPartHeaderSize(bytes);
            return this;
        }

        SecuritySettings securitySettings() {
            if (identityStore != null && users != null) {
                throw new IllegalStateException("identityStore(...) and user(...) are exclusive");
            }
            IdentityStore store = identityStore != null ? identityStore
                    : users != null ? users.build() : IdentityStore.none();
            return new SecuritySettings(store, confidentialPort, rotateSessionIdOnLogin);
        }

        /**
         * Discovers and deploys the application: web.xml, then the fragments ordered per
         * §8.2.2 ({@link FragmentOrderer}), merged with the annotated components per §8.2.3
         * (the annotations of jars whose fragment is excluded by the ordering or
         * {@code metadata-complete} are dropped), then the container initializers the ordering
         * retains (§8.2.4, {@link ApplicationSources#ordering}), in {@link java.util.ServiceLoader}
         * order.
         *
         * <p>Under an absolute ordering without {@code <others/>}, the initializers of library
         * jars that carry no fragment are not run (see {@link #applicationRoot}). Any retained
         * initializer on the path is enough for Foy to start, even without servlet, filter,
         * listener or descriptor.</p>
         *
         * @throws IllegalStateException when both {@link #identityStore} and {@link #user} were called
         */
        public Optional<Mounted> build() throws ServletException {
            SecuritySettings security = securitySettings();
            ClassLoader loader = classLoader != null ? classLoader : FoyChappeBoot.class.getClassLoader();
            // One registry for discovery, web.xml classes, dynamic registrations and @HandlesTypes.
            WebComponentRegistry registry = WebComponentRegistry.forClassLoader(loader);
            ComponentFactory factory = new RegistryComponentFactory(registry, loader);
            AnnotatedComponents annotated;
            try {
                annotated = beanManager == null
                        ? AnnotatedComponents.none() : WebAppDiscovery.discover(beanManager, registry);
            } catch (IllegalArgumentException e) {
                // A component misusing the Servlet annotations: a deployment failure.
                throw new ServletException("Foy deployment failed: " + e.getMessage(), e);
            }
            WebAppDescriptor descriptor = loadDescriptor(loader);

            List<Fragment> ordered = List.of();
            List<Initializer> initializers = List.of();
            // Static resource roots, by priority: application roots, then the ordered fragments' jars.
            Set<URL> resourceRoots = new LinkedHashSet<>(applicationRoots);
            // Jars and directories whose classes @HandlesTypes may match (those with a class index are skipped).
            Set<URL> scanRoots = new LinkedHashSet<>(applicationRoots);
            // Jars of the fragments the ordering excludes: no annotation, initializer, resource or handled class.
            Set<URL> excluded = Set.of();
            if (discoverPluggability) {
                List<Fragment> fragments = ApplicationSources.fragments(loader);
                ordered = FragmentOrderer.order(descriptor.absoluteOrdering(), fragments);
                excluded = ApplicationSources.excludedJars(fragments, ordered);
                // First, so that an excluded jar's components never make it an application root.
                // metadata-complete fragments' jars are dropped by DescriptorMerger itself.
                annotated = annotated.excludingSources(excluded);
                Set<URL> fragmentJars = new LinkedHashSet<>();
                for (Fragment f : fragments) fragmentJars.add(f.jar());
                Set<URL> appRoots = new LinkedHashSet<>(applicationRoots);
                appRoots.addAll(ApplicationSources.applicationRoots(loader, fragmentJars));
                appRoots.addAll(ApplicationSources.codeSources(annotated));
                resourceRoots.addAll(appRoots);
                for (Fragment f : ordered) resourceRoots.add(f.jar());
                scanRoots.addAll(resourceRoots);
                try {
                    initializers = ApplicationSources.initializers(loader, ApplicationSources.ordering(
                            descriptor.absoluteOrdering(), ordered, fragmentJars, appRoots));
                } catch (ServiceConfigurationError e) {
                    throw new ServletException("Foy deployment failed: " + e.getMessage(), e);
                }
            }
            WebAppDescriptor effective = FragmentMerger.merge(descriptor, ordered);

            if (effective.isEmpty() && initializers.isEmpty() && annotated.servlets().isEmpty()
                    && annotated.filters().isEmpty() && annotated.listeners().isEmpty()) {
                LOG.log(System.Logger.Level.INFO,
                        "No @WebServlet / @WebFilter / @WebListener beans, web.xml, web fragment or "
                                + "ServletContainerInitializer discovered — Foy inactive");
                return Optional.empty();
            }

            String path = contextPath != null ? contextPath
                    : effective.defaultContextPath() != null ? effective.defaultContextPath() : "/";
            try {
                // The builder's and the descriptors' <default-context-path> alike.
                WebAppModel.validateContextPath(path);
            } catch (IllegalArgumentException e) {
                throw new ServletException("Foy deployment failed: " + e.getMessage(), e);
            }
            WebAppModel.Builder modelBuilder = WebAppModel.builder(path);
            DescriptorMerger.mergeMerged(effective, ordered, annotated, factory, modelBuilder);
            for (Initializer i : initializers) {
                modelBuilder.initializer(i.sci());
                if (i.jar() != null) scanRoots.add(i.jar());
            }
            // The merger copies the descriptors' timeout (-1 when absent); the builder default applies then.
            // A builder timeout of zero or less means "never expire" (0 minutes), as in web.xml; it
            // must not become -1, the model's "not configured" value.
            if (effective.sessionTimeoutMinutes() == -1) {
                modelBuilder.sessionTimeoutMinutes(sessionTimeoutMinutes(sessionTimeoutSeconds));
            }
            WebAppModel model = modelBuilder.build();

            Deployment deployment;
            try {
                VidocqServletContext.ResourceProvider resources = resourceProvider != null ? resourceProvider
                        : new ClassPathResourceProvider(loader, List.copyOf(resourceRoots), excluded);
                deployment = WebAppDeployer.deploy(model, DeployOptions
                        .defaults(loader, registry, ApplicationSources.scanRoots(scanRoots), excluded)
                        .withComponentFactory(factory).withResourceProvider(resources)
                        .withVirtualServerName(virtualServerName).withSecurity(security)
                        .withRequestLimits(requestLimits));
            } catch (RuntimeException e) {
                throw new ServletException("Foy deployment failed: " + e.getMessage(), e);
            }

            if (beanManager != null) {
                // The CDI request context, active for each request under any container (foy#18).
                deployment.listeners().addFirst(new CdiRequestScopeListener(beanManager));
                // The contexts CDI Lite leaves to the servlet container, the session context first,
                // driven by the adapter of the CDI implementation in use (foy#21).
                for (CdiContextListeners provider : ServiceLoader.load(CdiContextListeners.class, loader)) {
                    for (EventListener listener : provider.listeners(beanManager)) {
                        deployment.listeners().registerFirst(listener);
                    }
                }
            }

            String mountPrefix = "/".equals(path) ? "" : path;
            for (var f : ordered) {
                LOG.log(System.Logger.Level.INFO, "Merged web fragment {0} from {1}", f.id(), f.jar());
            }
            for (var i : initializers) {
                LOG.log(System.Logger.Level.INFO, "Registered ServletContainerInitializer: {0}",
                        i.sci().getClass().getName());
            }
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
