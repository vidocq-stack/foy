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

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HttpServletResponseImplTest {

    private static String body(HttpServletResponseImpl res) {
        return new String(res.bodyBytes(), StandardCharsets.ISO_8859_1);
    }

    private static jakarta.servlet.http.HttpServletRequest request(String uri) {
        return (jakarta.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                HttpServletResponseImplTest.class.getClassLoader(),
                new Class<?>[] {jakarta.servlet.http.HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getScheme" -> "http";
                    case "getServerName" -> "example.com";
                    case "getServerPort" -> 8080;
                    case "getRequestURI" -> uri;
                    default -> null;
                });
    }

    private static String redirectLocation(String requestUri, String location) throws IOException {
        var res = new HttpServletResponseImpl();
        res.bindRequest(request(requestUri));
        res.sendRedirect(location);
        return res.getHeader("Location");
    }

    @Test
    void relativeRedirectLocationsAreResolvedPerSection57() throws IOException {
        assertEquals("http://example.com:8080/app/x", redirectLocation("/app/a/b", "/app/x"));
        assertEquals("http://example.com:8080/app/a/x", redirectLocation("/app/a/b", "x"));
        assertEquals("http://example.com:8080/app/x", redirectLocation("/app/a/b", "../x"));
        assertEquals("http://example.com:8080/x", redirectLocation("/app/a/b", "../../x"));
        assertEquals("http://example.com:8080/app/a/b?q=1", redirectLocation("/app/a/b", "?q=1"));
        assertEquals("http://example.com:8080/app/a/x?q=1#f", redirectLocation("/app/a/b", "./x?q=1#f"));
        assertEquals("https://other.org/y", redirectLocation("/app/a/b", "https://other.org/y"));
        assertEquals("mailto:a@b.c", redirectLocation("/app/a/b", "mailto:a@b.c"));
    }

    @Test
    void sendRedirectWithStatusOverloadSetsStatusAndLocation() throws IOException {
        var res = new HttpServletResponseImpl();
        res.bindRequest(request("/a/b"));
        res.sendRedirect("c", HttpServletResponse.SC_MOVED_PERMANENTLY, true);
        assertEquals(301, res.getStatus());
        assertEquals("http://example.com:8080/a/c", res.getHeader("Location"));
        assertTrue(res.isCommitted());
    }

    @Test
    void responseIsCommittedOnceTheDeclaredContentLengthIsWritten() throws IOException {
        var res = new HttpServletResponseImpl();
        res.setContentLength(5);
        res.getWriter().write("0123456789");
        res.addIntHeader("header1", 12345);
        assertTrue(res.isCommitted());
        assertFalse(res.containsHeader("header1"));
        assertEquals("01234", body(res));
    }

    @Test
    void responseIsNotCommittedBeforeTheContentLengthIsReached() throws IOException {
        var res = new HttpServletResponseImpl();
        res.setContentLength(50);
        res.getWriter().write("abc");
        assertFalse(res.isCommitted());
        res.addIntHeader("header1", 1);
        assertTrue(res.containsHeader("header1"));
    }

    @Test
    void sendErrorDropsTheDeclaredContentLength() throws IOException {
        var res = new HttpServletResponseImpl();
        res.setContentLength(5);
        res.sendError(500, "boom");
        assertFalse(res.containsHeader("Content-Length"));
        assertTrue(body(res).contains("boom"), body(res));
    }

    @Test
    void sendRedirectDropsTheDeclaredContentLength() throws IOException {
        var res = new HttpServletResponseImpl();
        res.setContentLength(5);
        res.getWriter().write("abc");
        res.sendRedirect("http://example.com/n");
        assertFalse(res.containsHeader("Content-Length"));
    }

    @Test
    void setLocaleHonoursCommitByContentLength() throws IOException {
        var res = new HttpServletResponseImpl();
        res.setContentLength(2);
        res.getWriter().write("abcd");
        res.setLocale(Locale.FRANCE);
        assertNull(res.getHeader("Content-Language"));
    }

    @Test
    void getHeadersIsAMutableCopy() {
        var res = new HttpServletResponseImpl();
        res.addHeader("X-A", "1");
        res.addHeader("X-A", "2");
        Collection<String> copy = res.getHeaders("X-A");
        copy.remove("1");
        copy.add("3");
        assertEquals(List.of("1", "2"), List.copyOf(res.getHeaders("X-A")));
        assertTrue(res.getHeaders("X-None").isEmpty());
    }

    @Test
    void negativeBufferSizeIsClampedToZero() {
        var res = new HttpServletResponseImpl();
        res.setBufferSize(-5);
        assertEquals(0, res.getBufferSize());
    }

    @Test
    void headersIgnoredAfterFlushBuffer() throws IOException {
        var res = new HttpServletResponseImpl();
        res.setHeader("h0", "0");
        res.flushBuffer();
        res.setHeader("h1", "1");
        res.addHeader("h2", "2");
        res.setIntHeader("h3", 3);
        res.addIntHeader("h4", 4);
        res.setDateHeader("h5", 0L);
        res.addDateHeader("h6", 0L);
        res.setStatus(404);
        res.setContentType("text/plain");
        res.setContentLength(10);
        res.setContentLengthLong(10L);
        res.setCharacterEncoding("UTF-8");
        res.setLocale(Locale.FRANCE);
        assertEquals(200, res.getStatus());
        assertEquals(Set.of("h0"), new HashSet<>(res.getHeaderNames()));
        assertNull(res.getContentType());
    }

    @Test
    void sendErrorAfterCommitThrows() throws IOException {
        var res = new HttpServletResponseImpl();
        res.flushBuffer();
        assertThrows(IllegalStateException.class, () -> res.sendError(500));
        assertThrows(IllegalStateException.class, () -> res.sendError(500, "x"));
    }

    @Test
    void resetAfterCommitThrows() throws IOException {
        var res = new HttpServletResponseImpl();
        res.flushBuffer();
        assertThrows(IllegalStateException.class, res::reset);
        assertThrows(IllegalStateException.class, res::resetBuffer);
        assertThrows(IllegalStateException.class, () -> res.sendRedirect("/x"));
        assertThrows(IllegalStateException.class, () -> res.sendRedirect("/x", 301, true));
    }

    @Test
    void sendRedirectClearsTheBufferAndClosesTheResponse() throws IOException {
        var res = new HttpServletResponseImpl();
        res.getWriter().write("Test FAILED");
        res.sendRedirect("http://example.com/next");
        res.getWriter().write("more FAILED");
        res.getWriter().flush();
        assertEquals(302, res.getStatus());
        assertEquals("http://example.com/next", res.getHeader("Location"));
        assertTrue(res.isCommitted());
        assertFalse(body(res).contains("FAILED"));
        res.setHeader("late", "x");
        assertFalse(res.containsHeader("late"));
    }

    @Test
    void sendRedirectKeepsTheBufferWhenAskedTo() throws IOException {
        var res = new HttpServletResponseImpl();
        res.getWriter().write("kept");
        res.sendRedirect("http://example.com/n", 301, false);
        res.getWriter().write("dropped");
        assertEquals(301, res.getStatus());
        assertEquals("kept", body(res));
    }

    @Test
    void writesAfterSendErrorAreDiscarded() throws IOException {
        var res = new HttpServletResponseImpl();
        res.sendError(HttpServletResponse.SC_NOT_FOUND, "nope");
        String before = body(res);
        res.getWriter().write("after");
        assertEquals(before, body(res));
        assertTrue(res.isCommitted());
    }

    @Test
    void defaultCharacterEncodingIsIso88591() {
        var res = new HttpServletResponseImpl();
        assertEquals("ISO-8859-1", res.getCharacterEncoding());
        res.setContentType("text/html");
        assertEquals("ISO-8859-1", res.getCharacterEncoding());
        var json = new HttpServletResponseImpl();
        json.setContentType("application/json");
        assertEquals("ISO-8859-1", json.getCharacterEncoding());
        // The wire Content-Type must not gain a charset that was never set.
        assertEquals("application/json", json.getHeader("Content-Type"));
        assertEquals("application/json", json.getContentType());
    }

    @Test
    void explicitCharsetStillWins() {
        var res = new HttpServletResponseImpl();
        res.setCharacterEncoding("UTF-8");
        assertEquals("UTF-8", res.getCharacterEncoding());
        var ctx = new HttpServletResponseImpl();
        ctx.setDefaultCharacterEncoding("UTF-16");
        assertEquals("UTF-16", ctx.getCharacterEncoding());
    }

    @Test
    void setLocaleAfterGetWriterKeepsTheCharset() throws IOException {
        var res = new HttpServletResponseImpl();
        res.getWriter();
        String enc = res.getCharacterEncoding();
        res.setLocale(Locale.JAPAN);
        assertEquals(enc, res.getCharacterEncoding());
    }

    @Test
    void setLocaleAfterCommitDoesNothing() throws IOException {
        var res = new HttpServletResponseImpl();
        res.setLocale(Locale.FRANCE);
        res.flushBuffer();
        res.setLocale(Locale.GERMANY);
        assertEquals(Locale.FRANCE, res.getLocale());
        assertEquals("fr-FR", res.getHeader("Content-Language"));
    }

    // ---- Header injection (CR, LF, NUL, non-Latin-1) ----

    private static void assertNoInjectedLine(HttpServletResponseImpl res) {
        for (var e : res.allHeaders().entrySet()) {
            assertFalse(e.getKey().equalsIgnoreCase("Set-Cookie"), "injected header " + e);
            for (String v : e.getValue()) {
                for (char c : v.toCharArray()) {
                    assertTrue(c != '\r' && c != '\n' && c != 0 && c <= 0xff, "unsafe char in " + e.getKey() + ": " + v);
                }
            }
        }
    }

    @Test
    void redirectLocationNeverCarriesCrLf() throws IOException {
        var res = new HttpServletResponseImpl();
        res.bindRequest(request("/app/a"));
        res.sendRedirect("/x\r\nSet-Cookie: a=b");
        assertEquals("http://example.com:8080/x%0D%0ASet-Cookie:%20a=b", res.getHeader("Location"));
        assertNoInjectedLine(res);
    }

    @Test
    void redirectLocationPercentEncodesNonAscii() throws IOException {
        // U+010D U+010A: a byte cast of each char would yield CR LF on the wire.
        var res = new HttpServletResponseImpl();
        res.bindRequest(request("/app/a"));
        res.sendRedirect("/xčĊSet-Cookie");
        assertEquals("http://example.com:8080/x%C4%8D%C4%8ASet-Cookie", res.getHeader("Location"));
        assertNoInjectedLine(res);
        assertEquals("http://example.com:8080/caf%C3%A9", redirectLocation("/app/a", "/café"));
        assertEquals("http://example.com:8080/a%20b?q=%25x", redirectLocation("/app/a", "/a b?q=%25x"));
    }

    @Test
    void unboundRedirectIsSanitisedToo() throws IOException {
        var res = new HttpServletResponseImpl();
        res.sendRedirect("/x\r\nSet-Cookie: a=b");
        assertEquals("/x%0D%0ASet-Cookie:%20a=b", res.getHeader("Location"));
    }

    @Test
    void setHeaderAndAddHeaderRejectCrLfNulAndNonLatin1() {
        var res = new HttpServletResponseImpl();
        assertThrows(IllegalArgumentException.class, () -> res.setHeader("X", "a\r\nSet-Cookie: a=b"));
        assertThrows(IllegalArgumentException.class, () -> res.addHeader("X", "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> res.addHeader("X", "a\u0000b"));
        assertThrows(IllegalArgumentException.class, () -> res.setHeader("X", "ačĊb"));
        assertThrows(IllegalArgumentException.class, () -> res.setHeader("X\r\nY", "v"));
        assertThrows(IllegalArgumentException.class, () -> res.setContentType("text/html\r\nSet-Cookie: a=b"));
        assertThrows(IllegalArgumentException.class, () -> res.setCharacterEncoding("utf-8\r\nX: y"));
        assertFalse(res.containsHeader("X"));
        // Latin-1 stays legal.
        res.setHeader("X", "café");
        assertEquals("café", res.getHeader("X"));
        assertNoInjectedLine(res);
    }

    @Test
    void cookiesWithCrLfOrNonLatin1AreRejected() {
        var res = new HttpServletResponseImpl();
        var path = new jakarta.servlet.http.Cookie("a", "b");
        path.setPath("/x\r\nSet-Cookie: c=d");
        assertThrows(IllegalArgumentException.class, () -> res.addCookie(path));
        var domain = new jakarta.servlet.http.Cookie("a", "b");
        domain.setDomain("xčĊ.org");
        assertThrows(IllegalArgumentException.class, () -> res.addCookie(domain));
        var value = new jakarta.servlet.http.Cookie("a", "b\nc");
        assertThrows(IllegalArgumentException.class, () -> res.addCookie(value));
        assertTrue(res.cookies().isEmpty());
    }
}
