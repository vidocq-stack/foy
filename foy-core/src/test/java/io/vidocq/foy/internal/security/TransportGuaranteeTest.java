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
package io.vidocq.foy.internal.security;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.bridge.HttpServletResponseImpl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class TransportGuaranteeTest {

    record FakeRequest(boolean isSecure, String host, String path, String query) implements Request {
        FakeRequest(boolean isSecure, String host) { this(isSecure, host, "/conf/x", "y=1"); }
        @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
        @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("127.0.0.1", 40000); }
        @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public HttpMethod method() { return HttpMethod.GET; }
        @Override public URI uri() { return URI.create("http://localhost/conf/x"); }
        @Override public Headers headers() { return Headers.of("Host", host); }
        @Override public Body body() { return Body.empty(); }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
    }

    private static WebSecurity security(int port) {
        var settings = SecuritySettings.DEFAULTS.withConfidentialPort(port);
        var table = ConstraintTable.builder()
                .descriptor(constraint(List.of("/conf/*"), List.of(), List.of(), null, "CONFIDENTIAL")).build();
        return new WebSecurity(table, new BasicAuthenticator(settings.identityStore(), "r"), settings);
    }

    private static HttpServletRequestImpl request(boolean secure, String host) {
        return request(new FakeRequest(secure, host));
    }

    private static HttpServletRequestImpl request(FakeRequest chappe) {
        return new HttpServletRequestImpl(chappe, new VidocqServletContext("/"), "", "/conf", "/x");
    }

    @Test
    void secureRequestsProceed() throws Exception {
        assertTrue(security(-1).admit(request(true, "example.org"), new HttpServletResponseImpl(), "/conf/x"));
    }

    @Test
    void plainRequestsAreRefusedWithoutAPort() throws Exception {
        var res = new HttpServletResponseImpl();
        assertFalse(security(-1).admit(request(false, "example.org"), res, "/conf/x"));
        assertEquals(403, res.getStatus());
    }

    @Test
    void plainRequestsAreRedirectedToTheConfiguredPort() throws Exception {
        var res = new HttpServletResponseImpl();
        assertFalse(security(8443).admit(request(false, "example.org:8080"), res, "/conf/x"));
        assertEquals(302, res.getStatus());
        assertEquals("https://example.org:8443/conf/x?y=1", res.getHeader("Location"));
    }

    @Test
    void theDefaultHttpsPortIsLeftOutAndAnAbsentQueryAddsNoQuestionMark() throws Exception {
        var res = new HttpServletResponseImpl();
        assertFalse(security(443).admit(request(new FakeRequest(false, "example.org", "/conf/x", null)), res, "/conf/x"));
        assertEquals(302, res.getStatus());
        assertEquals("https://example.org/conf/x", res.getHeader("Location"));
    }

    @Test
    void aHostileHostHeaderIsRefusedNotRedirected() throws Exception {
        for (String host : List.of("evil.example/@x", "a b", "user@evil.example", "[::1]:8080")) {
            var res = new HttpServletResponseImpl();
            assertFalse(security(8443).admit(request(false, host), res, "/conf/x"), host);
            assertEquals(403, res.getStatus(), host);
            assertNull(res.getHeader("Location"), host);
        }
    }

    @Test
    void aRawUriThatCouldMoveTheAuthorityIsRefusedNotRedirected() throws Exception {
        // "https://example.org:8443" + "@evil.example/x" would make example.org:8443 a userinfo.
        for (var chappe : List.of(
                new FakeRequest(false, "example.org", "@evil.example/conf/x", null),
                new FakeRequest(false, "example.org", "/conf/x", "a\r\nSet-Cookie: x=1"),
                new FakeRequest(false, "example.org", "/conf/x y", null))) {
            var res = new HttpServletResponseImpl();
            assertFalse(security(8443).admit(request(chappe), res, "/conf/x"), chappe.toString());
            assertEquals(403, res.getStatus(), chappe.toString());
            assertNull(res.getHeader("Location"), chappe.toString());
        }
    }

    @Test
    void plainHosts() {
        assertTrue(WebSecurity.isPlainHost("example.org"));
        assertTrue(WebSecurity.isPlainHost("127.0.0.1"));
        assertFalse(WebSecurity.isPlainHost(""));
        assertFalse(WebSecurity.isPlainHost(null));
        assertFalse(WebSecurity.isPlainHost("a/b"));
        assertFalse(WebSecurity.isPlainHost("a\r\nSet-Cookie:x"));
        assertFalse(WebSecurity.isPlainHost("a".repeat(254)));
    }
}
