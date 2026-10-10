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
package io.vidocq.foy.cdi.vauban;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.foy.internal.cdi.FoySessionContext;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foy's session context on Vauban (foy#21): {@link FoyWebExtension} registers it in
 * {@code @Discovery}; Vauban installs it when it runs that discovery at boot, which these tests
 * ensure by compiling the application without annotation processing.
 */
@DisplayName("FoySessionContext on Vauban: @SessionScoped beans per HTTP session")
class FoySessionScopeVaubanTest {

    @TempDir
    Path tempDir;

    /*
     * No request-scoped probe in Cart's @PreDestroy here: Vauban provides no
     * RequestContextController, so no request context is active while session beans are
     * destroyed. That probe runs on Weld only (foy-it-weld).
     */
    private static final List<String> CLASSES = List.of("vsess.app.Cart", "vsess.app.Events",
            "vsess.app.SessionServlet", "vsess.app.SessionFilter", "vsess.app.SessionRequestListener");

    private static final String CART = """
            package vsess.app;

            import jakarta.annotation.PreDestroy;
            import jakarta.enterprise.context.SessionScoped;
            import jakarta.inject.Inject;

            @SessionScoped
            public class Cart implements java.io.Serializable {
                private final String id = java.util.UUID.randomUUID().toString();
                @Inject
                Events events;

                public String id() {
                    return id;
                }

                @PreDestroy
                public void destroyed() {
                    events.record("bean " + id);
                }
            }
            """;

    private static final String EVENTS = """
            package vsess.app;

            import jakarta.enterprise.context.BeforeDestroyed;
            import jakarta.enterprise.context.Destroyed;
            import jakarta.enterprise.context.Initialized;
            import jakarta.enterprise.context.SessionScoped;
            import jakarta.enterprise.event.Observes;
            import jakarta.servlet.http.HttpSession;

            @jakarta.enterprise.context.ApplicationScoped
            public class Events {
                private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

                public void record(String line) {
                    lines.add(line);
                }

                public String dump() {
                    return String.join("\\n", lines);
                }

                public void initialized(@Observes @Initialized(SessionScoped.class) HttpSession s) {
                    lines.add("initialized " + s.getId());
                }

                public void beforeDestroyed(@Observes @BeforeDestroyed(SessionScoped.class) HttpSession s) {
                    lines.add("beforeDestroyed " + s.getId());
                }

                public void destroyed(@Observes @Destroyed(SessionScoped.class) HttpSession s) {
                    lines.add("destroyed " + s.getId());
                }
            }
            """;

    private static final String SERVLET = """
            package vsess.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;

            @jakarta.enterprise.context.Dependent
            @jakarta.servlet.annotation.WebServlet("/session/*")
            public class SessionServlet extends jakarta.servlet.http.HttpServlet {
                @Inject
                Cart cart;
                @Inject
                Events events;

                @Override
                protected void doGet(HttpServletRequest q, HttpServletResponse r) throws java.io.IOException {
                    String op = q.getPathInfo() == null ? "" : q.getPathInfo().substring(1);
                    var out = r.getWriter();
                    switch (op) {
                        case "id" -> out.write(cart.id() + "|" + q.getSession(false).getId());
                        case "invalidate" -> {
                            String id = cart.id();
                            q.getSession(false).invalidate();
                            out.write(id + "|" + cart.id());
                        }
                        case "expire" -> {
                            String id = cart.id();
                            q.getSession(false).setMaxInactiveInterval(1);
                            out.write(id + "|" + q.getSession(false).getId());
                        }
                        case "same" -> out.write(q.getAttribute("listener") + "|" + q.getAttribute("filter") + "|" + cart.id());
                        case "probe" -> {
                            try {
                                out.write("active " + cart.id());
                            } catch (RuntimeException e) {
                                out.write(e.getClass().getName());
                            }
                        }
                        case "events" -> out.write(events.dump());
                        default -> r.sendError(404);
                    }
                }
            }
            """;

    private static final String FILTER = """
            package vsess.app;

            @jakarta.enterprise.context.Dependent
            @jakarta.servlet.annotation.WebFilter("/session/same")
            public class SessionFilter extends jakarta.servlet.http.HttpFilter {
                @jakarta.inject.Inject
                Cart cart;

                @Override
                protected void doFilter(jakarta.servlet.http.HttpServletRequest q, jakarta.servlet.http.HttpServletResponse r,
                        jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    q.setAttribute("filter", cart.id());
                    chain.doFilter(q, r);
                }
            }
            """;

    private static final String LISTENER = """
            package vsess.app;

            @jakarta.enterprise.context.Dependent
            @jakarta.servlet.annotation.WebListener
            public class SessionRequestListener implements jakarta.servlet.ServletRequestListener {
                @jakarta.inject.Inject
                Cart cart;

                @Override
                public void requestInitialized(jakarta.servlet.ServletRequestEvent event) {
                    if (event.getServletRequest() instanceof jakarta.servlet.http.HttpServletRequest q
                            && q.getRequestURI().endsWith("/session/same")) {
                        q.setAttribute("listener", cart.id());
                    }
                }
            }
            """;

    // ---- the extension itself ----

    @Test
    @DisplayName("@Discovery registers FoySessionContext through the addContext overload without the boolean")
    void discoveryRegistersTheContextWithoutTheBooleanOverload() {
        var calls = new ArrayList<List<Object>>();
        var meta = (MetaAnnotations) Proxy.newProxyInstance(MetaAnnotations.class.getClassLoader(),
                new Class<?>[] {MetaAnnotations.class}, (p, m, a) -> {
                    if (m.getName().equals("addContext")) {
                        calls.add(List.of(a));
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        new FoyWebExtension().sessionContext(meta);
        assertEquals(List.of(List.<Object>of(SessionScoped.class, FoySessionContext.class)), calls);
    }

    // ---- end to end ----

    @Test
    @DisplayName("two clients get two instances, each stable across its requests")
    void twoClientsTwoInstances() throws Exception {
        try (var app = start(true)) {
            var alice = cookieClient();
            var bob = cookieClient();
            String alice1 = app.body(alice, "/session/id").split("\\|")[0];
            String alice2 = app.body(alice, "/session/id").split("\\|")[0];
            String bob1 = app.body(bob, "/session/id").split("\\|")[0];
            assertEquals(alice1, alice2);
            assertNotEquals(alice1, bob1);
        }
    }

    @Test
    @DisplayName("invalidate keeps the instance until the end of the request, then a new one comes")
    void invalidateGivesANewInstance() throws Exception {
        try (var app = start(true)) {
            var client = cookieClient();
            String before = app.body(client, "/session/id").split("\\|")[0];
            String[] invalidated = app.body(client, "/session/invalidate").split("\\|");
            assertEquals(before, invalidated[0]);
            assertEquals(before, invalidated[1]);
            assertNotEquals(before, app.body(client, "/session/id").split("\\|")[0]);
            app.awaitEvent("bean " + before);
        }
    }

    @Test
    @DisplayName("@Initialized, @BeforeDestroyed and @Destroyed(SessionScoped.class) fire with the HttpSession")
    void sessionEventsFire() throws Exception {
        try (var app = start(true)) {
            var client = cookieClient();
            String sessionId = app.body(client, "/session/id").split("\\|")[1];
            app.awaitEvent("initialized " + sessionId);
            app.body(client, "/session/invalidate");
            app.awaitEvent("destroyed " + sessionId);
            assertTrue(app.events().contains("beforeDestroyed " + sessionId));
        }
    }

    @Test
    @DisplayName("an expired session's beans are destroyed by the reaper, without another request")
    void expiryDestroysTheBeans() throws Exception {
        try (var app = start(true)) {
            app.mounted().deployment().sessionManager().restartReaper(Duration.ofMillis(100));
            String[] expiring = app.body(cookieClient(), "/session/expire").split("\\|");
            app.awaitEvent("destroyed " + expiring[1]);
            assertTrue(app.events().contains("bean " + expiring[0]));
        }
    }

    @Test
    @DisplayName("a request listener, a filter and the servlet share the instance of a request")
    void listenerFilterAndServletShareTheInstance() throws Exception {
        try (var app = start(true)) {
            String[] ids = app.body(cookieClient(), "/session/same").split("\\|");
            assertEquals(ids[2], ids[0]);
            assertEquals(ids[2], ids[1]);
        }
    }

    @Test
    @DisplayName("without FoyWebExtension, a session bean still fails with ContextNotActiveException, and nothing else breaks")
    void withoutTheContextASessionBeanFailsCleanly() throws Exception {
        try (var app = start(false)) {
            assertEquals("jakarta.enterprise.context.ContextNotActiveException",
                    app.body(cookieClient(), "/session/probe"));
        }
    }

    // ---- harness ----

    private record App(URLClassLoader loader, VaubanContainer container, Server server,
                       FoyChappeBoot.Mounted mounted, String base) implements AutoCloseable {

        String body(HttpClient client, String path) throws Exception {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            return response.body();
        }

        List<String> events() throws Exception {
            return body(HttpClient.newHttpClient(), "/session/events").lines().toList();
        }

        void awaitEvent(String line) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!events().contains(line)) {
                if (System.nanoTime() - deadline > 0) throw new AssertionError("timed out waiting for '" + line + "': " + events());
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
        }

        @Override
        public void close() throws Exception {
            server.stop();
            mounted.close();   // before the container: the live sessions' beans are destroyed now
            container.close();
            loader.close();
        }
    }

    private App start(boolean withFoyExtension) throws Exception {
        URLClassLoader loader = compile(CART, EVENTS, SERVLET, FILTER, LISTENER);
        VaubanContainer container = withTccl(loader, () -> {
            var builder = VaubanContainer.builder().classLoader(loader);
            if (withFoyExtension) builder.addBeanClass(FoyWebExtension.class);
            for (String name : CLASSES) builder.addBeanClass(loader.loadClass(name));
            return builder.build();
        });
        var mounted = withTccl(loader, () -> FoyChappeBoot.builder()
                .beanManager(container.getBeanManager()).classLoader(loader).contextPath("/").build().orElseThrow());
        int port;
        try (var s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
        return new App(loader, container, server, mounted, "http://127.0.0.1:" + port);
    }

    private static HttpClient cookieClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private static <T> T withTccl(ClassLoader loader, Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Compiles without annotation processing: no vauban-bce-processed marker, so Vauban runs the BCEs at boot. */
    private URLClassLoader compile(String... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var root = Files.createTempDirectory(tempDir, "app");
        var classes = Files.createDirectories(root.resolve("classes"));
        var files = new ArrayList<File>();
        for (var source : sources) {
            var pkg = match(source, "package\\s+([\\w.]+)\\s*;");
            var name = match(source, "public\\s+(?:class|interface|enum|record)\\s+(\\w+)");
            var file = root.resolve("src").resolve(pkg.replace('.', '/')).resolve(name + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            files.add(file.toFile());
        }
        try (var fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, testPath());
            boolean ok = compiler.getTask(null, fm, diagnostics, List.of("--release", "25", "-proc:none"), null,
                    fm.getJavaFileObjectsFromFiles(files)).call();
            assertTrue(ok, () -> diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, FoySessionScopeVaubanTest.class.getClassLoader());
    }

    private static String match(String source, String regex) {
        var m = Pattern.compile(regex).matcher(source);
        if (!m.find()) throw new IllegalArgumentException("no match for " + regex);
        return m.group(1);
    }

    /** This test run's class path and module path, which hold the Jakarta APIs the application compiles against. */
    private static List<File> testPath() {
        Set<File> path = new LinkedHashSet<>();
        for (var property : List.of("jdk.module.path", "java.class.path")) {
            for (var e : System.getProperty(property, "").split(File.pathSeparator)) {
                if (!e.isBlank()) path.add(new File(e));
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
            if ("file".equals(uri.getScheme())) path.add(new File(uri));
        }));
        return List.copyOf(path);
    }
}
