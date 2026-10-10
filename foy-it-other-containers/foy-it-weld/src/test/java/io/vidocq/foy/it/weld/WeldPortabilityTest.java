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
package io.vidocq.foy.it.weld;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.foy.chappe.FoyChappeBoot.Mounted;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.Context;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foy on Chappe with Weld SE as the CDI container (vidocq-workspace#15, foy#18): servlets, filters
 * and listeners are Weld beans with injection, and the CDI request and session contexts are active
 * for each request (foy#18, foy#21). Nothing of Vauban is used, not even foy-cdi-vauban.
 */
class WeldPortabilityTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static WeldContainer container;
    private static Server server;
    private static String base;
    private static Mounted mounted;

    @BeforeAll
    static void start() throws Exception {
        container = new Weld().initialize();
        mounted = FoyChappeBoot.builder()
                .beanManager(container.getBeanManager())
                .classLoader(WeldPortabilityTest.class.getClassLoader())
                .build()
                .orElseThrow();
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
        // Undeploy before Weld shuts down: the session beans of the live sessions are destroyed now.
        if (mounted != null) {
            mounted.close();
        }
        if (container != null) {
            container.close();
        }
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void servletAndFilterGetInjection() throws Exception {
        HttpResponse<String> response = get("/greet/weld");
        assertEquals("hello weld", response.body());
        assertEquals("hello filter", response.headers().firstValue("X-Stamp").orElse(null));
    }

    @Test
    void listenerGetsInjection() throws Exception {
        assertEquals("hello listener", get("/greet/listener").body());
    }

    @Test
    void requestScopedBeanIsOnePerRequest() throws Exception {
        String[] firstRequest = get("/greet/token").body().split("\\|");
        String[] secondRequest = get("/greet/token").body().split("\\|");

        assertEquals(firstRequest[0], firstRequest[1], "one instance within a request");
        assertNotEquals(firstRequest[0], secondRequest[0], "a new instance for each request");
    }

    @Test
    void requestContextEventsFire() throws Exception {
        RequestEvents events = container.select(RequestEvents.class).get();
        int before = events.destroyed();
        get("/greet/weld");
        assertTrue(events.initialized() > 0, "@Initialized(RequestScoped.class) fired");
        assertTrue(events.destroyed() > before, "@Destroyed(RequestScoped.class) fired");
    }

    // ---- session scope (foy#21) ----

    @Test
    void twoClientsGetTwoInstancesEachStableAcrossItsRequests() throws Exception {
        var alice = cookieClient();
        var bob = cookieClient();
        String alice1 = body(alice, "/session/id").split("\\|")[0];
        String alice2 = body(alice, "/session/id").split("\\|")[0];
        String bob1 = body(bob, "/session/id").split("\\|")[0];
        assertEquals(alice1, alice2, "one instance per session");
        assertNotEquals(alice1, bob1, "one instance per session, not per application");
    }

    @Test
    void invalidateGivesANewInstance() throws Exception {
        SessionEvents events = container.select(SessionEvents.class).get();
        var client = cookieClient();
        String before = body(client, "/session/id").split("\\|")[0];
        String[] invalidated = body(client, "/session/invalidate").split("\\|");
        assertEquals(before, invalidated[0]);
        assertEquals(before, invalidated[1], "the instance survives invalidate() until the end of the request");
        String after = body(client, "/session/id").split("\\|")[0];
        assertNotEquals(before, after);
        awaitTrue(() -> events.destroyedBeans().contains(before), "@PreDestroy of the invalidated session's bean");
    }

    @Test
    void sessionContextEventsFire() throws Exception {
        SessionEvents events = container.select(SessionEvents.class).get();
        var client = cookieClient();
        String sessionId = body(client, "/session/id").split("\\|")[1];
        assertTrue(events.initialized().contains(sessionId), "@Initialized(SessionScoped.class) with the HttpSession");
        body(client, "/session/invalidate");
        awaitTrue(() -> events.destroyed().contains(sessionId), "@Destroyed(SessionScoped.class)");
        assertTrue(events.beforeDestroyed().contains(sessionId), "@BeforeDestroyed(SessionScoped.class)");
    }

    @Test
    void expiryDestroysTheSessionBeansWithoutAnotherRequest() throws Exception {
        SessionEvents events = container.select(SessionEvents.class).get();
        mounted.deployment().sessionManager().restartReaper(Duration.ofMillis(100));
        String[] expiring = body(cookieClient(), "/session/expire").split("\\|");
        awaitTrue(() -> events.destroyed().contains(expiring[1]), "@Destroyed after expiry");
        assertTrue(events.destroyedBeans().contains(expiring[0]), "@PreDestroy ran, with a request context");
    }

    @Test
    void weldsOwnSessionContextStaysInactiveNextToFoys() throws Exception {
        var bm = container.getBeanManager();
        var contexts = bm.getContexts(SessionScoped.class);
        assertTrue(contexts.size() >= 2, "Weld's bound session context and Foy's: " + contexts);
        assertTrue(contexts.stream().noneMatch(Context::isActive), "no session context outside a request");
        assertThrows(ContextNotActiveException.class, () -> bm.getContext(SessionScoped.class));
        body(cookieClient(), "/session/id");   // no WELD-001304: a single active context during the request
    }

    @Test
    void filterListenerAndServletShareTheInstanceOfARequest() throws Exception {
        String[] ids = body(cookieClient(), "/session/same").split("\\|");
        assertEquals(ids[2], ids[0], "request listener");
        assertEquals(ids[2], ids[1], "filter");
    }

    /**
     * Documented gap BUG-20261010-07: {@code AsyncContext.start} threads are not bound to the session
     * context, as for the request context; using a session bean there fails cleanly.
     */
    @Test
    void asyncThreadHasNoSessionContext() throws Exception {
        assertTrue(body(cookieClient(), "/session/async").contains("ContextNotActive"));
    }

    private static HttpClient cookieClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private static String body(HttpClient client, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }

    /** Polls with a deadline: the end of a request (and its events) may follow the response. */
    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("timed out after 10 s waiting for " + what);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response;
    }
}
