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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.WebAppModel.*;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class WebAppDeployerEndToEndTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private Server server;
    private int port;
    private Deployment deployment;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
        EVENTS.clear();
    }

    private void deploy(WebAppModel model) {
        deploy(model, DeployOptions.defaults(getClass().getClassLoader()));
    }

    private void deploy(WebAppModel model, DeployOptions options) {
        deployment = WebAppDeployer.deploy(model, options);
        var r = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public static class Recording extends HttpServlet {
        final String id;
        public Recording() { this("default"); }
        Recording(String id) { this.id = id; }
        @Override public void init() { EVENTS.add("init:" + id); }
        @Override public void destroy() { EVENTS.add("destroy:" + id); }
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write(id + ":" + getServletConfig().getInitParameter("k"));
        }
    }

    static ServletDecl decl(String id, int los, String... patterns) {
        return new ServletDecl(id, Recording.class, () -> new Recording(id), List.of(patterns),
                Map.of("k", "v-" + id), los, true);
    }

    @Test
    void oneInstancePerDeclaration() throws Exception {
        deploy(WebAppModel.builder("/").servlet(decl("a", Integer.MIN_VALUE, "/x", "/y")).build());
        assertEquals("a:v-a", get("/x").body());
        assertEquals("a:v-a", get("/y").body());
        assertEquals(List.of("init:a"), EVENTS);
    }

    @Test
    void loadOnStartupOrdersInitThenDeclarationOrder() {
        deploy(WebAppModel.builder("/")
                .servlet(decl("lazy", Integer.MIN_VALUE, "/l"))
                .servlet(decl("two", 2, "/2"))
                .servlet(decl("zero", 0, "/0"))
                .servlet(decl("alsoTwo", 2, "/22"))
                .build());
        assertEquals(List.of("init:zero", "init:two", "init:alsoTwo", "init:lazy"), EVENTS);
    }

    public static class FailingInit extends HttpServlet {
        final ServletException failure;
        FailingInit(ServletException failure) { this.failure = failure; }
        @Override public void init() throws ServletException { throw failure; }
    }

    static ServletDecl failing(String name, String pattern, ServletException e) {
        return new ServletDecl(name, FailingInit.class, () -> new FailingInit(e), List.of(pattern),
                Map.of(), Integer.MIN_VALUE, true);
    }

    @Test
    void initFailuresAnswer500Or404Or503() throws Exception {
        deploy(WebAppModel.builder("/")
                .servlet(failing("plain", "/plain", new ServletException("x")))
                .servlet(failing("perm", "/perm", new UnavailableException("gone")))
                .servlet(failing("temp", "/temp", new UnavailableException("later", 30)))
                .build());
        assertEquals(500, get("/plain").statusCode());
        assertEquals(404, get("/perm").statusCode());
        assertEquals(503, get("/temp").statusCode());
    }

    @Test
    void sciDynamicServletIsServedAndListenerSeesContextInitialized() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> {
            EVENTS.add("sci:" + classes);
            ctx.addServlet("dyn", new Recording("dyn")).addMapping("/dyn");
            ctx.addListener(new ServletContextListener() {
                @Override public void contextInitialized(ServletContextEvent e) { EVENTS.add("ctxInit"); }
                @Override public void contextDestroyed(ServletContextEvent e) { EVENTS.add("ctxDestroyed"); }
            });
        };
        deploy(WebAppModel.builder("/").initializer(sci).build());
        assertEquals("dyn:null", get("/dyn").body());
        assertEquals(List.of("sci:null", "ctxInit", "init:dyn"), EVENTS);
    }

    @Test
    void addServletAfterDeployThrows() {
        deploy(WebAppModel.builder("/").build());
        assertThrows(IllegalStateException.class,
                () -> deployment.servletContext().addServlet("late", new Recording("late")));
    }

    public static class ThrowingDestroy extends Recording {
        public ThrowingDestroy() { super("throwing"); }
        @Override public void destroy() { EVENTS.add("destroy:throwing"); throw new IllegalStateException(); }
    }

    @Test
    void closeDestroysInReverseOrder() {
        ServletContainerInitializer sci = (c, ctx) -> ctx.addListener(new ServletContextListener() {
            @Override public void contextDestroyed(ServletContextEvent e) { EVENTS.add("ctxDestroyed"); }
        });
        deploy(WebAppModel.builder("/")
                .servlet(decl("first", 1, "/1"))
                .servlet(new ServletDecl("throwing", ThrowingDestroy.class, ThrowingDestroy::new,
                        List.of("/t"), Map.of(), 2, true))
                .initializer(sci)
                .build());
        EVENTS.clear();
        deployment.close();
        deployment.close();
        assertEquals(List.of("destroy:throwing", "destroy:first", "ctxDestroyed"), EVENTS);
    }

    @Test
    void visibilityRestrictsDynamicRegistrationsOnly() throws Exception {
        ServletContainerInitializer sci = (c, ctx) -> ctx.addServlet("byClass", Recording.class).addMapping("/dyn");
        var cl = getClass().getClassLoader();
        deploy(WebAppModel.builder("/").servlet(decl("static", Integer.MIN_VALUE, "/s")).initializer(sci).build(),
                DeployOptions.defaults(cl).withComponentFactory(ComponentFactory.reflective(cl, Set.of())));
        assertEquals("static:v-static", get("/s").body());
        assertEquals(404, get("/dyn").statusCode());
    }

    @Test
    void tempDirAttributeIsSet() {
        deploy(WebAppModel.builder("/").build());
        assertInstanceOf(java.io.File.class,
                deployment.servletContext().getAttribute(ServletContext.TEMPDIR));
    }

    public static class SessionProbe extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write(String.valueOf(q.getSession(true).getMaxInactiveInterval()));
        }
    }

    static ServletDecl sessionProbe() {
        return new ServletDecl("probe", SessionProbe.class, SessionProbe::new, List.of("/session"),
                Map.of(), Integer.MIN_VALUE, true);
    }

    @Test
    void configuredSessionTimeoutReachesCreatedSessions() throws Exception {
        deploy(WebAppModel.builder("/").servlet(sessionProbe()).sessionTimeoutMinutes(5).build());
        assertEquals("300", get("/session").body());
    }

    @Test
    void sessionTimeoutDefaultsTo1800Seconds() throws Exception {
        deploy(WebAppModel.builder("/").servlet(sessionProbe()).build());
        assertEquals("1800", get("/session").body());
    }

    public static class RuntimeFailingInit extends HttpServlet {
        @Override public void init() { throw new IllegalStateException("boom"); }
    }

    public static class RuntimeFailingFilter implements Filter {
        @Override public void init(FilterConfig c) { throw new IllegalStateException("boom"); }
        @Override public void doFilter(ServletRequest q, ServletResponse r, FilterChain chain) throws IOException {
            r.getWriter().write("filtered");
        }
    }

    @Test
    void runtimeExceptionFromInitIsAnInitFailure() throws Exception {
        deploy(WebAppModel.builder("/")
                .servlet(new ServletDecl("bad", RuntimeFailingInit.class, RuntimeFailingInit::new,
                        List.of("/bad"), Map.of(), 1, true))
                .servlet(decl("good", 2, "/good"))
                .filter(new FilterDecl("badFilter", RuntimeFailingFilter.class, RuntimeFailingFilter::new, Map.of(), true))
                .filterMapping(new FilterMappingDecl("badFilter", "/good", null, Set.of(DispatcherType.REQUEST)))
                .build());
        assertEquals(500, get("/bad").statusCode());
        var good = get("/good");
        assertEquals(200, good.statusCode());
        assertEquals("good:v-good", good.body());
        assertEquals(List.of("good"), deployment.initializedServlets().stream()
                .map(s -> ((Recording) s).id).toList());
        assertTrue(deployment.initializedFilters().isEmpty());
    }

    static ServletContextListener recordingListener(String id) {
        return new ServletContextListener() {
            @Override public void contextInitialized(ServletContextEvent e) { EVENTS.add("ctxInit:" + id); }
            @Override public void contextDestroyed(ServletContextEvent e) { EVENTS.add("ctxDestroyed:" + id); }
        };
    }

    @Test
    void failingContextInitializedCleansUpBeforePropagating() {
        var tempDir = new java.util.concurrent.atomic.AtomicReference<java.io.File>();
        ServletContainerInitializer sci = (c, ctx) -> {
            tempDir.set((java.io.File) ctx.getAttribute(ServletContext.TEMPDIR));
            ctx.addListener(recordingListener("first"));
            ctx.addListener(new ServletContextListener() {
                @Override public void contextInitialized(ServletContextEvent e) {
                    throw new IllegalStateException("listener boom");
                }
            });
        };
        var model = WebAppModel.builder("/failing-listener")
                .servlet(decl("s", 1, "/s")).initializer(sci).build();
        var ex = assertThrows(IllegalStateException.class,
                () -> WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader())));
        assertEquals("listener boom", ex.getMessage());
        // contextDestroyed fired because contextInitialized was; no servlet was ever initialised.
        assertEquals(List.of("ctxInit:first", "ctxDestroyed:first"), EVENTS);
        assertNull(io.vidocq.foy.internal.container.CrossContextRegistry.lookup("/failing-listener"));
        assertNotNull(tempDir.get());
        assertFalse(tempDir.get().exists(), "temp dir must be removed");
    }

    @Test
    void failingComponentSupplierLeaksNothing() throws Exception {
        var before = vidocqTempDirs();
        var model = WebAppModel.builder("/failing-supplier")
                .servlet(decl("ok", 1, "/ok"))
                .listener(new ListenerDecl(ServletContextListener.class, () -> {
                    throw new IllegalStateException("cannot instantiate");
                }))
                .build();
        assertThrows(IllegalStateException.class,
                () -> WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader())));
        assertEquals(List.of(), EVENTS, "nothing initialised, nothing destroyed");
        assertNull(io.vidocq.foy.internal.container.CrossContextRegistry.lookup("/failing-supplier"));
        assertEquals(before, vidocqTempDirs(), "no temp dir leaked");
    }

    private static Set<java.nio.file.Path> vidocqTempDirs() throws IOException {
        var tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        try (var s = java.nio.file.Files.list(tmp)) {
            return new HashSet<>(s.filter(p -> p.getFileName().toString().startsWith("vidocq-servlet-")).toList());
        }
    }

    @Test
    void closeRemovesTempDir() throws Exception {
        deploy(WebAppModel.builder("/").build());
        var dir = (java.io.File) deployment.servletContext().getAttribute(ServletContext.TEMPDIR);
        var sub = java.nio.file.Files.createDirectories(dir.toPath().resolve("sub"));
        java.nio.file.Files.writeString(sub.resolve("file.txt"), "x");
        deployment.close();
        assertFalse(dir.exists(), "temp dir must be removed on close");
    }

    public static class CountingListener implements ServletRequestListener {}

    /** Delegates to the registry-backed factory and records every class it instantiates. */
    static final class RecordingFactory implements ComponentFactory {
        final ComponentFactory delegate = io.vidocq.foy.internal.gen.RegistryComponentFactory.forClassLoader(
                WebAppDeployerEndToEndTest.class.getClassLoader());
        final List<Class<?>> created = new CopyOnWriteArrayList<>();
        @Override public Class<?> load(String className) throws ClassNotFoundException, ServletException {
            return delegate.load(className);
        }
        @Override public <T> T newInstance(Class<T> type) throws ServletException {
            created.add(type);
            return delegate.newInstance(type);
        }
    }

    private static DeployOptions options(ComponentFactory factory) {
        return new DeployOptions(null, null, null, Set.of(), Set.of(), Set.of(), factory, HandlesTypesResolver.NONE);
    }

    @Test
    void contextCreateAndAddListenerGoThroughTheDeploymentFactory() {
        var factory = new RecordingFactory();
        ServletContainerInitializer sci = (classes, ctx) -> {
            ctx.addServlet("dyn", ctx.createServlet(Recording.class)).addMapping("/dyn");
            ctx.addListener(CountingListener.class);
            ctx.addListener(CountingListener.class.getName());
        };
        deploy(WebAppModel.builder("/").initializer(sci).build(), options(factory));
        assertEquals(List.of(Recording.class, CountingListener.class, CountingListener.class), factory.created);
    }

    @Test
    void dynamicSetServletSecurityIsEnforced() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> ctx.addServlet("sec", Recording.class)
                .setServletSecurity(new ServletSecurityElement(
                        new HttpConstraintElement(jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic.DENY)));
        ServletContainerInitializer mapping = (classes, ctx) ->
                ctx.getServletRegistration("sec").addMapping("/sec");
        deploy(WebAppModel.builder("/").initializer(sci).initializer(mapping).build(),
                options(new RecordingFactory()));
        assertEquals(403, get("/sec").statusCode());
    }

    @Test
    void declaredServletSecurityIsEnforced() throws Exception {
        var deny = new ServletSecurityElement(
                new HttpConstraintElement(jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic.DENY));
        deploy(WebAppModel.builder("/")
                .servlet(new ServletDecl("d", Recording.class, () -> new Recording("d"), List.of("/denied"),
                        Map.of(), Integer.MIN_VALUE, true, deny))
                .servlet(decl("open", Integer.MIN_VALUE, "/open"))
                .build());
        assertEquals(403, get("/denied").statusCode());
        assertEquals(200, get("/open").statusCode());
    }

    /** Reports isAsyncSupported() and whether startAsync() is refused. */
    public static class AsyncProbe extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            String start;
            try {
                q.startAsync().complete();
                start = "started";
            } catch (IllegalStateException e) {
                start = "ISE";
            }
            r.getWriter().write(q.isAsyncSupported() + ":" + start);
        }
    }

    public static class PassThrough implements Filter {
        @Override public void doFilter(ServletRequest q, ServletResponse r, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(q, r);
        }
    }

    @Test
    void dynamicServletAsyncSupportedIsHonoured() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> {
            var off = ctx.addServlet("off", new AsyncProbe());
            off.setAsyncSupported(false);
            off.addMapping("/off");
            var on = ctx.addServlet("on", new AsyncProbe());
            on.setAsyncSupported(true);
            on.addMapping("/on");
            ctx.addServlet("default", new AsyncProbe()).addMapping("/default");
        };
        deploy(WebAppModel.builder("/").initializer(sci).build());
        assertEquals("false:ISE", get("/off").body());
        assertEquals("true:started", get("/on").body());
        assertEquals("false:ISE", get("/default").body(), "a dynamic registration is not async by default");
    }

    @Test
    void aNonAsyncFilterMakesTheChainNonAsync() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> {
            for (String p : List.of("/dyn-plain", "/dyn-async")) {
                var s = ctx.addServlet(p, new AsyncProbe());
                s.setAsyncSupported(true);
                s.addMapping(p);
            }
            ctx.addFilter("dynPlain", new PassThrough())
                    .addMappingForUrlPatterns(null, false, "/dyn-plain");
            var asyncFilter = ctx.addFilter("dynAsync", new PassThrough());
            asyncFilter.setAsyncSupported(true);
            asyncFilter.addMappingForUrlPatterns(null, false, "/dyn-async");
        };
        deploy(WebAppModel.builder("/")
                .servlet(new ServletDecl("sPlain", AsyncProbe.class, AsyncProbe::new, List.of("/static-plain"),
                        Map.of(), Integer.MIN_VALUE, true))
                .servlet(new ServletDecl("sAsync", AsyncProbe.class, AsyncProbe::new, List.of("/static-async"),
                        Map.of(), Integer.MIN_VALUE, true))
                .filter(new FilterDecl("plain", PassThrough.class, PassThrough::new, Map.of(), false))
                .filter(new FilterDecl("async", PassThrough.class, PassThrough::new, Map.of(), true))
                .filterMapping(new FilterMappingDecl("plain", "/static-plain", null, Set.of(DispatcherType.REQUEST)))
                .filterMapping(new FilterMappingDecl("async", "/static-async", null, Set.of(DispatcherType.REQUEST)))
                .initializer(sci)
                .build());
        assertEquals("false:ISE", get("/static-plain").body(), "§2.3.3.3: every filter must support async");
        assertEquals("true:started", get("/static-async").body());
        assertEquals("false:ISE", get("/dyn-plain").body());
        assertEquals("true:started", get("/dyn-async").body());
    }

    public static class CountingServlet extends HttpServlet {
        final java.util.concurrent.atomic.AtomicInteger inits = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger destroys = new java.util.concurrent.atomic.AtomicInteger();
        @Override public void init() { inits.incrementAndGet(); }
        @Override public void destroy() { destroys.incrementAndGet(); }
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("counted");
        }
    }

    @Test
    void oneInstanceUnderTwoNamesIsInitialisedAndDestroyedOnce() throws Exception {
        var shared = new CountingServlet();
        ServletContainerInitializer sci = (classes, ctx) -> {
            ctx.addServlet("a", shared).addMapping("/a");
            ctx.addServlet("b", shared).addMapping("/b");
        };
        deploy(WebAppModel.builder("/").initializer(sci).build());
        assertEquals("counted", get("/a").body());
        assertEquals("counted", get("/b").body());
        assertEquals(1, shared.inits.get());
        deployment.close();
        assertEquals(1, shared.destroys.get());
    }

    /** Spec-forbidden: 'value' and 'urlPatterns' together. */
    @WebServlet(value = "/x", urlPatterns = "/y")
    public static class BothPatterns extends HttpServlet {}

    /** Spec-forbidden: {@code @WebServlet} on a class that is not a servlet. */
    @WebServlet("/l")
    public static class ServletAnnotatedListener implements ServletContextListener {}

    @Test
    void dynamicAnnotationMisuseFollowsTheServletContextContracts() throws Exception {
        var createServlet = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var addListener = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        ServletContainerInitializer sci = (classes, ctx) -> {
            try {
                ctx.createServlet(BothPatterns.class);
            } catch (Throwable t) {
                createServlet.set(t);
            }
            try {
                ctx.addListener(ServletAnnotatedListener.class);
            } catch (Throwable t) {
                addListener.set(t);
            }
            // addServlet declares no ServletException: the misuse is reported when the
            // registration is materialised, and only this component is skipped.
            ctx.addServlet("byInstance", new BothPatterns()).addMapping("/by-instance");
            ctx.addServlet("byClass", BothPatterns.class).addMapping("/by-class");
            ctx.addServlet("ok", new Recording("ok")).addMapping("/ok");
        };
        try (var log = io.vidocq.foy.internal.LogCapture.of(WebAppDeployer.class.getName())) {
            deploy(WebAppModel.builder("/").initializer(sci).build());
            assertEquals(2, log.warnings().size(), log.warnings().toString());
        }
        assertInstanceOf(ServletException.class, createServlet.get(), "createServlet declares ServletException");
        assertInstanceOf(IllegalArgumentException.class, addListener.get(), "addListener declares no checked exception");
        assertInstanceOf(ServletException.class, addListener.get().getCause());
        assertEquals(404, get("/by-instance").statusCode());
        assertEquals(404, get("/by-class").statusCode());
        assertEquals("ok:null", get("/ok").body());
    }
}
