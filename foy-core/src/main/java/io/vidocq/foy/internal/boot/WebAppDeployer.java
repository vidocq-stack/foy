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

import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.CrossContextRegistry;
import io.vidocq.foy.internal.container.DefaultServlet;
import io.vidocq.foy.internal.container.FilterConfigImpl;
import io.vidocq.foy.internal.container.ServletConfigImpl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterMapping;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletSecurityElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.EventListener;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deploys a {@link WebAppModel}: the Servlet 6.1 deployment lifecycle — static and dynamic
 * registrations, initializers, {@code contextInitialized}, load-on-startup ordered
 * {@code init()}, and the end of the configuration phase (§4.4).
 */
public final class WebAppDeployer {

    private static final System.Logger LOG = System.getLogger(WebAppDeployer.class.getName());

    /** A servlet instance with its mapping data, from the model or from the context API. */
    private record ServletUnit(String name, Class<? extends Servlet> type, Servlet instance, List<String> patterns,
                               Map<String, String> initParams, int loadOnStartup, boolean asyncSupported,
                               ServletSecurityElement security) {}

    /** A filter instance with its init parameters and declared async support. */
    private record FilterUnit(String name, Class<? extends Filter> type, Filter instance, Map<String, String> initParams,
                              boolean asyncSupported) {}

    private WebAppDeployer() {}

    /**
     * Deploys {@code model}. When anything escapes (a listener, an initializer, a component
     * factory), what was already set up is torn down — destroy in reverse init order,
     * {@code contextDestroyed} if {@code contextInitialized} was fired, temp dir removed —
     * before the exception propagates.
     */
    public static Deployment deploy(WebAppModel model, DeployOptions options) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(options, "options");
        ComponentFactory factory = options.componentFactory();
        VidocqServletContext ctx = newContext(model, options);
        var initializedServlets = new ArrayList<Servlet>();
        var initializedFilters = new ArrayList<Filter>();
        Path tempDir = null;
        ListenerRegistry contextInitializedListeners = null;
        SessionManager sessions = null;
        try {
            var servlets = new ArrayList<ServletUnit>();
            var filters = new ArrayList<FilterUnit>();
            instantiateStatic(model, servlets, filters);
            // Expose the servlets/filters declared in web.xml/@WebServlet through
            // ServletContext.getServletRegistrations() — visibility required by the TCK
            // (RegistrationTests.servletRegistrationsTest).
            registerStatic(ctx, model, servlets, filters);
            for (ServletDecl d : model.servlets()) {
                if (!d.enabled()) ctx.registerStaticServlet(d.name(), d.type(), d.urlPatterns(), d.initParams(),
                        d.asyncSupported());
            }
            // <context-param> init params (web.xml) — must be set before markInitialized.
            model.contextParams().forEach(ctx::setInitParameter);
            tempDir = createTempDir(ctx);
            var listeners = new ArrayList<EventListener>();
            for (var l : model.listeners()) listeners.add(l.factory().get());
            ListenerRegistry registry = new ListenerRegistry();
            registry.registerAll(listeners);
            ctx.setListenerRegistry(registry);
            runInitializers(model, options, ctx);
            contextInitializedListeners = registry;
            registry.fireContextInitialized(ctx);

            // Materialise the dynamic registrations (SCI + listener-initialized) before init().
            var dynamicMappings = new ArrayList<FilterMapping>();
            materializeDynamic(model, ctx, factory, servlets, filters, dynamicMappings);
            List<FilterMapping> filterMappings = buildFilterMappings(model, filters, dynamicMappings);

            ServletDispatcher liveServlets = initServlets(ctx, servlets, initializedServlets);
            var liveFilters = initFilters(ctx, filters, filterMappings, initializedFilters);

            // End of the initialisation phase (Servlet 6.1 §4.4) — from now on the dynamic
            // configuration methods must throw IllegalStateException.
            ctx.markInitialized();
            // The session timeout is read once the initializers and the context listeners ran:
            // ServletContext.setSessionTimeout is honoured from both (section 4.4.1).
            sessions = new SessionManager(new InMemorySessionStore(), ctx, sessionTimeoutSeconds(ctx));
            sessions.setListenerRegistry(registry);
            sessions.start();
            var bridge = new ChappeServletBridge(liveServlets,
                    new FilterRegistry(liveFilters), ctx, sessions, model.contextPath());
            // Register the context for cross-context lookups (§4.8 / cross-context async dispatch).
            CrossContextRegistry.register(ctx);
            return new Deployment(bridge, ctx, registry, initializedServlets, initializedFilters, tempDir, sessions);
        } catch (RuntimeException | Error e) {
            try {
                Deployment.undeploy(ctx, contextInitializedListeners, initializedServlets, initializedFilters,
                        tempDir, sessions);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    /**
     * The default session timeout in seconds: the context's value in minutes (descriptor, then
     * {@code setSessionTimeout} from an initializer or a listener); zero or less means that
     * sessions never time out.
     */
    static int sessionTimeoutSeconds(VidocqServletContext ctx) {
        int minutes = ctx.getSessionTimeout();
        return minutes <= 0 ? -1 : Math.multiplyExact(minutes, 60);
    }

    /** Servlet 6.1 §4.8.1: the "jakarta.servlet.context.tempdir" attribute is required. */
    private static Path createTempDir(VidocqServletContext ctx) {
        try {
            Path tmp = Files.createTempDirectory("vidocq-servlet-");
            ctx.setAttribute(ServletContext.TEMPDIR, tmp.toFile());
            return tmp;
        } catch (IOException ignored) {
            // No temp dir available: the attribute stays absent.
            return null;
        }
    }

    private static VidocqServletContext newContext(WebAppModel model, DeployOptions options) {
        VidocqServletContext ctx = new VidocqServletContext(model.contextPath());
        ctx.setErrorPages(model.errorPages());
        ctx.setLocaleEncodingMappings(model.localeEncodingMappings());
        ctx.setMimeMappings(model.mimeMappings());
        ctx.setWelcomeFiles(model.welcomeFiles());
        if (model.requestCharacterEncoding() != null) ctx.setRequestCharacterEncoding(model.requestCharacterEncoding());
        if (model.responseCharacterEncoding() != null) ctx.setResponseCharacterEncoding(model.responseCharacterEncoding());
        ctx.applyCookieConfig(model.cookieConfig());
        if (!model.trackingModes().isEmpty()) ctx.setDescriptorTrackingModes(model.trackingModes());
        ctx.setEffectiveVersion(model.effectiveMajorVersion(), model.effectiveMinorVersion());
        if (options.resourceProvider() != null) ctx.setResourceProvider(options.resourceProvider());
        String name = options.servletContextName() != null ? options.servletContextName() : model.displayName();
        if (name != null) ctx.setServletContextName(name);
        // -1 = not configured (container default, 30 minutes); zero or less otherwise = never expire.
        if (model.sessionTimeoutMinutes() != -1) ctx.setSessionTimeoutInternal(model.sessionTimeoutMinutes());
        options.reservedServletNames().forEach(ctx::reserveServletName);
        options.reservedFilterNames().forEach(ctx::reserveFilterName);
        options.reservedUrlPatterns().forEach(ctx::reserveUrlPattern);
        if (options.securityProvider() != null) ctx.setSecurityProvider(options.securityProvider());
        ctx.setComponentFactory(options.componentFactory());
        return ctx;
    }

    /**
     * One instance per declaration. Static declarations are never filtered by
     * {@link ComponentFactory#isVisible}: their classes are already resolved, and the
     * visibility restriction (TCK war isolation) applies to dynamic registrations only.
     */
    private static void instantiateStatic(WebAppModel model, List<ServletUnit> servlets, List<FilterUnit> filters) {
        for (ServletDecl d : model.servlets()) {
            // A disabled servlet is declared (registerDisabled) but never instantiated nor mapped.
            if (!d.enabled()) continue;
            servlets.add(new ServletUnit(d.name(), d.type(), d.factory().get(), d.urlPatterns(), d.initParams(),
                    d.loadOnStartup(), d.asyncSupported(), d.servletSecurity()));
        }
        for (FilterDecl d : model.filters()) {
            filters.add(new FilterUnit(d.name(), d.type(), d.factory().get(), d.initParams(), d.asyncSupported()));
        }
    }

    /**
     * Static registrations also reserve their URL patterns, so a dynamic addMapping cannot override them.
     * A filter registration exposes its model mappings ({@code getUrlPatternMappings},
     * {@code getServletNameMappings}); routing still comes from the model ({@link #buildFilterMappings}).
     */
    private static void registerStatic(VidocqServletContext ctx, WebAppModel model, List<ServletUnit> servlets,
                                       List<FilterUnit> filters) {
        for (ServletUnit s : servlets) {
            ctx.registerStaticServlet(s.name(), s.type(), s.patterns(), s.initParams(),
                    s.asyncSupported());
        }
        for (FilterUnit f : filters) {
            var registration = ctx.registerStaticFilter(f.name(), f.type(), f.initParams(), f.asyncSupported());
            for (FilterMappingDecl m : model.filterMappings()) {
                if (!m.filterName().equals(f.name())) continue;
                EnumSet<DispatcherType> types = EnumSet.copyOf(m.dispatcherTypes());
                if (m.urlPattern() != null) registration.addMappingForUrlPatterns(types, true, m.urlPattern());
                else registration.addMappingForServletNames(types, true, m.servletName());
            }
        }
    }

    /**
     * Servlet 6.1 §4.4: SCI onStartup() runs before the listeners' contextInitialized. During
     * onStartup the dynamic APIs (addListener etc.) are allowed (the context is not yet
     * "initialized" in the §4.4 sense).
     */
    private static void runInitializers(WebAppModel model, DeployOptions options, VidocqServletContext ctx) {
        for (var sci : model.initializers()) {
            try {
                sci.onStartup(options.handlesTypes().resolve(sci), ctx);
            } catch (ServletException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "SCI.onStartup failed (" + sci.getClass().getName() + ")", e);
            }
        }
    }

    /**
     * Transfers ServletRegistration.Dynamic / FilterRegistration.Dynamic from the context to
     * the servlet/filter lists, without overriding names declared statically (web.xml has
     * precedence on duplicates).
     */
    private static void materializeDynamic(WebAppModel model, VidocqServletContext ctx, ComponentFactory factory,
                                           List<ServletUnit> servlets, List<FilterUnit> filters,
                                           List<FilterMapping> dynamicMappings) {
        var staticServletNames = new HashSet<String>();
        for (ServletDecl d : model.servlets()) staticServletNames.add(d.name());
        for (var e : ctx.dynamicServletRegistrations().entrySet()) {
            String name = e.getKey();
            var reg = e.getValue();
            if (staticServletNames.contains(name)) continue;
            Servlet instance = instantiate("servlet", name, reg.instance(), reg.klass(), reg.getClassName(),
                    Servlet.class, factory);
            // An unmapped dynamic servlet is still initialised and reachable by name (section 9.1.2).
            if (instance == null) continue;
            // setServletSecurity wins; otherwise the class's @ServletSecurity applies (§13.4.1).
            ServletSecurityElement security = reg.getServletSecurity();
            if (security == null) {
                try {
                    security = factory.descriptor(instance.getClass()).servletSecurity();
                } catch (IllegalArgumentException misuse) {
                    // addServlet declares no checked exception: the misuse is reported here and
                    // only this component is left out, like an instantiation failure.
                    LOG.log(System.Logger.Level.WARNING, "cannot register dynamic servlet " + name,
                            new ServletException(misuse.getMessage(), misuse));
                    continue;
                }
            }
            // Dynamic registrations are not async unless setAsyncSupported(true) was called.
            servlets.add(new ServletUnit(name, instance.getClass(), instance, List.copyOf(reg.getMappings()),
                    Map.copyOf(reg.getInitParameters()), reg.getLoadOnStartup(), reg.isAsyncSupported(), security));
        }

        var staticFilterNames = new HashSet<String>();
        for (FilterDecl d : model.filters()) staticFilterNames.add(d.name());
        for (var e : ctx.dynamicFilterRegistrations().entrySet()) {
            String name = e.getKey();
            var reg = e.getValue();
            if (staticFilterNames.contains(name)) continue;
            Filter instance = instantiate("filter", name, reg.instance(), reg.klass(), reg.getClassName(),
                    Filter.class, factory);
            if (instance == null) continue;
            boolean async = reg.isAsyncSupported();
            for (var mapping : reg.allMappings()) {
                for (String pattern : mapping.urlPatterns()) {
                    dynamicMappings.add(filterMapping(pattern, instance, name, mapping.dispatchers(), async));
                }
                // Section 6.2.4: servlet-name mappings stay servlet-name mappings (never expanded).
                for (String servletName : mapping.servletNames()) {
                    dynamicMappings.add(FilterMapping.forServletName(servletName, instance, name,
                            dispatcherTypes(mapping.dispatchers()), async));
                }
            }
            // Like a declared filter, an unmapped dynamic filter is initialised (and destroyed) but
            // never invoked. The spec leaves this open; initialising every registered filter is
            // container practice (Tomcat filterStart, Jetty FilterHolder start).
            filters.add(new FilterUnit(name, instance.getClass(), instance, Map.copyOf(reg.getInitParameters()),
                    async));
        }
    }

    /** Returns the registered instance, or a new one; {@code null} when the component must be skipped. */
    @SuppressWarnings("unchecked")
    private static <T> T instantiate(String kind, String name, T instance, Class<? extends T> klass,
                                     String className, Class<T> base, ComponentFactory factory) {
        if (instance != null) return factory.isVisible(instance.getClass()) ? instance : null;
        try {
            Class<? extends T> c = klass;
            if (c == null && className != null) {
                Class<?> loaded = factory.load(className);
                if (!base.isAssignableFrom(loaded)) {
                    LOG.log(System.Logger.Level.WARNING, "dynamic " + kind + " '" + name + "' skipped: class "
                            + className + " is not a " + base.getName());
                    return null;
                }
                c = loaded.asSubclass(base);
            }
            if (c == null) return null; // addJspFile without a real implementation
            // Class loader isolation: ignore classes absent from the WAR.
            if (!factory.isVisible(c)) return null;
            return factory.newInstance(c);
        } catch (ClassNotFoundException | ServletException | RuntimeException ex) {
            // RuntimeException: a factory that does not wrap what the constructor throws.
            LOG.log(System.Logger.Level.WARNING, "cannot instantiate dynamic " + kind + " " + name, ex);
            return null;
        }
    }

    /** Model mappings in declaration order, then the dynamic ones. */
    private static List<FilterMapping> buildFilterMappings(WebAppModel model,
                                                           List<FilterUnit> filters,
                                                           List<FilterMapping> dynamicMappings) {
        var result = new ArrayList<FilterMapping>();
        for (FilterMappingDecl m : model.filterMappings()) {
            FilterUnit f = filters.stream().filter(u -> u.name().equals(m.filterName())).findFirst().orElseThrow();
            result.add(m.urlPattern() != null
                    ? filterMapping(m.urlPattern(), f.instance(), f.name(), m.dispatcherTypes(), f.asyncSupported())
                    : FilterMapping.forServletName(m.servletName(), f.instance(), f.name(),
                            dispatcherTypes(m.dispatcherTypes()), f.asyncSupported()));
        }
        result.addAll(dynamicMappings);
        return result;
    }

    private static Set<DispatcherType> dispatcherTypes(Set<DispatcherType> types) {
        return types == null ? Set.of(DispatcherType.REQUEST) : types;
    }

    private static FilterMapping filterMapping(String pattern, Filter filter, String name, Set<DispatcherType> types,
                                               boolean asyncSupported) {
        return new FilterMapping(UrlPatternMatcher.of(pattern), filter, name, dispatcherTypes(types), asyncSupported);
    }

    /**
     * Servlet 6.1 §2.3: init() before the first request — load-on-startup servlets first,
     * ascending (stable on declaration order), then the others in declaration order (eager
     * init of the rest is allowed by §2.3.1). Returns the dispatcher: the URL mappings in declaration
     * order and every servlet by name, mapped or not (section 9.1.2 named dispatch).
     */
    private static ServletDispatcher initServlets(VidocqServletContext ctx, List<ServletUnit> servlets,
                                                                List<Servlet> initialized) {
        var order = new ArrayList<ServletUnit>();
        servlets.stream().filter(s -> s.loadOnStartup() >= 0)
                .sorted(Comparator.comparingInt(ServletUnit::loadOnStartup)).forEach(order::add);
        servlets.stream().filter(s -> s.loadOnStartup() < 0).forEach(order::add);

        var failures = new IdentityHashMap<Servlet, Servlet>();
        // One instance registered under several names is initialised (and destroyed) once.
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<Servlet, Boolean>());
        for (ServletUnit s : order) {
            if (!seen.add(s.instance())) continue;
            try {
                s.instance().init(new ServletConfigImpl(s.name(), ctx, s.initParams()));
                initialized.add(s.instance());
            } catch (ServletException | RuntimeException e) {
                // Servlet 6.1 §2.3.3: a servlet whose init() failed must answer 500 (or 503)
                // to every later request, not 404 — a stub stands in for it. A runtime
                // exception is an init failure too: one bad servlet must not kill the app.
                LOG.log(System.Logger.Level.WARNING, "init failed for servlet " + s.name(), e);
                failures.put(s.instance(), new InitFailureServlet(
                        e instanceof ServletException se ? se : new ServletException(e)));
            }
        }
        var live = new ArrayList<ServletDispatcher.Mapping>();
        var named = new ArrayList<ServletDispatcher.NamedServlet>();
        for (ServletUnit s : servlets) {
            Servlet stub = failures.get(s.instance());
            named.add(stub != null ? new ServletDispatcher.NamedServlet(s.name(), stub, true)
                    : new ServletDispatcher.NamedServlet(s.name(), s.instance(), s.asyncSupported()));
            for (String p : s.patterns()) {
                live.add(stub != null
                        ? new ServletDispatcher.Mapping(UrlPatternMatcher.of(p), stub, s.name())
                        : new ServletDispatcher.Mapping(UrlPatternMatcher.of(p), s.instance(), s.name(),
                                s.asyncSupported(), s.security()));
            }
        }
        appendContainerDefault(ctx, live);
        return new ServletDispatcher(live, named);
    }

    /**
     * Servlet 6.1 section 12.2: when no application servlet maps {@code /}, the container default
     * servlet serves the static resources under that mapping. It is not an application servlet:
     * absent from {@code getServletRegistrations} and from the deployment's initialised servlets
     * (it holds no resource, so it needs no {@code destroy}).
     */
    private static void appendContainerDefault(VidocqServletContext ctx, List<ServletDispatcher.Mapping> live) {
        if (live.stream().anyMatch(m -> m.matcher().kind() == UrlPatternMatcher.Kind.DEFAULT)) return;
        var servlet = new DefaultServlet();
        try {
            servlet.init(new ServletConfigImpl(DefaultServlet.NAME, ctx, Map.of()));
        } catch (ServletException e) {
            throw new IllegalStateException("container default servlet failed to initialise", e);
        }
        live.add(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/"), servlet, DefaultServlet.NAME, true));
    }

    /** init() filters in declaration order; a failing filter is logged and left out of the chain. */
    private static List<FilterMapping> initFilters(VidocqServletContext ctx, List<FilterUnit> filters,
                                                   List<FilterMapping> mappings, List<Filter> initialized) {
        var failed = new IdentityHashMap<Filter, Boolean>();
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<Filter, Boolean>());
        for (FilterUnit f : filters) {
            if (!seen.add(f.instance())) continue;
            try {
                f.instance().init(new FilterConfigImpl(f.name(), ctx, f.initParams()));
                initialized.add(f.instance());
            } catch (ServletException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "init failed for filter " + f.name(), e);
                failed.put(f.instance(), Boolean.TRUE);
            }
        }
        return mappings.stream().filter(m -> !failed.containsKey(m.filter())).toList();
    }
}
