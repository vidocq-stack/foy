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
import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The Servlet section 3.10 attributes, over TLS on HTTP/1.1 and HTTP/2, and their absence on a plain connection. */
class TlsAttributesEndToEndTest {

    private static final TlsTestCertificates CERTS = TlsTestCertificates.get();
    private static final AtomicInteger ADDED = new AtomicInteger();

    private Server tlsServer;
    private int tlsPort;
    private Server plainServer;

    private static HttpServlet describe() {
        return new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                var out = resp.getWriter();
                var names = new TreeSet<String>();
                for (String n : Collections.list(req.getAttributeNames())) {
                    if (n.startsWith("jakarta.servlet.request.")) names.add(n);
                }
                for (String n : names) out.println(n);
                if (req.getAttribute("jakarta.servlet.request.X509Certificate") instanceof X509Certificate[] chain
                        && chain.length > 0) {
                    out.println("leaf=" + chain[0].getSubjectX500Principal().getName());
                }
                out.println("secure=" + req.isSecure() + " scheme=" + req.getScheme());
                out.println("protocol=" + req.getProtocol());
            }
        };
    }

    private static HttpServletRequestImpl impl(ServletRequest req) {
        while (req instanceof ServletRequestWrapper w) req = w.getRequest();
        return (HttpServletRequestImpl) req;
    }

    private static HttpServlet peers() {
        return new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("peers=" + impl(req).peerCertificates().length);
            }
        };
    }

    private static HttpServlet mutate() {
        return new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                var chain = (X509Certificate[]) req.getAttribute("jakarta.servlet.request.X509Certificate");
                chain[0] = null;
                req.removeAttribute("jakarta.servlet.request.X509Certificate");
                var impl = impl(req);
                var first = impl.peerCertificates();
                first[0] = null;
                var again = impl.peerCertificates();
                resp.getWriter().write("mutated peers=" + again.length + " leaf="
                        + again[0].getSubjectX500Principal().getName()
                        + " distinct=" + (first != again) + " attribute=" + req.getAttribute("jakarta.servlet.request.X509Certificate"));
            }
        };
    }

    private ChappeServletBridge bridge() {
        var ctx = new VidocqServletContext("/");
        ctx.addListener(new ServletRequestAttributeListener() {
            @Override public void attributeAdded(ServletRequestAttributeEvent e) { ADDED.incrementAndGet(); }
        });
        return new ChappeServletBridge(
                new ServletDispatcher(List.of(
                        new ServletDispatcher.Mapping(UrlPatternMatcher.of("/a"), describe(), "A"),
                        new ServletDispatcher.Mapping(UrlPatternMatcher.of("/mutate"), mutate(), "M"),
                        new ServletDispatcher.Mapping(UrlPatternMatcher.of("/peers"), peers(), "P"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(ctx), null, "/");
    }

    @BeforeEach
    void setUp() {
        ADDED.set(0);
        var r = TestServerLauncher.startTls(bridge(), CERTS.serverContext(), ClientAuth.WANT);
        tlsServer = r.server;
        tlsPort = r.port;
    }

    @AfterEach
    void tearDown() {
        if (tlsServer != null) tlsServer.stop();
        if (plainServer != null) plainServer.stop();
    }

    private static HttpClient client(HttpClient.Version version, SSLContext ssl) {
        return HttpClient.newBuilder().sslContext(ssl).version(version).build();
    }

    private String get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + tlsPort + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** GETs {@code /a}, and checks the version really negotiated: chappe offers h2 through ALPN by default. */
    private String get(HttpClient.Version version, SSLContext ssl) throws Exception {
        String body = get(client(version, ssl), "/a");
        String expected = version == HttpClient.Version.HTTP_2 ? "protocol=HTTP/2" : "protocol=HTTP/1.1";
        assertTrue(body.contains(expected), body);
        return body;
    }

    @ParameterizedTest
    @EnumSource(value = HttpClient.Version.class)
    void aClientCertificateFillsTheAttributes(HttpClient.Version version) throws Exception {
        String body = get(version, CERTS.clientContext("alice"));
        assertTrue(body.contains("jakarta.servlet.request.X509Certificate"), body);
        assertTrue(body.contains("jakarta.servlet.request.cipher_suite"), body);
        assertTrue(body.contains("jakarta.servlet.request.key_size"), body);
        assertTrue(body.contains("jakarta.servlet.request.ssl_session_id"), body);
        assertTrue(body.contains("jakarta.servlet.request.secure_protocol"), body);
        assertTrue(body.contains("leaf=CN=Alice Example"), body);
        assertTrue(body.contains("secure=true scheme=https"), body);
        assertEquals(0, ADDED.get(), "container attributes fire no listener");
    }

    @ParameterizedTest
    @EnumSource(value = HttpClient.Version.class)
    void noClientCertificateLeavesOnlyTheConnectionAttributes(HttpClient.Version version) throws Exception {
        String body = get(version, CERTS.clientContextWithoutCertificate());
        assertFalse(body.contains("X509Certificate"), body);
        assertTrue(body.contains("jakarta.servlet.request.cipher_suite"), body);
    }

    @Test
    void aPlainRequestHasNoTlsAttribute() throws Exception {
        var r = TestServerLauncher.start(bridge());
        plainServer = r.server;
        String body = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port + "/a"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertFalse(body.contains("jakarta.servlet.request."), body);
        assertTrue(body.contains("secure=false scheme=http"), body);
    }

    @Test
    void theCertificateArrayIsPerRequest() throws Exception {
        var client = client(HttpClient.Version.HTTP_1_1, CERTS.clientContext("alice"));
        String mutated = get(client, "/mutate");
        // within the request, the connection's chain survives both the array mutation and the removal
        assertTrue(mutated.startsWith("mutated peers=1 leaf=CN=Alice Example"), mutated);
        assertTrue(mutated.contains("distinct=true attribute=null"), mutated);
        String body = get(client, "/a");
        assertTrue(body.contains("leaf=CN=Alice Example"), body);
    }

    @Test
    void peerCertificatesIsEmptyWithoutAClientCertificate() throws Exception {
        var client = client(HttpClient.Version.HTTP_1_1, CERTS.clientContextWithoutCertificate());
        assertEquals("peers=0", get(client, "/peers"));
    }

    @Test
    void peerCertificatesIsEmptyOnAPlainConnection() throws Exception {
        var r = TestServerLauncher.start(bridge());
        plainServer = r.server;
        String body = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port + "/peers"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("peers=0", body);
    }
}
