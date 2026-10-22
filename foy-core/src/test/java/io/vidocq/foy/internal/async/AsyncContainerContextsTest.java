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
package io.vidocq.foy.internal.async;

import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-20261010-07: the container's context listeners (the CDI request and session scope ones) are
 * bound around a Runnable given to {@code AsyncContext.start} and around {@code onComplete}; the
 * application's request listeners still see one request.
 */
class AsyncContainerContextsTest {

    /** Stands for a CDI context listener of the container: a per-thread binding. */
    static final class ContainerScope implements ServletRequestListener {
        static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
        static final AtomicInteger INITIALIZED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();
        @Override public void requestInitialized(ServletRequestEvent sre) {
            INITIALIZED.incrementAndGet();
            ACTIVE.set(Boolean.TRUE);
        }
        @Override public void requestDestroyed(ServletRequestEvent sre) {
            DESTROYED.incrementAndGet();
            ACTIVE.remove();
        }
    }

    /** An application listener: it must see the request once, whatever the async threads. */
    public static class AppListener implements ServletRequestListener {
        static final AtomicInteger INITIALIZED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();
        @Override public void requestInitialized(ServletRequestEvent sre) { INITIALIZED.incrementAndGet(); }
        @Override public void requestDestroyed(ServletRequestEvent sre) { DESTROYED.incrementAndGet(); }
    }

    static final AtomicReference<Boolean> ON_START_THREAD = new AtomicReference<>();
    static final AtomicReference<Boolean> ON_COMPLETE = new AtomicReference<>();
    static volatile CountDownLatch completed;

    public static class AsyncServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            var ac = req.startAsync();
            ac.addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) {
                    ON_COMPLETE.set(Boolean.TRUE.equals(ContainerScope.ACTIVE.get()));
                    completed.countDown();
                }
                @Override public void onTimeout(AsyncEvent event) { /* not needed by this test */ }
                @Override public void onError(AsyncEvent event) { /* not needed by this test */ }
                @Override public void onStartAsync(AsyncEvent event) { /* not needed by this test */ }
            });
            ac.start(() -> {
                ON_START_THREAD.set(Boolean.TRUE.equals(ContainerScope.ACTIVE.get()));
                try {
                    ac.getResponse().getWriter().write("done");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                ac.complete();
            });
        }
    }

    private Deployment deployment;
    private io.vidocq.chappe.api.Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
    }

    @Test
    void theContainerContextsAreBoundOnAStartThreadAndInOnComplete() throws Exception {
        ContainerScope.INITIALIZED.set(0);
        ContainerScope.DESTROYED.set(0);
        AppListener.INITIALIZED.set(0);
        AppListener.DESTROYED.set(0);
        ON_START_THREAD.set(null);
        ON_COMPLETE.set(null);
        completed = new CountDownLatch(1);
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("async", AsyncServlet.class, AsyncServlet::new, List.of("/async"),
                        Map.of(), -1, true))
                .listener(new ListenerDecl(AppListener.class, AppListener::new))
                .build();
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()));
        deployment.listeners().registerFirst(new ContainerScope());
        var started = TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + started.port() + "/async"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("done", r.body());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(ON_START_THREAD.get(), "contexts on the AsyncContext.start thread");
        assertTrue(ON_COMPLETE.get(), "contexts in onComplete");
        // The application's listeners see one request; the container's are also bound on the start thread.
        assertEquals(1, AppListener.INITIALIZED.get());
        assertEquals(1, AppListener.DESTROYED.get());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ContainerScope.DESTROYED.get() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(2, ContainerScope.INITIALIZED.get());
        assertEquals(2, ContainerScope.DESTROYED.get());
    }
}
