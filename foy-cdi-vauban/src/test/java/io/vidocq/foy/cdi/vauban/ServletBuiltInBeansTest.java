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

import io.vidocq.foy.cdi.vauban.TestApps.ProcessorPath;
import io.vidocq.foy.cdi.vauban.TestApps.Result;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static io.vidocq.foy.cdi.vauban.TestApps.boot;
import static io.vidocq.foy.cdi.vauban.TestApps.deploy;
import static io.vidocq.foy.cdi.vauban.TestApps.withTccl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Inject HttpServletRequest}, {@code HttpSession} and {@code ServletContext} in an application served by
 * Foy on Vauban (CDI 4.1 §3.11, built-in beans of the Jakarta EE integration): the application declares nothing,
 * foy-cdi-vauban on its path is enough. One application, compiled once with vauban-processor, booted on a fresh
 * container by each test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Servlet built-in beans: HttpServletRequest, HttpSession and ServletContext on Vauban")
class ServletBuiltInBeansTest {

    /** The logger of the warning a second deployment on the same container gets. */
    private static final String SERVLET_CONTEXT_LOGGER = "io.vidocq.foy.cdi.vauban.ServletContextHolder";

    /** Vauban's warning for any class it generates at run time instead of at build time. */
    private static final String RUNTIME_GENERATION_LOGGER = "io.vidocq.vauban.core.container.RuntimeGeneration";

    private static final String NO_REQUEST = "No servlet request";
    private static final String NO_CONTEXT = "No ServletContext";
    private static final String NPE = "NullPointerException";
    private static final String NOT_ACTIVE = "ContextNotActiveException";

    /** The context path the tests deploy the application at. */
    private static final String APP = "/app";
    private static final String FIRST = "/first";

    /** What the tests ask the application's {@code Probe}. */
    private static final String REQUEST = "request";
    private static final String SESSION = "session";
    private static final String CONTEXT = "context";
    private static final String EVENTS = "events";

    private static final String PROBE = """
            package beans.app;

            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.enterprise.event.Observes;
            import jakarta.enterprise.event.Startup;
            import jakarta.inject.Inject;
            import jakarta.servlet.ServletContext;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpSession;
            import java.util.List;
            import java.util.concurrent.CopyOnWriteArrayList;
            import java.util.function.Function;
            import java.util.function.Supplier;

            /** What the test asks the application outside any request, and what the application saw. */
            @ApplicationScoped
            public class Probe implements Function<String, String> {
                @Inject HttpServletRequest request;
                @Inject HttpSession session;
                @Inject ServletContext context;
                private final List<String> events = new CopyOnWriteArrayList<>();

                void onStartup(@Observes Startup startup) {
                    record("startup request " + describe(request::getRequestURI));
                    record("startup context " + describe(context::getContextPath));
                }

                public void record(String event) {
                    events.add(event);
                }

                @Override
                public String apply(String what) {
                    return switch (what) {
                        case "request" -> describe(request::getRequestURI);
                        case "session" -> describe(session::getId);
                        case "context" -> describe(context::getContextPath);
                        default -> String.join("\\n", events);
                    };
                }

                /** The value, or the exception chain: the class and the message of each cause. */
                public static String describe(Supplier<String> value) {
                    try {
                        return value.get();
                    } catch (RuntimeException e) {
                        var chain = new StringBuilder();
                        for (Throwable t = e; t != null; t = t.getCause()) {
                            chain.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
                        }
                        return chain.toString();
                    }
                }
            }
            """;

    private static final String TOKEN = """
            package beans.app;

            @jakarta.enterprise.context.RequestScoped
            public class Token {
                private final String id = java.util.UUID.randomUUID().toString();

                public String id() {
                    return id;
                }
            }
            """;

    private static final String CONTEXT_LISTENER = """
            package beans.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.ServletContext;
            import jakarta.servlet.ServletContextEvent;
            import jakarta.servlet.ServletContextListener;

            @jakarta.servlet.annotation.WebListener
            public class ContextProbe implements ServletContextListener {
                @Inject ServletContext context;
                @Inject Probe probe;

                @Override
                public void contextInitialized(ServletContextEvent event) {
                    String seen = Probe.describe(context::getContextPath);
                    String attribute = Probe.describe(() -> {
                        context.setAttribute("probe", "set through the bean");
                        return String.valueOf(event.getServletContext().getAttribute("probe"));
                    });
                    probe.record("initialized " + event.getServletContext().getContextPath() + " sees " + seen
                            + " " + attribute);
                }

                @Override
                public void contextDestroyed(ServletContextEvent event) {
                    probe.record("destroyed " + event.getServletContext().getContextPath() + " sees "
                            + Probe.describe(context::getContextPath));
                }
            }
            """;

    private static final String BEANS_SERVLET = """
            package beans.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.ServletContext;
            import jakarta.servlet.http.HttpServlet;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;
            import jakarta.servlet.http.HttpSession;

            @jakarta.servlet.annotation.WebServlet("/beans")
            public class BeansServlet extends HttpServlet {
                @Inject HttpServletRequest request;
                @Inject HttpSession session;
                @Inject ServletContext context;

                @Override
                protected void doGet(HttpServletRequest q, HttpServletResponse r) throws java.io.IOException {
                    Integer visits = (Integer) session.getAttribute("visits");
                    int next = visits == null ? 1 : visits + 1;
                    session.setAttribute("visits", next);
                    r.getWriter().write(String.join("|", request.getRequestURI(), request.getHeader("X-Probe"),
                            String.valueOf(next), context.getContextPath(),
                            String.valueOf(session.getId().equals(q.getSession(false).getId()))));
                }
            }
            """;

    private static final String PROXIES_SERVLET = """
            package beans.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.ServletContext;
            import jakarta.servlet.http.HttpServlet;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;
            import jakarta.servlet.http.HttpSession;

            @jakarta.servlet.annotation.WebServlet("/proxies")
            public class ProxiesServlet extends HttpServlet {
                @Inject HttpServletRequest request;
                @Inject HttpSession session;
                @Inject ServletContext context;

                @Override
                protected void doGet(HttpServletRequest q, HttpServletResponse r) throws java.io.IOException {
                    r.getWriter().write(kind(request) + "\\n" + kind(session) + "\\n" + kind(context));
                }

                private static String kind(Object bean) {
                    return bean.getClass().getName() + " " + java.lang.reflect.Proxy.isProxyClass(bean.getClass());
                }
            }
            """;

    private static final String FORWARD_SERVLET = """
            package beans.app;

            @jakarta.servlet.annotation.WebServlet("/forward")
            public class ForwardServlet extends jakarta.servlet.http.HttpServlet {
                @Override
                protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                        jakarta.servlet.http.HttpServletResponse r)
                        throws jakarta.servlet.ServletException, java.io.IOException {
                    q.setAttribute("via", "forward");
                    q.getRequestDispatcher("/target").forward(q, r);
                }
            }
            """;

    private static final String INCLUDE_SERVLET = """
            package beans.app;

            @jakarta.servlet.annotation.WebServlet("/include")
            public class IncludeServlet extends jakarta.servlet.http.HttpServlet {
                @Override
                protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                        jakarta.servlet.http.HttpServletResponse r)
                        throws jakarta.servlet.ServletException, java.io.IOException {
                    q.setAttribute("via", "include");
                    q.getRequestDispatcher("/target").include(q, r);
                }
            }
            """;

    private static final String TARGET_SERVLET = """
            package beans.app;

            @jakarta.servlet.annotation.WebServlet("/target")
            public class TargetServlet extends jakarta.servlet.http.HttpServlet {
                @jakarta.inject.Inject
                jakarta.servlet.http.HttpServletRequest request;

                @Override
                protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                        jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                    r.getWriter().write(request.getAttribute("via") + "|" + request.getRequestURI());
                }
            }
            """;

    private static final String ASYNC_SERVLET = """
            package beans.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.AsyncContext;
            import jakarta.servlet.AsyncEvent;
            import jakarta.servlet.AsyncListener;
            import jakarta.servlet.DispatcherType;
            import jakarta.servlet.http.HttpServlet;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;

            @jakarta.servlet.annotation.WebServlet(urlPatterns = "/async", asyncSupported = true)
            public class AsyncServlet extends HttpServlet {
                @Inject HttpServletRequest request;
                @Inject Token token;
                @Inject Probe probe;

                @Override
                protected void doGet(HttpServletRequest q, HttpServletResponse r) {
                    if (q.getDispatcherType() == DispatcherType.ASYNC) {
                        // The re-dispatch of "restart": a second cycle, which fires onStartAsync.
                        q.startAsync().complete();
                        return;
                    }
                    String mode = q.getParameter("mode");
                    probe.record(mode + " doGet " + token.id());
                    AsyncContext async = q.startAsync();
                    switch (mode) {
                        case "start" -> async.start(() -> {
                            try {
                                async.getResponse().getWriter().write(Probe.describe(request::getRequestURI)
                                        + "|" + Probe.describe(token::id));
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            } finally {
                                async.complete();
                            }
                        });
                        case "complete" -> {
                            async.addListener(new Recorder(mode));
                            async.complete();
                        }
                        case "timeout" -> {
                            async.addListener(new Recorder(mode));
                            async.setTimeout(100);
                        }
                        case "error" -> {
                            async.addListener(new Recorder(mode));
                            async.start(() -> {
                                throw new IllegalStateException("failed on purpose");
                            });
                        }
                        case "restart" -> {
                            async.addListener(new Recorder(mode));
                            async.dispatch();
                        }
                        default -> throw new IllegalArgumentException(mode);
                    }
                }

                /** Records each event with the request URI and the request-scoped token it sees. */
                private final class Recorder implements AsyncListener {
                    private final String mode;

                    Recorder(String mode) {
                        this.mode = mode;
                    }

                    private void record(String event) {
                        probe.record(mode + " " + event + " " + Probe.describe(request::getRequestURI) + " "
                                + Probe.describe(token::id));
                    }

                    @Override public void onComplete(AsyncEvent event) { record("onComplete"); }
                    @Override public void onTimeout(AsyncEvent event) { record("onTimeout"); }
                    @Override public void onError(AsyncEvent event) { record("onError"); }
                    @Override public void onStartAsync(AsyncEvent event) { record("onStartAsync"); }
                }
            }
            """;

    private static final String READ_SERVLET = """
            package beans.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.AsyncContext;
            import jakarta.servlet.ReadListener;
            import jakarta.servlet.ServletInputStream;
            import jakarta.servlet.http.HttpServlet;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;

            @jakarta.servlet.annotation.WebServlet(urlPatterns = "/read", asyncSupported = true)
            public class ReadServlet extends HttpServlet {
                @Inject HttpServletRequest request;

                @Override
                protected void doPost(HttpServletRequest q, HttpServletResponse r) throws java.io.IOException {
                    AsyncContext async = q.startAsync();
                    ServletInputStream in = q.getInputStream();
                    in.setReadListener(new ReadListener() {
                        @Override
                        public void onDataAvailable() throws java.io.IOException {
                            byte[] buffer = new byte[256];
                            int read = 0;
                            while (in.isReady() && read >= 0) {
                                read = in.read(buffer);
                            }
                        }

                        @Override
                        public void onAllDataRead() throws java.io.IOException {
                            r.getWriter().write(Probe.describe(request::getRequestURI));
                            async.complete();
                        }

                        @Override
                        public void onError(Throwable failure) {
                            async.complete();
                        }
                    });
                }
            }
            """;

    /** An application whose initializer reaches the ServletContext bean through a bean of its own. */
    private static final String CONTEXT_USER = """
            package sci.app;

            @jakarta.enterprise.context.ApplicationScoped
            public class ContextUser {
                @jakarta.inject.Inject
                jakarta.servlet.ServletContext context;

                public String contextPath() {
                    return context.getContextPath();
                }
            }
            """;

    private static final String INITIALIZER = """
            package sci.app;

            public class Starter implements jakarta.servlet.ServletContainerInitializer {
                @Override
                public void onStartup(java.util.Set<Class<?>> classes, jakarta.servlet.ServletContext ctx) {
                    String seen;
                    try {
                        seen = jakarta.enterprise.inject.spi.CDI.current().select(ContextUser.class).get()
                                .contextPath();
                    } catch (RuntimeException e) {
                        seen = e.toString();
                    }
                    ctx.setAttribute("seen", seen);
                }
            }
            """;

    private static final String SEEN_SERVLET = """
            package sci.app;

            @jakarta.servlet.annotation.WebServlet("/seen")
            public class SeenServlet extends jakarta.servlet.http.HttpServlet {
                @Override
                protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                        jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                    r.getWriter().write(String.valueOf(getServletContext().getAttribute("seen")));
                }
            }
            """;

    private Result app;

    @BeforeAll
    void compileTheApplication(@TempDir Path tempDir) throws Exception {
        app = TestApps.compile(tempDir, ProcessorPath.CLASS_PATH, List.of(), PROBE, TOKEN, CONTEXT_LISTENER,
                BEANS_SERVLET, PROXIES_SERVLET, FORWARD_SERVLET, INCLUDE_SERVLET, TARGET_SERVLET, ASYNC_SERVLET,
                READ_SERVLET);
        assertTrue(app.success(), app::messages);
    }

    @Test
    @DisplayName("the behaviour change: an application's own producer of HttpServletRequest makes an ambiguous dependency at start")
    void anApplicationProducerIsAmbiguous(@TempDir Path tempDir) throws Exception {
        var own = TestApps.compile(tempDir, ProcessorPath.CLASS_PATH, List.of(), """
                package own.app;

                @jakarta.enterprise.context.ApplicationScoped
                public class OwnRequest {
                    @jakarta.inject.Inject
                    jakarta.servlet.http.HttpServletRequest injected;

                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.context.RequestScoped
                    jakarta.servlet.http.HttpServletRequest request() {
                        throw new UnsupportedOperationException("never called: the deployment fails first");
                    }
                }
                """);
        assertTrue(own.success(), own::messages);
        try (var loader = own.loader()) {
            Callable<VaubanContainer> start = () -> boot(loader);
            var failure = assertThrows(DeploymentException.class, () -> withTccl(loader, start));
            var message = failure.getMessage();
            assertTrue(message.toLowerCase(Locale.ROOT).contains("ambiguous")
                    && message.contains(HttpServletRequest.class.getName()), message);
        }
    }

    @Test
    @DisplayName("the documented limit: no request context in a ReadListener callback, so the injected request fails there with ContextNotActiveException")
    void noRequestContextInAReadListener() throws Exception {
        try (var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader));
             var served = deploy(container, loader, APP)) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(served.uri("/app/read"))
                    .POST(HttpRequest.BodyPublishers.ofString("some body")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response::body);
            assertTrue(response.body().contains(NOT_ACTIVE), response::body);
        }
    }

    @Test
    @DisplayName("foy-cdi-vauban's archive carries its bean index and the processed marker: a deployment does not run the extensions again at start")
    void theModuleIsIndexedAndProcessedAtBuild() throws IOException {
        var classes = TestApps.classesOf(FoyWebExtension.class);
        try (var lines = Files.lines(classes.resolve("META-INF/vauban-beans.list"))) {
            assertEquals(List.of(ServletBuiltInBeans.class.getName(), ServletContextHolder.class.getName(),
                            ServletRequestHolder.class.getName()),
                    lines.map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).sorted().toList());
        }
        assertTrue(Files.exists(classes.resolve("META-INF/vauban-bce-processed")),
                "without the marker, every deployment runs its build compatible extensions again at start");
    }

    @Test
    @DisplayName("a servlet injects the request, the session and the context: URI and header, a session attribute across requests, the context path")
    void aServletInjectsTheThreeBeans() throws Exception {
        try (var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader));
             var served = deploy(container, loader, APP)) {
            var uri = served.uri("/app/beans");
            var alice = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            var bob = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            assertEquals("/app/beans|a1|1|/app|true", get(alice, uri, "a1"));
            assertEquals("/app/beans|a2|2|/app|true", get(alice, uri, "a2"), "the same session for the same client");
            assertEquals("/app/beans|b1|1|/app|true", get(bob, uri, "b1"), "another client, another session");
        }
    }

    @Test
    @DisplayName("the client proxies are the ones vauban-processor generated at Foy's build: no reflect.Proxy, no runtime generation")
    void theClientProxiesAreGeneratedAtBuild() throws Exception {
        try (var generation = LogRecords.of(RUNTIME_GENERATION_LOGGER);
             var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader));
             var served = deploy(container, loader, APP)) {
            var kinds = get(HttpClient.newHttpClient(), served.uri("/app/proxies"), null).lines().toList();
            assertEquals(3, kinds.size(), kinds::toString);
            for (var kind : kinds) {
                assertTrue(kind.startsWith(FoyWebExtension.class.getPackageName() + "."),
                        "a class generated in foy-cdi-vauban's package: " + kind);
                assertTrue(kind.endsWith(" false"), "not a java.lang.reflect.Proxy: " + kind);
            }
            assertEquals(List.of(), generation.warnings(), "no RUNTIME CODE GENERATION FALLBACK");
        }
    }

    @Test
    @DisplayName("the forwarded and the included servlet get the request the container serves, with what the first servlet set on it")
    void theRequestOnAForwardAndAnInclude() throws Exception {
        try (var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader));
             var served = deploy(container, loader, APP)) {
            var client = HttpClient.newHttpClient();
            assertEquals("forward|/app/forward", get(client, served.uri("/app/forward"), null));
            assertEquals("include|/app/include", get(client, served.uri("/app/include"), null));
        }
    }

    @Test
    @DisplayName("an AsyncContext.start task gets the request and its own request-scoped beans")
    void theRequestInAnAsyncStartThread() throws Exception {
        try (var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader));
             var served = deploy(container, loader, APP)) {
            var body = get(HttpClient.newHttpClient(), served.uri("/app/async?mode=start"), null);
            assertTrue(body.startsWith("/app/async|"), body);
            assertFalse(body.contains("Exception"), body);
        }
    }

    @Test
    @DisplayName("an AsyncListener gets the request and the request-scoped beans of the request in onComplete, onTimeout, onError and onStartAsync")
    void theRequestInAnAsyncListener() throws Exception {
        try (var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader));
             var served = deploy(container, loader, APP)) {
            var client = HttpClient.newHttpClient();
            for (var mode : List.of("complete", "timeout", "error", "restart")) {
                send(client, served.uri("/app/async?mode=" + mode));
            }
            var events = probe(container, loader).apply(EVENTS).lines().toList();
            for (var expected : List.of("complete onComplete", "timeout onTimeout", "timeout onComplete",
                    "error onError", "error onComplete", "restart onStartAsync")) {
                var mode = expected.substring(0, expected.indexOf(' '));
                var token = tokenOf(events, mode);
                assertTrue(events.contains(expected + " /app/async " + token),
                        expected + " sees the request and the token of the request (" + token + "): " + events);
            }
        }
    }

    @Test
    @DisplayName("outside a servlet request the beans fail clearly: no request context, a request context without a request, the @Startup event")
    void outsideARequest() throws Exception {
        try (var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader))) {
            var probe = probe(container, loader);

            var noContext = probe.apply(REQUEST);
            assertTrue(noContext.contains(NOT_ACTIVE), noContext);

            var controller = container.getBeanManager().createInstance().select(RequestContextController.class).get();
            controller.activate();
            String request;
            String session;
            try {
                request = probe.apply(REQUEST);
                session = probe.apply(SESSION);
            } finally {
                controller.deactivate();
            }
            assertTrue(request.contains(IllegalStateException.class.getName() + ": " + NO_REQUEST), request);
            assertFalse(request.contains(NPE), request);
            assertTrue(session.contains(NOT_ACTIVE), "no session context without a servlet request: " + session);

            var context = probe.apply(CONTEXT);
            assertTrue(context.contains(IllegalStateException.class.getName() + ": " + NO_CONTEXT), context);

            var startup = probe.apply(EVENTS);
            assertTrue(startup.lines().anyMatch(l -> l.startsWith("startup request ") && l.contains(NOT_ACTIVE)),
                    startup);
            assertTrue(startup.contains("startup context " + IllegalStateException.class.getName()), startup);
            assertTrue(startup.contains(NO_CONTEXT), startup);
            assertFalse(startup.contains(NPE), startup);
        }
    }

    @Test
    @DisplayName("an application ServletContextListener uses the injected context in contextInitialized and contextDestroyed")
    void theContextInTheApplicationListeners() throws Exception {
        try (var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader))) {
            var probe = probe(container, loader);
            try (var served = deploy(container, loader, APP)) {
                assertEquals(served.mounted().servletContext().getContextPath(), probe.apply(CONTEXT));
            }
            var events = probe.apply(EVENTS).lines().toList();
            assertTrue(events.contains("initialized /app sees /app set through the bean"), events::toString);
            assertTrue(events.contains("destroyed /app sees /app"), events::toString);
            assertTrue(probe.apply(CONTEXT).contains(NO_CONTEXT), "emptied after the undeploy");
        }
    }

    @Test
    @DisplayName("a second deployment on the same container keeps the first one's context with one WARNING; after an undeploy, a deploy gives the new context")
    void twoDeploymentsAndARedeploy() throws Exception {
        try (var warnings = LogRecords.of(SERVLET_CONTEXT_LOGGER);
             var loader = app.loader();
             var container = withTccl(loader, () -> boot(loader))) {
            var probe = probe(container, loader);
            try (var _ = deploy(container, loader, FIRST)) {
                try (var _ = deploy(container, loader, "/second")) {
                    assertEquals(FIRST, probe.apply(CONTEXT));
                    var logged = warnings.warnings();
                    assertEquals(1, logged.size(), logged::toString);
                    assertTrue(logged.getFirst().contains(FIRST) && logged.getFirst().contains("/second"),
                            logged::toString);
                }
                assertEquals(FIRST, probe.apply(CONTEXT), "the second undeploy leaves the first one's context");
            }
            assertTrue(probe.apply(CONTEXT).contains(NO_CONTEXT), "emptied once the first is undeployed");
            try (var _ = deploy(container, loader, "/reloaded")) {
                assertEquals("/reloaded", probe.apply(CONTEXT), "a dev-mode reload gives the new context");
            }
            assertEquals(1, warnings.warnings().size(), "a redeploy after an undeploy warns of nothing");
        }
    }

    @Test
    @DisplayName("a ServletContainerInitializer reaches the injected context through a bean in onStartup")
    void theContextInAnInitializer(@TempDir Path tempDir) throws Exception {
        var initialized = TestApps.compile(tempDir, ProcessorPath.CLASS_PATH, List.of(), CONTEXT_USER, INITIALIZER,
                SEEN_SERVLET);
        assertTrue(initialized.success(), initialized::messages);
        var services = Files.createDirectories(initialized.classes().resolve("META-INF/services"));
        Files.writeString(services.resolve(ServletContainerInitializer.class.getName()), "sci.app.Starter\n");
        try (var loader = initialized.loader();
             var container = withTccl(loader, () -> boot(loader));
             var served = deploy(container, loader, "/sci")) {
            assertEquals("/sci", get(HttpClient.newHttpClient(), served.uri("/sci/seen"), null));
        }
    }

    /** The token the request of {@code mode} saw in {@code doGet}. */
    private static String tokenOf(List<String> events, String mode) {
        var prefix = mode + " doGet ";
        return events.stream().filter(e -> e.startsWith(prefix)).map(e -> e.substring(prefix.length()))
                .findFirst().orElseThrow(() -> new AssertionError("no doGet for " + mode + ": " + events));
    }

    @SuppressWarnings("unchecked")
    private static Function<String, String> probe(VaubanContainer container, ClassLoader loader) throws Exception {
        var type = loader.loadClass("beans.app.Probe");
        return (Function<String, String>) container.getBeanManager().createInstance().select(type).get();
    }

    private static String get(HttpClient client, URI uri, String probeHeader) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri);
        if (probeHeader != null) {
            request.header("X-Probe", probeHeader);
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response::body);
        return response.body();
    }

    /** Sends a request whatever its status: the error cases of the async tests end in a 500. */
    private static void send(HttpClient client, URI uri) throws IOException, InterruptedException {
        client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding());
    }
}
