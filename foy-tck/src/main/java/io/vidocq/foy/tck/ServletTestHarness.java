package io.vidocq.foy.tck;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterMapping;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.spi.security.SecurityProvider;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

/**
 * Servlet 6.1 conformance harness: starts a local Chappe {@link Server}
 * wired with a fully configured {@link ChappeServletBridge}, and exposes
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

    private final Server server;
    private final int port;
    private final HttpClient client;
    private final String contextPath;

    private final java.util.List<jakarta.servlet.Servlet> initializedServlets;
    private final java.util.List<jakarta.servlet.Filter> initializedFilters;
    private final io.vidocq.foy.internal.listener.ListenerRegistry listenerRegistry;
    private final io.vidocq.foy.internal.container.VidocqServletContext servletContext;

    private ServletTestHarness(Server server, int port, String contextPath) {
        this(server, port, contextPath, java.util.List.of(), java.util.List.of(), null, null);
    }

    private ServletTestHarness(Server server, int port, String contextPath,
                               java.util.List<jakarta.servlet.Servlet> initializedServlets,
                               java.util.List<jakarta.servlet.Filter> initializedFilters,
                               io.vidocq.foy.internal.listener.ListenerRegistry listenerRegistry,
                               io.vidocq.foy.internal.container.VidocqServletContext servletContext) {
        this.server = server;
        this.port = port;
        this.contextPath = contextPath;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.initializedServlets = initializedServlets;
        this.initializedFilters = initializedFilters;
        this.listenerRegistry = listenerRegistry;
        this.servletContext = servletContext;
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
        // Cycle de vie Servlet 6.1 §2.3.4 : destroy() en ordre inverse d'init().
        for (int i = initializedFilters.size() - 1; i >= 0; i--) {
            try { initializedFilters.get(i).destroy(); } catch (RuntimeException ignored) {}
        }
        for (int i = initializedServlets.size() - 1; i >= 0; i--) {
            try { initializedServlets.get(i).destroy(); } catch (RuntimeException ignored) {}
        }
        if (listenerRegistry != null && servletContext != null) {
            try { listenerRegistry.fireContextDestroyed(servletContext); } catch (RuntimeException ignored) {}
        }
        if (servletContext != null) {
            io.vidocq.foy.internal.container.CrossContextRegistry.unregister(servletContext);
        }
    }

    public static Builder builder() { return new Builder(); }

    /** Fluid builder that configures a servlet application then starts Chappe. */
    public static final class Builder {
        private final List<ServletDispatcher.Mapping> servlets = new ArrayList<>();
        private final List<FilterMapping> filters = new ArrayList<>();
        private final List<EventListener> listeners = new ArrayList<>();
        private final ErrorPageRegistry errorPages = new ErrorPageRegistry();
        // initParams par identité d'instance (servlet ou filter) — utilisés par start() lors du init()
        private final java.util.IdentityHashMap<Object, java.util.Map<String, String>> initParams =
                new java.util.IdentityHashMap<>();
        private String contextPath = "/";
        private SecurityProvider securityProvider;
        private java.util.Map<String, String> localeEncodingMappings = java.util.Map.of();
        private java.util.Map<String, String> contextInitParams = new java.util.LinkedHashMap<>();

        public Builder localeEncodingMappings(java.util.Map<String, String> m) {
            this.localeEncodingMappings = m == null ? java.util.Map.of() : java.util.Map.copyOf(m);
            return this;
        }

        public Builder contextInitParam(String name, String value) {
            contextInitParams.put(name, value);
            return this;
        }

        public Builder contextInitParams(java.util.Map<String, String> params) {
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

        private final java.util.Set<String> reservedServletNames = new java.util.HashSet<>();
        private final java.util.Set<String> reservedFilterNames = new java.util.HashSet<>();
        private final java.util.Set<String> reservedUrlPatterns = new java.util.HashSet<>();
        public Builder reservedServletName(String n) { reservedServletNames.add(n); return this; }
        public Builder reservedFilterName(String n) { reservedFilterNames.add(n); return this; }
        public Builder reservedUrlPattern(String p) { reservedUrlPatterns.add(p); return this; }

        private java.util.Set<String> warClassNames = null; // null = pas d'isolation
        /** Restricts dynamic registrations instantiated by name/class to
         *  classes actually present in the WAR, simulating an isolated
         *  WebAppClassLoader without creating a separate ClassLoader. */
        public Builder restrictToWarClasses(java.util.Set<String> classNames) {
            this.warClassNames = classNames == null ? null : java.util.Set.copyOf(classNames);
            return this;
        }

        public Builder servlet(String urlPattern, jakarta.servlet.Servlet servlet) {
            return servlet(urlPattern, servlet, java.util.Map.of());
        }

        public Builder servlet(String urlPattern, jakarta.servlet.Servlet servlet,
                               java.util.Map<String, String> servletInitParams) {
            return servlet(urlPattern, servlet, servlet.getClass().getSimpleName(), servletInitParams);
        }

        public Builder servlet(String urlPattern, jakarta.servlet.Servlet servlet, String servletName,
                               java.util.Map<String, String> servletInitParams) {
            return servlet(urlPattern, servlet, servletName, servletInitParams, true);
        }

        public Builder servlet(String urlPattern, jakarta.servlet.Servlet servlet, String servletName,
                               java.util.Map<String, String> servletInitParams, boolean asyncSupported) {
            servlets.add(new ServletDispatcher.Mapping(
                    io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of(urlPattern),
                    servlet, servletName, asyncSupported));
            initParams.put(servlet, java.util.Map.copyOf(servletInitParams));
            return this;
        }

        public Builder filter(String urlPattern, jakarta.servlet.Filter filter) {
            return filter(urlPattern, filter, java.util.Map.of());
        }

        public Builder filter(String urlPattern, jakarta.servlet.Filter filter,
                              java.util.Map<String, String> filterInitParams) {
            return filter(urlPattern, filter, filter.getClass().getSimpleName(), filterInitParams);
        }

        public Builder filter(String urlPattern, jakarta.servlet.Filter filter, String filterName,
                              java.util.Map<String, String> filterInitParams) {
            return filter(urlPattern, filter, filterName, filterInitParams,
                    java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        }

        public Builder filter(String urlPattern, jakarta.servlet.Filter filter, String filterName,
                              java.util.Map<String, String> filterInitParams,
                              java.util.Set<jakarta.servlet.DispatcherType> dispatcherTypes) {
            filters.add(new FilterMapping(
                    io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of(urlPattern),
                    filter, filterName,
                    dispatcherTypes == null || dispatcherTypes.isEmpty()
                            ? java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST)
                            : java.util.EnumSet.copyOf(dispatcherTypes)));
            initParams.put(filter, java.util.Map.copyOf(filterInitParams));
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

        private io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider resourceProvider;
        public Builder resourceProvider(
                io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider provider) {
            this.resourceProvider = provider; return this;
        }

        private String servletContextName;
        public Builder servletContextName(String n) { this.servletContextName = n; return this; }

        private final java.util.List<jakarta.servlet.ServletContainerInitializer> sciList = new ArrayList<>();
        public Builder servletContainerInitializer(jakarta.servlet.ServletContainerInitializer sci) {
            if (sci != null) sciList.add(sci);
            return this;
        }

        public ServletTestHarness start() {
            VidocqServletContext ctx = new VidocqServletContext(contextPath);
            ctx.setErrorPages(errorPages);
            ctx.setLocaleEncodingMappings(localeEncodingMappings);
            ctx.setEffectiveVersion(effectiveMajor, effectiveMinor);
            if (resourceProvider != null) ctx.setResourceProvider(resourceProvider);
            if (servletContextName != null) ctx.setServletContextName(servletContextName);
            if (sessionTimeoutMinutes > 0) ctx.setSessionTimeoutInternal(sessionTimeoutMinutes);
            for (String n : reservedServletNames) ctx.reserveServletName(n);
            for (String n : reservedFilterNames) ctx.reserveFilterName(n);
            for (String p : reservedUrlPatterns) ctx.reserveUrlPattern(p);
            // Expose les servlets/filtres déclarés en web.xml/@WebServlet via
            // ServletContext.getServletRegistrations() — visibilité exigée par
            // le TCK (RegistrationTests.servletRegistrationsTest).
            materializeStaticRegistrations(ctx);
            // Init params du <context-param> (web.xml) — doivent être posés avant markInitialized.
            for (var e : contextInitParams.entrySet()) ctx.setInitParameter(e.getKey(), e.getValue());
            // Servlet 6.1 §4.8.1 : attribut "jakarta.servlet.context.tempdir" requis.
            try {
                java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("vidocq-servlet-");
                tmp.toFile().deleteOnExit();
                ctx.setAttribute("jakarta.servlet.context.tempdir", tmp.toFile());
            } catch (java.io.IOException ignored) {}
            ListenerRegistry registry = new ListenerRegistry();
            registry.registerAll(listeners);
            ctx.setListenerRegistry(registry);
            if (securityProvider != null) ctx.setSecurityProvider(securityProvider);

            SessionManager sessions = new SessionManager(new InMemorySessionStore(), ctx, 1800);
            sessions.setListenerRegistry(registry);

            // Servlet 6.1 §4.4 : les SCI onStartup() sont appelés avant contextInitialized
            // des listeners. Pendant onStartup, les API dynamiques (addListener etc.) sont
            // autorisées (le context n'est pas encore "initialized" au sens de §4.4).
            for (var sci : sciList) {
                try { sci.onStartup(null, ctx); }
                catch (jakarta.servlet.ServletException e) {
                    System.err.println("[ServletTestHarness] SCI.onStartup failed ("
                            + sci.getClass().getName() + "): " + e.getMessage());
                }
            }

            registry.fireContextInitialized(ctx);

            // Matérialisation des registrations dynamiques (SCI + listener-initialized) :
            // on les transfère dans la liste des servlets/filters avant la phase init().
            materializeDynamicRegistrations(ctx);

            // Cycle de vie Servlet 6.1 §2.3 : init() avant la première requête.
            // Les servlets dont init échoue (UnavailableException etc.) sont exclus du dispatcher
            // — les requêtes vers eux tomberont sur le 404 par défaut. La spec §2.3.3.2 tolère ce
            // comportement en mode non-permanent.
            var initialized = new java.util.ArrayList<jakarta.servlet.Servlet>();
            var liveServlets = new java.util.ArrayList<ServletDispatcher.Mapping>();
            for (var m : servlets) {
                var params = initParams.getOrDefault(m.servlet(), java.util.Map.of());
                var cfg = new io.vidocq.foy.internal.container.ServletConfigImpl(
                        m.servletName(), ctx, params);
                try {
                    m.servlet().init(cfg);
                    initialized.add(m.servlet());
                    liveServlets.add(m);
                } catch (jakarta.servlet.ServletException e) {
                    // Servlet 6.1 §2.3.3 : un servlet dont init() a failé doit renvoyer
                    // 500 (ou 503) à toute requête ultérieure, pas 404. On substitue un
                    // stub qui émet le 500 plutôt que d'exclure du dispatcher.
                    System.err.println("[ServletTestHarness] init failed for "
                            + m.servletName() + ": " + e.getMessage());
                    // Re-throw la ServletException à chaque requête — permet aux
                    // <error-page> mappées sur jakarta.servlet.ServletException d'être
                    // activées (TCK GenericServletTests attend ce dispatch).
                    // Cas particulier §2.3.3.2 : UnavailableException permanent → 404,
                    // temporary → 503.
                    final jakarta.servlet.ServletException initFailure = e;
                    jakarta.servlet.Servlet stub = new jakarta.servlet.GenericServlet() {
                        @Override public void service(jakarta.servlet.ServletRequest req,
                                                      jakarta.servlet.ServletResponse res)
                                throws jakarta.servlet.ServletException, java.io.IOException {
                            if (initFailure instanceof jakarta.servlet.UnavailableException ue) {
                                var http = (jakarta.servlet.http.HttpServletResponse) res;
                                if (ue.isPermanent()) {
                                    http.sendError(404, ue.getMessage());
                                } else {
                                    http.sendError(503, ue.getMessage());
                                }
                                return;
                            }
                            throw initFailure;
                        }
                    };
                    liveServlets.add(new ServletDispatcher.Mapping(
                            m.matcher(), stub, m.servletName()));
                }
            }
            var initializedFilters = new java.util.ArrayList<jakarta.servlet.Filter>();
            var liveFilters = new java.util.ArrayList<FilterMapping>();
            for (var fm : filters) {
                var params = initParams.getOrDefault(fm.filter(), java.util.Map.of());
                try {
                    fm.filter().init(new io.vidocq.foy.internal.container.FilterConfigImpl(
                            fm.filterName(), ctx, params));
                    initializedFilters.add(fm.filter());
                    liveFilters.add(fm);
                } catch (jakarta.servlet.ServletException e) {
                    System.err.println("[ServletTestHarness] init failed for filter "
                            + fm.filterName() + ": " + e.getMessage());
                }
            }

            // Fin de la phase d'initialisation (Servlet 6.1 §4.4) — après cet appel,
            // les méthodes de configuration dynamique doivent throw IllegalStateException.
            ctx.markInitialized();

            var bridge = new ChappeServletBridge(new ServletDispatcher(liveServlets),
                    new FilterRegistry(liveFilters), ctx, sessions, contextPath);

            // Enregistre le context dans le registre cross-context (§4.8 / async dispatch cross-ctx).
            io.vidocq.foy.internal.container.CrossContextRegistry.register(ctx);

            int port = startServerWithRetry(bridge);
            return new ServletTestHarness(currentServer, port, contextPath,
                    initialized, initializedFilters, registry, ctx);
        }

        /** Exposes static servlet/filter registrations (web.xml / @WebServlet) to
         *  {@link VidocqServletContext} so {@code getServletRegistration(s)}
         *  returns them correctly. URL patterns are also reserved through this
         *  path to prevent dynamic {@code addMapping} from overriding them. */
        @SuppressWarnings("unchecked")
        private void materializeStaticRegistrations(VidocqServletContext ctx) {
            // Regroupe les url-patterns par servletName pour ne créer qu'une seule
            // registration par servlet statique.
            var patternsByName = new java.util.LinkedHashMap<String, java.util.List<String>>();
            for (var m : servlets) {
                patternsByName
                        .computeIfAbsent(m.servletName(), k -> new java.util.ArrayList<>())
                        .add(m.matcher().pattern());
            }
            for (var m : servlets) {
                String name = m.servletName();
                if (ctx.getServletRegistration(name) != null) continue;
                var patterns = patternsByName.get(name);
                var params = initParams.getOrDefault(m.servlet(), java.util.Map.of());
                ctx.registerStaticServlet(name,
                        (Class<? extends jakarta.servlet.Servlet>) m.servlet().getClass(),
                        patterns, params, m.asyncSupported());
            }
            for (var fm : filters) {
                String name = fm.filterName();
                if (ctx.getFilterRegistration(name) != null) continue;
                var params = initParams.getOrDefault(fm.filter(), java.util.Map.of());
                ctx.registerStaticFilter(name,
                        (Class<? extends jakarta.servlet.Filter>) fm.filter().getClass(), params);
            }
        }

        /** Transfers {@code ServletRegistration.Dynamic} / {@code FilterRegistration.Dynamic}
         *  from the context to servlet/filter lists without overriding entries
         *  already declared in web.xml (which has precedence on duplicates). */
        private void materializeDynamicRegistrations(VidocqServletContext ctx) {
            var cl = Thread.currentThread().getContextClassLoader();
            // Mapping par nom pour dédupliquer avec web.xml.
            var existingNames = new java.util.HashSet<String>();
            for (var m : servlets) existingNames.add(m.servletName());

            for (var e : ctx.dynamicServletRegistrations().entrySet()) {
                String name = e.getKey();
                if (existingNames.contains(name)) continue;
                var reg = e.getValue();
                jakarta.servlet.Servlet instance = reg.instance();
                if (instance == null) {
                    try {
                        Class<? extends jakarta.servlet.Servlet> c = reg.klass();
                        if (c == null && reg.getClassName() != null) {
                            c = (Class<? extends jakarta.servlet.Servlet>) Class.forName(reg.getClassName(), true, cl);
                        }
                        if (c == null) continue; // addJspFile sans impl réelle
                        // Isolation classloader : ignore les classes absentes du WAR.
                        if (warClassNames != null && !warClassNames.contains(c.getName())) continue;
                        instance = c.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException ex) {
                        System.err.println("[ServletTestHarness] cannot instantiate dynamic servlet "
                                + name + ": " + ex);
                        continue;
                    }
                } else if (warClassNames != null && !warClassNames.contains(instance.getClass().getName())) {
                    continue;
                }
                for (String pattern : reg.getMappings()) {
                    servlets.add(new ServletDispatcher.Mapping(
                            io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of(pattern),
                            instance, name));
                }
                // initParams portés par identité d'instance — même clé pour toutes les mappings.
                initParams.put(instance, java.util.Map.copyOf(reg.getInitParameters()));
            }

            var existingFilterNames = new java.util.HashSet<String>();
            for (var fm : filters) existingFilterNames.add(fm.filterName());
            for (var e : ctx.dynamicFilterRegistrations().entrySet()) {
                String name = e.getKey();
                if (existingFilterNames.contains(name)) continue;
                var reg = e.getValue();
                jakarta.servlet.Filter instance = reg.instance();
                if (instance == null) {
                    try {
                        Class<? extends jakarta.servlet.Filter> c = reg.klass();
                        if (c == null && reg.getClassName() != null) {
                            c = (Class<? extends jakarta.servlet.Filter>) Class.forName(reg.getClassName(), true, cl);
                        }
                        if (c == null) continue;
                        if (warClassNames != null && !warClassNames.contains(c.getName())) continue;
                        instance = c.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException ex) {
                        System.err.println("[ServletTestHarness] cannot instantiate dynamic filter "
                                + name + ": " + ex);
                        continue;
                    }
                } else if (warClassNames != null && !warClassNames.contains(instance.getClass().getName())) {
                    continue;
                }
                for (var mapping : reg.allMappings()) {
                    var dispatchers = mapping.dispatchers();
                    for (String pattern : mapping.urlPatterns()) {
                        filter(pattern, instance, name, reg.getInitParameters(), dispatchers);
                    }
                    // servlet-name mappings : résolution vers les url-patterns des servlets cibles.
                    for (String servletName : mapping.servletNames()) {
                        for (var sm : new java.util.ArrayList<>(servlets)) {
                            if (servletName.equals(sm.servletName())) {
                                filter(sm.matcher().pattern(), instance, name,
                                        reg.getInitParameters(), dispatchers);
                            }
                        }
                    }
                }
                initParams.put(instance, java.util.Map.copyOf(reg.getInitParameters()));
            }
        }

        private Server currentServer;

        private int startServerWithRetry(Handler handler) {
            RuntimeException last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                int port;
                try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                catch (Exception e) { throw new RuntimeException(e); }
                try {
                    Server server = Server.builder().host("127.0.0.1").port(port).handler(handler).build();
                    server.start();
                    currentServer = server;
                    return port;
                } catch (RuntimeException e) { last = e; }
            }
            throw last;
        }
    }
}
