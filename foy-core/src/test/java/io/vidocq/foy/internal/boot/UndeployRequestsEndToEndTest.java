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
import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The requests around an undeploy (Servlet 6.1 section 2.3.4). The documented order closes the deployment
 * before the server stops, so requests still arrive while the application goes away: from the moment the
 * undeploy starts, a new request gets 503 and never reaches a filter or a servlet (BUG-20261115-02), and
 * the requests already in service may finish, for a bounded time, before {@code destroy()}
 * (BUG-20261115-03).
 */
@Timeout(30)
class UndeployRequestsEndToEndTest {

    private static final String PATH = "/probe";
    private static final String SERVICE = "service";
    private static final String DESTROY = "destroy";

    /** What the servlet of one test records, and where it can be held. */
    static final class Probe {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch serviceEntered = new CountDownLatch(1);
        final CountDownLatch serviceGate = new CountDownLatch(1);
        final CountDownLatch destroyEntered = new CountDownLatch(1);
        final CountDownLatch destroyGate = new CountDownLatch(1);
        volatile boolean holdService;
        volatile boolean holdDestroy;
    }

    /** Records its requests and its {@code destroy()}; either can be held on a gate of the probe. */
    public static final class ProbeServlet extends HttpServlet {
        private final transient Probe probe;

        ProbeServlet(Probe probe) {
            this.probe = probe;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            probe.events.add(SERVICE);
            if (probe.holdService) {
                probe.serviceEntered.countDown();
                await(probe.serviceGate);
                probe.events.add("service end");
            }
            resp.getWriter().write("served");
        }

        @Override
        public void destroy() {
            probe.events.add(DESTROY);
            if (probe.holdDestroy) {
                probe.destroyEntered.countDown();
                await(probe.destroyGate);
            }
        }

        private static void await(CountDownLatch gate) {
            try {
                if (!gate.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("the gate never opened");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final Probe probe = new Probe();
    /** Holds the close of an upgraded connection a test registers. */
    private final CountDownLatch stuckConnection = new CountDownLatch(1);
    private final HttpClient client = HttpClient.newHttpClient();
    private Deployment deployment;
    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        probe.serviceGate.countDown();
        probe.destroyGate.countDown();
        stuckConnection.countDown();
        if (deployment != null) deployment.close();
        if (server != null) server.stop();
        client.close();
    }

    /** Once the undeploy is over and before the server stops, a request gets 503 and the servlet never sees it. */
    @Test
    void aRequestAfterTheUndeployGets503() throws Exception {
        deploy();
        assertEquals(200, get().statusCode());
        deployment.close();
        assertEquals(503, get().statusCode());
        assertEquals(List.of(SERVICE, DESTROY), probe.events, "the servlet served a request after its undeploy");
    }

    /** From the moment the undeploy starts: a request while the servlet's {@code destroy()} runs gets 503. */
    @Test
    void aRequestWhileTheUndeployRunsGets503() throws Exception {
        probe.holdDestroy = true;
        deploy();
        Thread undeploy = Thread.ofVirtual().start(deployment::close);
        assertTrue(probe.destroyEntered.await(5, TimeUnit.SECONDS), DESTROY);
        try {
            assertEquals(503, get().statusCode());
            assertEquals(List.of(DESTROY), probe.events, "the servlet served a request during its undeploy");
        } finally {
            probe.destroyGate.countDown();
        }
        assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
    }

    /** The undeploy lets a request in service finish before it destroys the servlet. */
    @Test
    void theUndeployLetsARequestInServiceFinishFirst() throws Exception {
        probe.holdService = true;
        deploy();
        CompletableFuture<HttpResponse<String>> response = getAsync();
        assertTrue(probe.serviceEntered.await(5, TimeUnit.SECONDS), SERVICE);
        Thread undeploy = Thread.ofVirtual().start(deployment::close);
        assertFalse(undeploy.join(Duration.ofMillis(500)), "the undeploy did not wait for the request in service");
        probe.serviceGate.countDown();
        assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
        assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
        assertEquals(List.of(SERVICE, "service end", DESTROY), probe.events);
    }

    /** A request still in service after the grace does not hold the undeploy: it goes on, with a WARNING. */
    @Test
    void theUndeployDoesNotWaitForARequestPastItsGrace() throws Exception {
        probe.holdService = true;
        deploy();
        CompletableFuture<HttpResponse<String>> response = getAsync();
        assertTrue(probe.serviceEntered.await(5, TimeUnit.SECONDS), SERVICE);
        try (var log = LogCapture.of(VidocqServletContext.class.getName())) {
            Thread undeploy = Thread.ofVirtual().start(deployment::close);
            assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy waited past its grace");
            assertEquals(List.of(SERVICE, DESTROY), probe.events);
            assertTrue(log.warnings().stream().anyMatch(w -> w.contains("requests still in service (1)")),
                    log.warnings().toString());
        } finally {
            probe.serviceGate.countDown();
        }
        assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
    }

    /**
     * The grace of the upgraded connections (3 s) and that of the requests in service (2 s) both count from
     * the start of the undeploy: a stuck connection and a stuck request together hold it about 3 s before
     * {@code destroy()}, not 3 s and then 2 s more.
     */
    @Test
    void aStuckConnectionAndAStuckRequestShareTheUndeploysStart() throws Exception {
        probe.holdService = true;
        deploy();
        CompletableFuture<HttpResponse<String>> response = getAsync();
        assertTrue(probe.serviceEntered.await(5, TimeUnit.SECONDS), SERVICE);
        assertTrue(deployment.servletContext().registerUpgradedConnection(() -> {
            if (!stuckConnection.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
        }));
        try (var log = LogCapture.of(VidocqServletContext.class.getName())) {
            long start = System.nanoTime();
            Thread undeploy = Thread.ofVirtual().start(deployment::close);
            assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy did not return");
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            assertTrue(took.compareTo(Duration.ofMillis(4_500)) < 0, "the undeploy took " + took);
            assertEquals(List.of(SERVICE, DESTROY), probe.events);
            assertTrue(log.warnings().stream().anyMatch(w -> w.contains("requests still in service (1)")),
                    log.warnings().toString());
        } finally {
            stuckConnection.countDown();
            probe.serviceGate.countDown();
        }
        assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
    }

    private void deploy() {
        var servlet = new ServletDecl("probe", ProbeServlet.class, () -> new ProbeServlet(probe), List.of(PATH),
                Map.of(), 1, true);
        deployment = WebAppDeployer.deploy(WebAppModel.builder("/").servlet(servlet).build(),
                DeployOptions.defaults(getClass().getClassLoader()));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        port = started.port();
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH)).GET().build();
    }

    private HttpResponse<String> get() throws IOException, InterruptedException {
        return client.send(request(), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> getAsync() {
        return client.sendAsync(request(), HttpResponse.BodyHandlers.ofString());
    }
}
