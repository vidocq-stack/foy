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
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * and listeners are Weld beans with injection, and the CDI request context is active for each
 * request. Nothing of Vauban is used, not even foy-cdi-vauban.
 */
class WeldPortabilityTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static WeldContainer container;
    private static Server server;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        container = new Weld().initialize();
        var mounted = FoyChappeBoot.builder()
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

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response;
    }
}
