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
package io.vidocq.foy.tck;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.ComponentFactory;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.spi.security.SecurityProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Servlet 6.1 conformance harness: deploys the configured application with
 * {@link WebAppDeployer}, serves it from a local Chappe {@link Server}, and exposes
 * an {@link HttpClient} so tests can issue real HTTP requests.
 *
 * <p>This harness acts as a servlet container for conformance test suites.
 * Official TCK integration (Arquillian DeployableContainer) builds on the
 * same integration point.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var harness = ServletTestHarness.builder()
 *         .servlet("/hello", new HelloServlet())
 *         .filter("/*", new LoggingFilter())
 *         .start();
 * try (harness) {
 *     HttpResponse<String> r = harness.get("/hello");
 *     assertEquals(200, r.statusCode());
 * }
 * }</pre>
 */
public final class ServletTestHarness implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ServletTestHarness.class.getName());

    private final Server server;
    private final int port;
    private final HttpClient client;
    private final String contextPath;
    private final Deployment deployment;

    private ServletTestHarness(Server server, int port, String contextPath, Deployment deployment) {
        this.server = server;
        this.port = port;
        this.contextPath = contextPath;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.deployment = deployment;
    }

    public int port() { return port; }
    public String baseUrl() { return "http://127.0.0.1:" + port + (contextPath.equals("/") ? "" : contextPath); }
    public HttpClient client() { return client; }

    public HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(5)).GET().build());
    }

    public HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(5));
    }

    @Override public void close() {
        if (server != null) server.stop();
        deployment.close();
    }

    public static Builder builder() { return new Builder(); }

    /** Fluid builder that configures a servlet application then starts Chappe. */
    public static final class Builder {

        /** A servlet being declared: one instance, every URL pattern registered under its name. */
        private record PendingServlet(Servlet instance, List<String> patterns,
                                      Map<String, String> initParams, boolean asyncSupported) {}

        /** A filter being declared: one instance under its name. */
        private record PendingFilter(Filter instance, Map<String, String> initParams) {}

        private final Map<String, PendingServlet> servlets = new LinkedHashMap<>();
        private final Map<String, PendingFilter> filters = new LinkedHashMap<>();
        private final List<FilterMappingDecl> filterMappings = new ArrayList<>();
        private final List<EventListener> listeners = new ArrayList<>();
        private final ErrorPageRegistry errorPages = new ErrorPageRegistry();
        private String contextPath = "/";
        private SecurityProvider securityProvider;
        private Map<String, String> localeEncodingMappings = Map.of();
        private final Map<String, String> contextInitParams = new LinkedHashMap<>();

        public Builder localeEncodingMappings(Map<String, String> m) {
            this.localeEncodingMappings = m == null ? Map.of() : Map.copyOf(m);
            return this;
        }

        public Builder contextInitParam(String name, String value) {
            contextInitParams.put(name, value);
            return this;
        }

        public Builder contextInitParams(Map<String, String> params) {
            if (params != null) contextInitParams.putAll(params);
            return this;
        }

        private int effectiveMajor = 6, effectiveMinor = 1;
        public Builder effectiveVersion(int major, int minor) {
            this.effectiveMajor = major; this.effectiveMinor = minor; return this;
        }

        private int sessionTimeoutMinutes = -1;
        public Builder sessionTimeoutMinutes(int minutes) {
            this.sessionTimeoutMinutes = minutes; return this;
        }

        private final Set<String> reservedServletNames = new HashSet<>();
        private final Set<String> reservedFilterNames = new HashSet<>();
        private final Set<String> reservedUrlPatterns = new HashSet<>();
        public Builder reservedServletName(String n) { reservedServletNames.add(n); return this; }
        public Builder reservedFilterName(String n) { reservedFilterNames.add(n); return this; }
        public Builder reservedUrlPattern(String p) { reservedUrlPatterns.add(p); return this; }

        private Set<String> warClassNames = null; // null = no isolation
        /** Restricts dynamic registrations instantiated by name/class to
         *  classes actually present in the WAR, simulating an isolated
         *  WebAppClassLoader without creating a separate ClassLoader. */
        public Builder restrictToWarClasses(Set<String> classNames) {
            this.warClassNames = classNames == null ? null : Set.copyOf(classNames);
            return this;
        }

        public Builder servlet(String urlPattern, Servlet servlet) {
            return servlet(urlPattern, servlet, Map.of());
        }

        public Builder servlet(String urlPattern, Servlet servlet, Map<String, String> servletInitParams) {
            String name = defaultName(servlet, servlets, PendingServlet::instance);
            return servlet(urlPattern, servlet, name, servletInitParams);
        }

        public Builder servlet(String urlPattern, Servlet servlet, String servletName,
                               Map<String, String> servletInitParams) {
            return servlet(urlPattern, servlet, servletName, servletInitParams, true);
        }

        /**
         * Maps {@code urlPattern} to the servlet named {@code servletName}. Registering the same
         * name again appends the pattern; when an explicit name comes back with another instance,
         * the first registration wins (instance, init parameters and async support) and a
         * warning is logged.
         */
        public Builder servlet(String urlPattern, Servlet servlet, String servletName,
                               Map<String, String> servletInitParams, boolean asyncSupported) {
            PendingServlet existing = servlets.get(servletName);
            if (existing == null) {
                var patterns = new ArrayList<String>();
                patterns.add(urlPattern);
                servlets.put(servletName, new PendingServlet(servlet, patterns,
                        Map.copyOf(servletInitParams), asyncSupported));
            } else {
                if (existing.instance() != servlet) {
                    LOG.log(System.Logger.Level.WARNING, "servlet name '" + servletName
                            + "' registered with another instance; keeping the first one");
                }
                existing.patterns().add(urlPattern);
            }
            return this;
        }

        public Builder filter(String urlPattern, Filter filter) {
            return filter(urlPattern, filter, Map.of());
        }

        public Builder filter(String urlPattern, Filter filter, Map<String, String> filterInitParams) {
            return filter(urlPattern, filter, defaultName(filter, filters, PendingFilter::instance), filterInitParams);
        }

        /**
         * The name of a component registered without one: its simple class name, made unique
         * with a {@code #n} suffix when another instance already holds it (anonymous classes
         * all share the empty simple name). Re-registering the same instance reuses its name.
         */
        private static <P> String defaultName(Object instance, Map<String, P> declared,
                                              Function<P, Object> instanceOf) {
            String base = instance.getClass().getSimpleName();
            String name = base;
            for (int n = 2; declared.containsKey(name); n++) {
                if (instanceOf.apply(declared.get(name)) == instance) return name;
                name = base + "#" + n;
            }
            return name;
        }

        public Builder filter(String urlPattern, Filter filter, String filterName,
                              Map<String, String> filterInitParams) {
            return filter(urlPattern, filter, filterName, filterInitParams, EnumSet.of(DispatcherType.REQUEST));
        }

        /**
         * Maps {@code urlPattern} to the filter named {@code filterName}; each call adds one
         * mapping, in call order. When an explicit name comes back with another instance, the
         * first registration wins and a warning is logged.
         */
        public Builder filter(String urlPattern, Filter filter, String filterName,
                              Map<String, String> filterInitParams, Set<DispatcherType> dispatcherTypes) {
            PendingFilter existing = filters.get(filterName);
            if (existing == null) {
                filters.put(filterName, new PendingFilter(filter, Map.copyOf(filterInitParams)));
            } else if (existing.instance() != filter) {
                LOG.log(System.Logger.Level.WARNING, "filter name '" + filterName
                        + "' registered with another instance; keeping the first one");
            }
            filterMappings.add(new FilterMappingDecl(filterName, urlPattern, null, dispatcherTypes));
            return this;
        }

        public Builder listener(EventListener listener) { listeners.add(listener); return this; }

        public Builder errorPage(int status, String location) {
            errorPages.register(status, location); return this;
        }

        public Builder errorPage(Class<? extends Throwable> type, String location) {
            errorPages.register(type, location); return this;
        }

        public Builder contextPath(String path) { this.contextPath = path; return this; }
        public Builder securityProvider(SecurityProvider p) { this.securityProvider = p; return this; }

        private VidocqServletContext.ResourceProvider resourceProvider;
        public Builder resourceProvider(VidocqServletContext.ResourceProvider provider) {
            this.resourceProvider = provider; return this;
        }

        private String servletContextName;
        public Builder servletContextName(String n) { this.servletContextName = n; return this; }

        private final List<ServletContainerInitializer> sciList = new ArrayList<>();
        public Builder servletContainerInitializer(ServletContainerInitializer sci) {
            if (sci != null) sciList.add(sci);
            return this;
        }

        public ServletTestHarness start() {
            var model = toModel();
            var cl = Thread.currentThread().getContextClassLoader();
            var factory = warClassNames == null
                    ? ComponentFactory.reflective(cl)
                    : ComponentFactory.reflective(cl, warClassNames);
            var options = DeployOptions.defaults(cl)
                    .withComponentFactory(factory)
                    .withSecurityProvider(securityProvider)
                    .withResourceProvider(resourceProvider)
                    .withServletContextName(servletContextName)
                    .withReserved(reservedServletNames, reservedFilterNames, reservedUrlPatterns);
            Deployment d = WebAppDeployer.deploy(model, options);
            int port = startServerWithRetry(d.handler());
            return new ServletTestHarness(currentServer, port, contextPath, d);
        }

        /** The accumulated configuration as a deployment description; components are pre-built instances. */
        private WebAppModel toModel() {
            var b = WebAppModel.builder(contextPath)
                    .errorPages(errorPages)
                    .localeEncodingMappings(localeEncodingMappings)
                    .effectiveVersion(effectiveMajor, effectiveMinor)
                    .sessionTimeoutMinutes(sessionTimeoutMinutes);
            contextInitParams.forEach(b::contextParam);
            servlets.forEach((name, s) -> b.servlet(new ServletDecl(name,
                    s.instance().getClass(), s::instance, s.patterns(), s.initParams(),
                    Integer.MIN_VALUE, s.asyncSupported())));
            filters.forEach((name, f) -> b.filter(new FilterDecl(name,
                    f.instance().getClass(), f::instance, f.initParams(), true)));
            filterMappings.forEach(b::filterMapping);
            for (EventListener l : listeners) {
                b.listener(new ListenerDecl(l.getClass(), () -> l));
            }
            sciList.forEach(b::initializer);
            return b.build();
        }

        private Server currentServer;

        private int startServerWithRetry(Handler handler) {
            RuntimeException last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                int port;
                try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                catch (Exception e) { throw new RuntimeException(e); }
                try {
                    // Short keep-alive idle timeout: some TCK clients (6.1.0
                    // TrailerTest) read the response to EOF on a keep-alive
                    // connection and rely on the container closing it — 60 s
                    // (chappe default) would add a minute per such test.
                    Server server = Server.builder().host("127.0.0.1").port(port)
                            .idleTimeout(java.time.Duration.ofSeconds(5))
                            .handler(handler).build();
                    server.start();
                    currentServer = server;
                    return port;
                } catch (RuntimeException e) { last = e; }
            }
            throw last;
        }
    }
}
