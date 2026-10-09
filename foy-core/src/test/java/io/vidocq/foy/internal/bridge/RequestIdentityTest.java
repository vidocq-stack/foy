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

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.ServletConnection;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.0 request and connection identity: getRequestId, getProtocolRequestId, getServletConnection. */
class RequestIdentityTest {

    record FakeRequest(HttpVersion version, boolean isSecure, InetSocketAddress remoteAddress,
                       InetSocketAddress localAddress) implements Request {
        @Override public HttpMethod method() { return HttpMethod.GET; }
        @Override public URI uri() { return URI.create("http://localhost/x"); }
        @Override public String path() { return "/x"; }
        @Override public String query() { return null; }
        @Override public Headers headers() { return Headers.of("Host", "localhost"); }
        @Override public Body body() { return Body.empty(); }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
    }

    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 8080);

    private static HttpServletRequestImpl request(HttpVersion version, boolean secure, int remotePort) {
        var chappe = new FakeRequest(version, secure, new InetSocketAddress("127.0.0.1", remotePort), LOCAL);
        return new HttpServletRequestImpl(chappe, new VidocqServletContext("/ctx"), "/ctx", "/x", null);
    }

    @Test
    void requestIdsAreUniqueDecimalStringsStableWithinARequest() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            var r = request(HttpVersion.HTTP_1_1, false, 40000);
            String id = r.getRequestId();
            assertTrue(id.matches("[0-9]+"), id);
            assertEquals(id, r.getRequestId(), "stable for the same request");
            assertTrue(ids.add(id), "unique: " + id);
        }
    }

    @Test
    void protocolRequestIdIsEmptyForHttp1() {
        assertEquals("", request(HttpVersion.HTTP_1_1, false, 40000).getProtocolRequestId());
        assertEquals("", request(HttpVersion.HTTP_1_0, false, 40000).getProtocolRequestId());
    }

    @Test
    void servletConnectionReportsProtocolSecurityAndAStableConnectionId() {
        ServletConnection c = request(HttpVersion.HTTP_1_1, false, 40001).getServletConnection();
        assertEquals("http/1.1", c.getProtocol());
        assertEquals("", c.getProtocolConnectionId());
        assertFalse(c.isSecure());
        assertFalse(c.getConnectionId().isEmpty());
        assertEquals(c.getConnectionId(), request(HttpVersion.HTTP_1_1, false, 40001)
                .getServletConnection().getConnectionId(), "same connection, same id");
        assertNotEquals(c.getConnectionId(), request(HttpVersion.HTTP_1_1, false, 40002)
                .getServletConnection().getConnectionId(), "another connection, another id");

        assertEquals("http/1.0", request(HttpVersion.HTTP_1_0, false, 40003).getServletConnection().getProtocol());
        ServletConnection tls = request(HttpVersion.HTTP_2, true, 40004).getServletConnection();
        assertEquals("h2", tls.getProtocol());
        assertTrue(tls.isSecure());
        assertEquals("h2c", request(HttpVersion.HTTP_2, false, 40005).getServletConnection().getProtocol());
    }
}
