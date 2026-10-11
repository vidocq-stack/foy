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
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.http.MultipartLimitException;
import io.vidocq.foy.internal.http.RequestLimits;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Section 3.2: a part without a file name is also a request parameter, after the query string. */
class MultipartParametersTest {

    private static final String B = "PARAMS-BOUNDARY";
    private static final MultipartConfigElement CONFIG = new MultipartConfigElement("");

    @TempDir Path temp;
    private final VidocqServletContext ctx = new VidocqServletContext("/");

    @BeforeEach
    void setUp() { ctx.setAttribute(ServletContext.TEMPDIR, temp.toFile()); }

    private HttpServletRequestImpl request(HttpMethod method, String query, MultipartConfigElement config, String body) {
        var req = unscoped(method, query, config, body);
        req.enterServlet("U");
        return req;
    }

    /** A request no servlet executes yet: a request listener, a FORM login check. */
    private HttpServletRequestImpl unscoped(HttpMethod method, String query, MultipartConfigElement config, String body) {
        if (config != null && ctx.getServletRegistration("U") == null) {
            ctx.addServlet("U", new HttpServlet() {}).setMultipartConfig(config);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = Headers.builder().add("Host", "localhost")
                .add("Content-Type", "multipart/form-data; boundary=" + B)
                .add("Content-Length", Integer.toString(bytes.length)).build();
        return new HttpServletRequestImpl(new RequestBodyStateTest.FakeRequest(method, query, headers,
                Body.of(new ByteArrayInputStream(bytes))), ctx, "/", "/f", null);
    }

    private static String field(String name, String value) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String typedField(String name, String contentType, String value) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\nContent-Type: "
                + contentType + "\r\n\r\n" + value + "\r\n";
    }

    private static String file(String name, String fileName, String value) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName
                + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String end() { return "--" + B + "--\r\n"; }

    @Test
    void fieldsBecomeParametersAfterTheQueryString() {
        var req = request(HttpMethod.POST, "a=q", CONFIG, field("a", "f") + field("b", "g") + file("c", "c.txt", "file") + end());
        assertArrayEquals(new String[] {"q", "f"}, req.getParameterValues("a"));
        assertEquals("g", req.getParameter("b"));
        assertNull(req.getParameter("c"), "a file part is not a parameter");
        assertEquals(List.of("a", "b"), Collections.list(req.getParameterNames()));
        req.releaseParts();
    }

    @Test
    void theFieldCharsetIsThePartsThenTheRequestsThenTheCharsetFieldThenIso88591() throws Exception {
        String e = "é"; // sent as UTF-8 bytes
        var none = request(HttpMethod.POST, null, CONFIG,
                typedField("declared", "text/plain; charset=UTF-8", e) + field("plain", e) + end());
        assertEquals(e, none.getParameter("declared"), "the part's own charset");
        assertEquals("Ã©", none.getParameter("plain"), "ISO-8859-1 by default (section 3.12)");

        var charsetField = request(HttpMethod.POST, null, CONFIG, field("_charset_", "UTF-8") + field("plain", e) + end());
        assertEquals(e, charsetField.getParameter("plain"), "_charset_ (RFC 7578 section 4.6)");

        var encoding = request(HttpMethod.POST, null, CONFIG, field("_charset_", "ISO-8859-1") + field("plain", e) + end());
        encoding.setCharacterEncoding("UTF-8");
        assertEquals(e, encoding.getParameter("plain"), "the request encoding wins over _charset_");
    }

    @Test
    void withoutAMultipartConfigTheBodyGivesNoParameter() {
        var req = request(HttpMethod.POST, "q=1", null, field("a", "1") + end());
        assertEquals("1", req.getParameter("q"));
        assertNull(req.getParameter("a"));
        assertThrows(IllegalStateException.class, req::getParts);
    }

    @Test
    void getParameterAndGetPartsShareOneParse() throws Exception {
        var req = request(HttpMethod.POST, null, CONFIG, field("a", "1") + file("f", "f.txt", "x") + end());
        assertEquals("1", req.getParameter("a"));
        assertEquals(2, req.getParts().size());
        assertEquals(-1, req.getInputStream().read(), "the body belongs to the container");
        req.releaseParts();
    }

    @Test
    void aPutIsParsedForParametersOnlyOnceGetPartsRan() throws Exception {
        var req = request(HttpMethod.PUT, null, CONFIG, field("a", "1") + end());
        assertNull(req.getParameter("a"), "section 3.1.1: only a POST body feeds the parameters by itself");
        assertEquals(1, req.getParts().size());
        assertEquals("1", req.getParameter("a"), "the parsed fields are merged");
    }

    @Test
    void parametersAfterGetInputStreamIgnoreTheBody() throws Exception {
        var req = request(HttpMethod.POST, "q=1", CONFIG, field("a", "1") + end());
        req.getInputStream();
        assertNull(req.getParameter("a"));
        assertEquals("1", req.getParameter("q"));
    }

    @Test
    void aFailedParseThrowsOnceFromGetParameterAndAlwaysFromGetParts() {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxMultipartParts(1));
        var req = request(HttpMethod.POST, "q=1", CONFIG, field("a", "1") + field("b", "2") + end());
        var first = assertThrows(MultipartLimitException.class, () -> req.getParameter("a"));
        assertEquals("1", req.getParameter("q"));
        assertNull(req.getParameter("a"));
        assertSame(first, assertThrows(MultipartLimitException.class, req::getParts));
    }

    @Test
    void parsingMultipartParametersLocksTheEncoding() throws Exception {
        var req = request(HttpMethod.POST, null, CONFIG, field("a", "1") + end());
        req.getParameter("a");
        req.setCharacterEncoding("UTF-8");
        assertNull(req.getCharacterEncoding());
    }

    @Test
    void parametersReadBeforeAnyServletRunsDoNotHideTheFieldsFromTheServlet() {
        var req = unscoped(HttpMethod.POST, "q=1", CONFIG, field("a", "1") + end());
        assertEquals("1", req.getParameter("q"), "a request listener sees the query string");
        assertNull(req.getParameter("a"), "no servlet, no multipart configuration: the body is left alone");
        req.enterServlet("U");
        assertEquals("1", req.getParameter("a"), "the servlet sees the fields");
        assertEquals(List.of("q", "a"), Collections.list(req.getParameterNames()));
        req.releaseParts();
    }
}
