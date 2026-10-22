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
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261022-06, second part: the application code Foy runs at deployment and undeployment
 * (initializers, context listeners, servlet and filter {@code init}/{@code destroy}) sees the
 * application's class loader as context class loader, and a cross-context dispatch runs the target
 * application with the target's loader, the caller's coming back when the dispatch returns.
 */
class DeploymentContextClassLoaderTest {

    private static final ClassLoader TEST = DeploymentContextClassLoaderTest.class.getClassLoader();
    /** The application's loader. */
    private static final ClassLoader APP = new ClassLoader("foy-test-application", TEST) {};
    /** The loader of the other application, the target of a cross-context dispatch. */
    private static final ClassLoader TARGET_APP = new ClassLoader("foy-test-target-application", TEST) {};
    /** The loader of the thread that deploys, undeploys and starts the server. */
    private static final ClassLoader DEPLOYER = new ClassLoader("foy-test-deployer", TEST) {};

    static final Map<String, ClassLoader> SEEN = new ConcurrentHashMap<>();

    private static void seen(String where) {
        SEEN.put(where, Thread.currentThread().getContextClassLoader());
    }

    static final class Initializer implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> c, ServletContext ctx) { seen("onStartup"); }
    }

    public static class ContextListener implements ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent sce) { seen("contextInitialized"); }
        @Override public void contextDestroyed(ServletContextEvent sce) { seen("contextDestroyed"); }
    }

    public static class LifecycleFilter implements Filter {
        @Override public void init(FilterConfig config) { seen("filter.init"); }
        @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(request, response);
        }
        @Override public void destroy() { seen("filter.destroy"); }
    }

    public static class LifecycleServlet extends HttpServlet {
        @Override public void init(ServletConfig config) throws ServletException {
            super.init(config);
            seen("servlet.init");
        }
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("ok");
        }
        @Override public void destroy() { seen("servlet.destroy"); }
    }

    /** Includes, then forwards to, a servlet of the other context; records its loader after each. */
    public static class CrossContextCaller extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            ServletContext other = getServletContext().getContext(TARGET_PATH);
            other.getRequestDispatcher("/t").include(req, resp);
            seen("caller.afterInclude");
            if (req.getParameter("forward") != null) {
                resp.resetBuffer();
                other.getRequestDispatcher("/t").forward(req, resp);
                seen("caller.afterForward");
            }
        }
    }

    public static class CrossContextTarget extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            seen("target:" + req.getDispatcherType());
            resp.getWriter().write("target");
        }
    }

    /**
     * The target's async work: an {@code AsyncListener}, an {@code AsyncContext.start} runnable that
     * sets a {@code WriteListener}, whose {@code onWritePossible} writes and completes.
     */
    public static class AsyncTarget extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) {
            seen("asyncTarget:" + req.getDispatcherType());
            var ac = req.startAsync();
            ac.addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) {
                    seen("target.onComplete");
                    asyncDone.countDown();
                }
                @Override public void onTimeout(AsyncEvent event) {}
                @Override public void onError(AsyncEvent event) {}
                @Override public void onStartAsync(AsyncEvent event) {}
            });
            ac.start(() -> {
                seen("target.asyncStart");
                try {
                    ServletOutputStream out = ac.getResponse().getOutputStream();
                    out.setWriteListener(new WriteListener() {
                        @Override public void onWritePossible() throws IOException {
                            seen("target.onWritePossible");
                            out.write("target-async".getBytes(StandardCharsets.US_ASCII));
                            ac.complete();
                        }
                        @Override public void onError(Throwable t) { ac.complete(); }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /** Reaches {@link AsyncTarget} of the other context by a forward, or by {@code AsyncContext.dispatch(ctx, path)}. */
    public static class AsyncCaller extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            ServletContext other = getServletContext().getContext(TARGET_PATH);
            if (req.getParameter("forward") != null) {
                other.getRequestDispatcher("/a").forward(req, resp);
            } else {
                req.startAsync().dispatch(other, "/a");
            }
            seen("asyncCaller.after");
        }
    }

    private static final String CALLER_PATH = "/tccl-caller";
    private static final String TARGET_PATH = "/tccl-target";

    static volatile CountDownLatch asyncDone;

    private final List<Deployment> deployments = new ArrayList<>();
    private io.vidocq.chappe.api.Server server;

    @BeforeEach
    void reset() {
        SEEN.clear();
        asyncDone = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        for (Deployment d : deployments) d.close();
    }

    /** Runs {@code action} on the current thread with {@link #DEPLOYER} as its context loader. */
    private static <T> T asDeployer(java.util.function.Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(DEPLOYER);
        try {
            T result = action.get();
            assertSame(DEPLOYER, thread.getContextClassLoader(), "the deploying thread gets its own loader back");
            return result;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void deploymentAndUndeploymentRunTheApplicationWithItsLoader() {
        var model = WebAppModel.builder("/")
                .initializer(new Initializer())
                .listener(new ListenerDecl(ContextListener.class, ContextListener::new))
                .filter(new FilterDecl("lifecycle", LifecycleFilter.class, LifecycleFilter::new, Map.of(), false))
                .filterMapping(new FilterMappingDecl("lifecycle", "/*", null, Set.of(DispatcherType.REQUEST)))
                .servlet(new ServletDecl("lifecycle", LifecycleServlet.class, LifecycleServlet::new, List.of("/x"),
                        Map.of(), 1, false))
                .build();

        Deployment deployment = asDeployer(() -> WebAppDeployer.deploy(model, DeployOptions.defaults(APP)));
        assertSame(APP, SEEN.get("onStartup"), "ServletContainerInitializer.onStartup");
        assertSame(APP, SEEN.get("contextInitialized"), "ServletContextListener.contextInitialized");
        assertSame(APP, SEEN.get("filter.init"), "Filter.init");
        assertSame(APP, SEEN.get("servlet.init"), "Servlet.init (load-on-startup)");

        asDeployer(() -> { deployment.close(); return null; });
        assertSame(APP, SEEN.get("filter.destroy"), "Filter.destroy");
        assertSame(APP, SEEN.get("servlet.destroy"), "Servlet.destroy");
        assertSame(APP, SEEN.get("contextDestroyed"), "ServletContextListener.contextDestroyed");
    }

    /** Deploys the target and the caller contexts, and serves the caller; answers the port. */
    private int deployBoth() {
        var target = WebAppModel.builder(TARGET_PATH)
                .servlet(new ServletDecl("target", CrossContextTarget.class, CrossContextTarget::new, List.of("/t"),
                        Map.of(), -1, false))
                .servlet(new ServletDecl("asyncTarget", AsyncTarget.class, AsyncTarget::new, List.of("/a"),
                        Map.of(), -1, true))
                .build();
        var caller = WebAppModel.builder(CALLER_PATH)
                .servlet(new ServletDecl("caller", CrossContextCaller.class, CrossContextCaller::new, List.of("/c"),
                        Map.of(), -1, false))
                .servlet(new ServletDecl("asyncCaller", AsyncCaller.class, AsyncCaller::new, List.of("/ac"),
                        Map.of(), -1, true))
                .build();
        deployments.add(WebAppDeployer.deploy(target, DeployOptions.defaults(TARGET_APP)));
        Deployment callerDeployment = WebAppDeployer.deploy(caller, DeployOptions.defaults(APP));
        deployments.add(callerDeployment);
        var started = asDeployer(() -> TestServerLauncherAccess.start(callerDeployment.handler()));
        server = started.server();
        return started.port();
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theAsyncWorkOfAForwardedTargetRunsWithTheTargetLoader() throws Exception {
        int port = deployBoth();

        HttpResponse<String> r = get(port, CALLER_PATH + "/ac?forward=1");

        assertEquals("target-async", r.body());
        assertTrue(asyncDone.await(5, TimeUnit.SECONDS), "onComplete fired");
        assertSame(TARGET_APP, SEEN.get("asyncTarget:FORWARD"), "forwarded target");
        assertSame(APP, SEEN.get("asyncCaller.after"), "the caller's loader after the forward");
        assertSame(TARGET_APP, SEEN.get("target.asyncStart"), "the target's AsyncContext.start runnable");
        assertSame(TARGET_APP, SEEN.get("target.onWritePossible"), "the target's WriteListener");
        assertSame(TARGET_APP, SEEN.get("target.onComplete"), "the target's AsyncListener");
    }

    @Test
    void theAsyncWorkOfACrossContextAsyncDispatchTargetRunsWithTheTargetLoader() throws Exception {
        int port = deployBoth();

        HttpResponse<String> r = get(port, CALLER_PATH + "/ac");

        assertEquals("target-async", r.body());
        assertTrue(asyncDone.await(5, TimeUnit.SECONDS), "onComplete fired");
        assertSame(APP, SEEN.get("asyncCaller.after"), "the caller");
        assertSame(TARGET_APP, SEEN.get("asyncTarget:ASYNC"), "the target of AsyncContext.dispatch(ctx, path)");
        assertSame(TARGET_APP, SEEN.get("target.asyncStart"), "the target's AsyncContext.start runnable");
        assertSame(TARGET_APP, SEEN.get("target.onWritePossible"), "the target's WriteListener");
        assertSame(TARGET_APP, SEEN.get("target.onComplete"), "the target's AsyncListener");
    }

    @Test
    void aCrossContextDispatchRunsTheTargetWithItsLoaderAndGivesTheCallerItsOwnBack() throws Exception {
        int port = deployBoth();

        HttpResponse<String> r = get(port, CALLER_PATH + "/c?forward=1");

        assertEquals("target", r.body());
        assertSame(TARGET_APP, SEEN.get("target:INCLUDE"), "cross-context include target");
        assertSame(APP, SEEN.get("caller.afterInclude"), "the caller's loader after the include");
        assertSame(TARGET_APP, SEEN.get("target:FORWARD"), "cross-context forward target");
        assertSame(APP, SEEN.get("caller.afterForward"), "the caller's loader after the forward");
    }
}
