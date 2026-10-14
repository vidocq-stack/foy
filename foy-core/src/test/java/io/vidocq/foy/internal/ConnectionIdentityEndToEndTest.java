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
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
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
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The connection id and the HTTP/2 stream id, as Chappe reports them (CHAPPE-035), seen by a servlet. */
class ConnectionIdentityEndToEndTest {

    private static final TlsTestCertificates CERTS = TlsTestCertificates.get();

    private Server server;

    private static ChappeServletBridge bridge() {
        var servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("conn=" + req.getServletConnection().getConnectionId()
                        + " preq=" + req.getProtocolRequestId() + " protocol=" + req.getProtocol());
            }
        };
        return new ChappeServletBridge(
                new ServletDispatcher(List.of(
                        new ServletDispatcher.Mapping(UrlPatternMatcher.of("/id"), servlet, "ID"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(new VidocqServletContext("/")), null, "/");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private static String get(HttpClient client, String scheme, int port) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(scheme + "://127.0.0.1:" + port + "/id"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String field(String body, String name) {
        for (String token : body.split(" ")) {
            if (token.startsWith(name + "=")) return token.substring(name.length() + 1);
        }
        throw new AssertionError(name + " missing in " + body);
    }

    @Test
    void keepAliveRequestsShareTheConnectionIdAndSeparateConnectionsDoNot() throws Exception {
        var r = TestServerLauncher.start(bridge());
        server = r.server;
        String a;
        String b;
        String c;
        try (var first = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            a = get(first, "http", r.port);
            b = get(first, "http", r.port);
        }
        try (var second = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            c = get(second, "http", r.port);
        }
        assertTrue(field(a, "conn").matches("[0-9]+"), a);
        assertEquals(field(a, "conn"), field(b, "conn"), "keep-alive: one connection");
        assertNotEquals(field(a, "conn"), field(c, "conn"), "another client, another connection");
        for (String body : List.of(a, b, c)) {
            assertEquals("", field(body, "preq"), body);
            assertEquals("HTTP/1.1", field(body, "protocol"), body);
        }
    }

    @Test
    void http2RequestsCarryTheirStreamIdAndShareTheConnectionId() throws Exception {
        var r = TestServerLauncher.startTls(bridge(), CERTS.serverContext(), ClientAuth.WANT);
        server = r.server;
        String a;
        String b;
        try (var client = HttpClient.newBuilder().sslContext(CERTS.clientContext("alice"))
                .version(HttpClient.Version.HTTP_2).build()) {
            a = get(client, "https", r.port);
            b = get(client, "https", r.port);
        }
        assertEquals("HTTP/2.0", field(a, "protocol"), a);
        assertEquals("HTTP/2.0", field(b, "protocol"), b);
        assertTrue(field(a, "conn").matches("[0-9]+"), a);
        assertEquals(field(a, "conn"), field(b, "conn"), "one HTTP/2 connection");
        for (String body : List.of(a, b)) {
            int stream = Integer.parseInt(field(body, "preq"));
            assertTrue(stream > 0 && stream % 2 == 1, body);
        }
        assertNotEquals(field(a, "preq"), field(b, "preq"));
    }
}
