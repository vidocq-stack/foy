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
package io.vidocq.foy.internal.bridge;

import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.HandlesTypesResolver;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.internal.gen.RegistryComponentFactory;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261022-06 (Vidocq/bodoni BOD-002): the application code of a request runs with the
 * application's class loader as the thread context class loader, whatever the server thread
 * inherited, and the thread gets its own loader back when Foy takes control again. A library that
 * looks things up by name through the context loader ({@code ResourceBundle}, {@code ServiceLoader})
 * then sees the application's modules.
 */
class RequestContextClassLoaderTest {

    /** The application's loader, distinct from the test's and from the server threads'. */
    private static final ClassLoader APP =
            new ClassLoader("foy-test-application", RequestContextClassLoaderTest.class.getClassLoader()) {};
    /** The loader the server threads inherit (the test thread's while the server starts). */
    private static final ClassLoader SERVER =
            new ClassLoader("foy-test-server", RequestContextClassLoaderTest.class.getClassLoader()) {};
    /** The loader of a thread of the application that calls {@code AsyncContext.start}. */
    private static final ClassLoader FOREIGN =
            new ClassLoader("foy-test-foreign", RequestContextClassLoaderTest.class.getClassLoader()) {};

    /** The context loader seen at each point of the request, by name. */
    static final Map<String, ClassLoader> SEEN = new ConcurrentHashMap<>();
    static final AtomicReference<Thread> SERVING_THREAD = new AtomicReference<>();
    static volatile CountDownLatch asyncDone;

    private static void seen(String where) {
        SEEN.put(where, Thread.currentThread().getContextClassLoader());
    }

    public static class Listener implements ServletRequestListener {
        @Override public void requestInitialized(ServletRequestEvent sre) { seen("requestInitialized"); }
        @Override public void requestDestroyed(ServletRequestEvent sre) { seen("requestDestroyed"); }
    }

    public static class Recording implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            seen("filter:" + request.getDispatcherType());
            chain.doFilter(request, response);
        }
    }

    /** Records its loader, the servlet context's, and forwards to {@code /target}. */
    public static class Forwarding extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            seen("servlet");
            SEEN.put("getClassLoader", getServletContext().getClassLoader());
            SERVING_THREAD.set(Thread.currentThread());
            req.getRequestDispatcher("/target").forward(req, resp);
        }
    }

    public static class Target extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            seen("target:" + req.getDispatcherType());
            resp.getWriter().write("ok");
        }
    }

    public static class Throwing extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
            throw new ServletException("boom");
        }
    }

    public static class ErrorPage extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            seen("errorPage");
            resp.getWriter().write("error page");
        }
    }

    /**
     * Starts an async cycle, then calls {@code AsyncContext.start} from a thread of its own whose
     * context loader is another one: the runnable still gets the application's loader.
     */
    public static class Async extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
            var ac = req.startAsync();
            ac.addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) {
                    seen("onComplete");
                    asyncDone.countDown();
                }
                @Override public void onTimeout(AsyncEvent event) {
                    // Not needed by this test: the cycle completes long before its timeout.
                }
                @Override public void onError(AsyncEvent event) {
                    // Not needed by this test: the cycle never fails.
                }
                @Override public void onStartAsync(AsyncEvent event) {
                    // Not needed by this test: no second cycle starts on this request.
                }
            });
            Thread caller = Thread.ofVirtual().unstarted(() -> ac.start(() -> {
                seen("asyncStart");
                ac.dispatch("/target");
            }));
            caller.setContextClassLoader(FOREIGN);
            caller.start();
            try {
                caller.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServletException(e);
            }
        }
    }

    private Deployment deployment;
    private io.vidocq.chappe.api.Server server;
    private int port;

    @BeforeEach
    void reset() {
        SEEN.clear();
        SERVING_THREAD.set(null);
        asyncDone = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
    }

    private static WebAppModel model() {
        Set<DispatcherType> all = Set.of(DispatcherType.REQUEST, DispatcherType.FORWARD,
                DispatcherType.ASYNC, DispatcherType.ERROR);
        return WebAppModel.builder("/")
                .listener(new ListenerDecl(Listener.class, Listener::new))
                .filter(new FilterDecl("recording", Recording.class, Recording::new, Map.of(), true))
                .filterMapping(new FilterMappingDecl("recording", "/*", null, all))
                .servlet(new ServletDecl("forwarding", Forwarding.class, Forwarding::new, List.of("/forward"),
                        Map.of(), -1, false))
                .servlet(new ServletDecl("target", Target.class, Target::new, List.of("/target"),
                        Map.of(), -1, true))
                .servlet(new ServletDecl("throwing", Throwing.class, Throwing::new, List.of("/throw"),
                        Map.of(), -1, false))
                .servlet(new ServletDecl("error", ErrorPage.class, ErrorPage::new, List.of("/err"),
                        Map.of(), -1, false))
                .servlet(new ServletDecl("async", Async.class, Async::new, List.of("/async"),
                        Map.of(), -1, true))
                .errorPages(new ErrorPageRegistry().register(500, "/err"))
                .build();
    }

    /** Deploys and starts the server from a thread whose context loader is {@link #SERVER}. */
    private void start(DeployOptions options) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(SERVER);
        try {
            deployment = WebAppDeployer.deploy(model(), options);
            var started = TestServerLauncherAccess.start(deployment.handler());
            server = started.server();
            port = started.port();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theRequestRunsWithTheApplicationLoaderAndTheThreadGetsItsOwnBack() throws Exception {
        start(DeployOptions.defaults(APP));

        Thread caller = Thread.currentThread();
        ClassLoader callerLoader = caller.getContextClassLoader();
        HttpResponse<String> r = get("/forward");

        assertEquals("ok", r.body());
        assertSame(APP, SEEN.get("requestInitialized"), "request listener, requestInitialized");
        assertSame(APP, SEEN.get("filter:REQUEST"), "filter");
        assertSame(APP, SEEN.get("servlet"), "servlet service");
        assertSame(APP, SEEN.get("getClassLoader"), "ServletContext.getClassLoader()");
        assertSame(APP, SEEN.get("filter:FORWARD"), "filter on the forward");
        assertSame(APP, SEEN.get("target:FORWARD"), "forward target");
        assertSame(APP, SEEN.get("requestDestroyed"), "request listener, requestDestroyed");

        // The thread that ran the application gets the loader it had before, once Foy is done with it.
        Thread serving = SERVING_THREAD.get();
        assertTrue(serving.join(Duration.ofSeconds(5)), "the serving thread ends");
        assertSame(SERVER, serving.getContextClassLoader(), "the serving thread's own loader is restored");
        assertSame(callerLoader, caller.getContextClassLoader(), "the client thread is never touched");
    }

    @Test
    void theErrorPageRunsWithTheApplicationLoader() throws Exception {
        start(DeployOptions.defaults(APP));

        HttpResponse<String> r = get("/throw");

        assertEquals(500, r.statusCode());
        assertEquals("error page", r.body());
        assertSame(APP, SEEN.get("filter:ERROR"), "filter on the error dispatch");
        assertSame(APP, SEEN.get("errorPage"), "error page");
    }

    @Test
    void asyncStartListenersAndDispatchRunWithTheApplicationLoader() throws Exception {
        start(DeployOptions.defaults(APP));

        HttpResponse<String> r = get("/async");

        assertEquals("ok", r.body());
        assertTrue(asyncDone.await(5, TimeUnit.SECONDS), "onComplete fired");
        assertSame(APP, SEEN.get("asyncStart"), "AsyncContext.start runnable, started from a foreign thread");
        assertSame(APP, SEEN.get("filter:ASYNC"), "filter on the async dispatch");
        assertSame(APP, SEEN.get("target:ASYNC"), "async dispatch target");
        assertSame(APP, SEEN.get("onComplete"), "AsyncListener.onComplete");
    }

    @Test
    void withoutAnApplicationLoaderTheContextLoaderIsLeftUntouched() throws Exception {
        var registry = io.vidocq.foy.internal.gen.WebComponentRegistry.forClassLoader(APP);
        start(new DeployOptions(null, null, null, Set.of(), Set.of(), Set.of(),
                new RegistryComponentFactory(registry, APP), HandlesTypesResolver.NONE));

        HttpResponse<String> r = get("/forward");

        assertEquals("ok", r.body());
        assertSame(SERVER, SEEN.get("servlet"), "the inherited loader stays");
    }
}
