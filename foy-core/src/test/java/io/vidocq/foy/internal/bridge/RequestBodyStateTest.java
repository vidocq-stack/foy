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
import io.vidocq.foy.internal.http.RequestLimits;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 sections 3.1.1 and 3.12 on the form body: cap, encoding lock, one read only. */
class RequestBodyStateTest {

    record FakeRequest(HttpMethod method, String query, Headers headers, Body body) implements Request {
        @Override public URI uri() { return URI.create("http://localhost/f"); }
        @Override public String path() { return "/f"; }
        @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
    }

    private final VidocqServletContext ctx = new VidocqServletContext("/");

    private HttpServletRequestImpl form(String query, byte[] body, boolean sized) {
        Headers.Builder headers = Headers.builder().add("Host", "localhost")
                .add("Content-Type", "application/x-www-form-urlencoded");
        headers = sized ? headers.add("Content-Length", Integer.toString(body.length))
                : headers.add("Transfer-Encoding", "chunked");
        return new HttpServletRequestImpl(new FakeRequest(HttpMethod.POST, query, headers.build(),
                Body.of(new ByteArrayInputStream(body))), ctx, "/", "/f", null);
    }

    @Test
    void aFormBodyAboveTheCapIsRefusedBeforeItIsRead() throws Exception {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxFormBodySize(8));
        var req = form("q=1", "a=0123456789".getBytes(US_ASCII), true);
        var e = assertThrows(IllegalStateException.class, () -> req.getParameter("a"));
        assertTrue(e.getMessage().contains("maxFormBodySize"), e.getMessage());
        assertEquals("1", req.getParameter("q"), "the query string is still there");
        assertNull(req.getParameter("a"), "no retry: the refused body stays refused");
        assertEquals(-1, req.getInputStream().read(), "the body belongs to the container");
    }

    @Test
    void aChunkedFormBodyAboveTheCapIsRefused() {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxFormBodySize(8));
        var req = form(null, "a=0123456789".getBytes(US_ASCII), false);
        assertThrows(IllegalStateException.class, () -> req.getParameter("a"));
        assertNull(req.getParameter("a"));
    }

    @Test
    void aFormBodyAtTheCapIsAccepted() {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxFormBodySize(12));
        assertEquals("0123456789", form(null, "a=0123456789".getBytes(US_ASCII), true).getParameter("a"));
    }

    @Test
    void parsingTheFormBodyLocksTheEncoding() throws Exception {
        var req = form(null, "a=%C3%A9".getBytes(US_ASCII), true);
        assertEquals("\u00c3\u00a9", req.getParameter("a"), "ISO-8859-1 by default (section 3.12)");
        req.setCharacterEncoding("UTF-8");
        assertNull(req.getCharacterEncoding(), "ignored once the body was parsed");
        assertEquals("\u00c3\u00a9", req.getParameter("a"));
    }

    @Test
    void anEncodingSetBeforeParsingApplies() throws Exception {
        var req = form(null, "a=%C3%A9".getBytes(US_ASCII), true);
        req.setCharacterEncoding("UTF-8");
        assertEquals("\u00e9", req.getParameter("a"));
    }

    @Test
    void queryOnlyParsingLeavesTheEncodingOpen() throws Exception {
        var req = new HttpServletRequestImpl(new FakeRequest(HttpMethod.GET, "a=1",
                Headers.of("Host", "localhost"), Body.empty()), ctx, "/", "/f", null);
        assertEquals("1", req.getParameter("a"));
        req.setCharacterEncoding("UTF-8");
        assertEquals("UTF-8", req.getCharacterEncoding());
    }

    @Test
    void aFailedBodyReadPropagatesOnceAndIsNeverRetried() throws Exception {
        InputStream failing = new InputStream() {
            private int served;
            @Override public int read() throws IOException {
                if (served < 3) { served++; return 'a'; }
                throw new IOException("connection reset");
            }
        };
        var headers = Headers.builder().add("Host", "localhost")
                .add("Content-Type", "application/x-www-form-urlencoded").add("Content-Length", "100").build();
        var req = new HttpServletRequestImpl(new FakeRequest(HttpMethod.POST, "q=1", headers, Body.of(failing)),
                ctx, "/", "/f", null);
        assertThrows(UncheckedIOException.class, () -> req.getParameter("a"));
        assertEquals("1", req.getParameter("q"));
        assertNull(req.getParameter("a"));
        assertEquals(-1, req.getInputStream().read(), "the half-read body is not offered again");
    }
}
