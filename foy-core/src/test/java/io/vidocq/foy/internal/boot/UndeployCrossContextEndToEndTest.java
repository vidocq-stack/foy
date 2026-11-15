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
import io.vidocq.foy.internal.boot.UndeployRequestsEndToEndTest.Probe;
import io.vidocq.foy.internal.boot.UndeployRequestsEndToEndTest.ProbeServlet;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A cross-context dispatch into an application being undeployed (Servlet 6.1 sections 2.3.4 and 4.8,
 * BUG-20261115-04). Once the undeploy starts, {@code getContext} no longer finds the application, and a
 * dispatcher obtained earlier is refused with a temporary {@code UnavailableException}, the 503 of a
 * request refused at the door; a dispatch already running in it is a request in service, waited for
 * before {@code destroy()}. Only the caller application is served: the target is reached by dispatch.
 */
@Timeout(30)
class UndeployCrossContextEndToEndTest {

    private static final String CALLER = "/caller";
    private static final String TARGET = "/target";
    private static final String PROBE_PATH = "/probe";
    private static final String SERVICE = "service";
    private static final String DESTROY = "destroy";

    /** What the caller application records: the dispatcher it keeps, and the failures it saw. */
    static final class Caller {
        final AtomicReference<RequestDispatcher> kept = new AtomicReference<>();
        final List<String> failures = new CopyOnWriteArrayList<>();
    }

    /** {@code /lookup} answers whether {@code getContext} finds the target; {@code /forward} forwards to it. */
    public static final class CallerServlet extends HttpServlet {
        private final transient Caller caller;

        CallerServlet(Caller caller) {
            this.caller = caller;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            ServletContext target = getServletContext().getContext(TARGET);
            if (req.getServletPath().equals("/lookup")) {
                resp.getWriter().write(target == null ? "none" : "found");
                return;
            }
            // The first forward keeps its dispatcher; the later ones reuse it, as an application may.
            RequestDispatcher dispatcher =
                    caller.kept.updateAndGet(current -> current != null ? current : target.getRequestDispatcher(PROBE_PATH));
            try {
                dispatcher.forward(req, resp);
            } catch (ServletException e) {
                caller.failures.add(e.getClass().getSimpleName());
                throw e;
            }
        }
    }

    private final Probe probe = new Probe();
    private final Caller caller = new Caller();
    private final HttpClient client = HttpClient.newHttpClient();
    private Deployment target;
    private Deployment callerDeployment;
    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        probe.serviceGate.countDown();
        probe.destroyGate.countDown();
        if (target != null) target.close();
        if (callerDeployment != null) callerDeployment.close();
        if (server != null) server.stop();
        client.close();
    }

    /** While the target's undeploy runs, {@code getContext} no longer finds it. */
    @Test
    void getContextNoLongerFindsAnApplicationOnceItsUndeployBegan() throws Exception {
        probe.holdDestroy = true;
        deployBoth();
        assertEquals("found", get("/lookup").body());
        Thread undeploy = Thread.ofVirtual().start(target::close);
        assertTrue(probe.destroyEntered.await(5, TimeUnit.SECONDS), DESTROY);
        try {
            assertEquals("none", get("/lookup").body(), "getContext found an application being undeployed");
        } finally {
            probe.destroyGate.countDown();
        }
        assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
    }

    /** A dispatcher obtained before the undeploy no longer reaches the destroyed servlet: 503. */
    @Test
    void aDispatcherObtainedBeforeTheUndeployIsRefused() throws Exception {
        deployBoth();
        assertEquals("served", get("/forward").body());
        target.close();
        HttpResponse<String> refused = get("/forward");
        assertEquals(List.of(SERVICE, DESTROY), probe.events, "a forward reached a destroyed servlet");
        assertEquals(503, refused.statusCode());
        assertEquals(List.of("UnavailableException"), caller.failures);
    }

    /** A forward running in the target is a request in service there: its undeploy waits for it. */
    @Test
    void theUndeployWaitsForACrossContextDispatchInService() throws Exception {
        probe.holdService = true;
        deployBoth();
        CompletableFuture<HttpResponse<String>> response = client.sendAsync(request("/forward"),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(probe.serviceEntered.await(5, TimeUnit.SECONDS), SERVICE);
        Thread undeploy = Thread.ofVirtual().start(target::close);
        assertFalse(undeploy.join(Duration.ofMillis(500)), "the undeploy did not wait for the forward in service");
        probe.serviceGate.countDown();
        assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
        assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
        assertEquals(List.of(SERVICE, "service end", DESTROY), probe.events);
    }

    private void deployBoth() {
        var probeServlet = new ServletDecl("probe", ProbeServlet.class, () -> new ProbeServlet(probe),
                List.of(PROBE_PATH), Map.of(), 1, true);
        target = WebAppDeployer.deploy(WebAppModel.builder(TARGET).servlet(probeServlet).build(),
                DeployOptions.defaults(getClass().getClassLoader()));
        var callerServlet = new ServletDecl("caller", CallerServlet.class, () -> new CallerServlet(caller),
                List.of("/lookup", "/forward"), Map.of(), -1, true);
        callerDeployment = WebAppDeployer.deploy(WebAppModel.builder(CALLER).servlet(callerServlet).build(),
                DeployOptions.defaults(getClass().getClassLoader()));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(callerDeployment.handler());
        server = started.server();
        port = started.port();
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CALLER + path)).GET().build();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(request(path), HttpResponse.BodyHandlers.ofString());
    }
}
