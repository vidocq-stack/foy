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
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

/** The parts of a request end with it (section 3.2): nothing is parsed or stored after releaseParts(). */
class MultipartLifecycleTest {

    private static final String BOUNDARY = "B";

    record FakeRequest(Headers headers, Body body, Headers trailers) implements Request {
        @Override public HttpMethod method() { return HttpMethod.POST; }
        @Override public URI uri() { return URI.create("http://localhost/u"); }
        @Override public String path() { return "/u"; }
        @Override public String query() { return null; }
        @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
    }

    @TempDir Path temp;

    private HttpServletRequestImpl request(String body, boolean chunked, Headers trailers) {
        var ctx = new VidocqServletContext("/");
        ctx.setAttribute(ServletContext.TEMPDIR, temp.toFile());
        ctx.addServlet("U", new HttpServlet() {}).setMultipartConfig(new MultipartConfigElement(""));
        byte[] bytes = body.getBytes(US_ASCII);
        var headers = Headers.builder().add("Host", "localhost")
                .add("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        headers = chunked ? headers.add("Transfer-Encoding", "chunked")
                : headers.add("Content-Length", Integer.toString(bytes.length));
        var req = new HttpServletRequestImpl(new FakeRequest(headers.build(), Body.of(new ByteArrayInputStream(bytes)),
                trailers), ctx, "/", "/u", null);
        req.enterServlet("U");
        return req;
    }

    private static String body() {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\n\r\ndata\r\n--"
                + BOUNDARY + "--\r\nepilogue";
    }

    private long tempFiles() throws IOException {
        try (var files = Files.list(temp)) { return files.count(); }
    }

    @Test
    void partsAskedForAfterTheRequestEndedAreRefusedAndStoreNothing() throws Exception {
        var req = request(body(), false, Headers.empty());
        req.releaseParts();
        assertThrows(IllegalStateException.class, req::getParts);
        assertThrows(IllegalStateException.class, () -> req.getPart("f"));
        assertEquals(0, tempFiles());
    }

    @Test
    void partsParsedDuringTheRequestAreGoneAfterIt() throws Exception {
        var req = request(body(), false, Headers.empty());
        assertEquals(1, req.getParts().size());
        assertEquals(1, tempFiles());
        req.releaseParts();
        assertEquals(0, tempFiles());
        assertThrows(IllegalStateException.class, req::getParts);
    }

    @Test
    void theTrackedBodyIsReadToItsEndAfterAContainerParse() throws Exception {
        var req = request(body(), true, Headers.of("X-Sum", "1"));
        assertFalse(req.isTrailerFieldsReady(), "nothing read yet");
        assertEquals(1, req.getParts().size());
        assertTrue(req.isTrailerFieldsReady(), "the epilogue was drained: the trailers are ready");
        assertEquals("1", req.getTrailerFields().get("x-sum"));
        req.releaseParts();
    }

    @Test
    void theTrailersStayReadyWhenTheApplicationAsksForTheBodyAfterAContainerParse() throws Exception {
        var stream = request(body(), true, Headers.of("X-Sum", "1"));
        assertEquals(1, stream.getParts().size());
        stream.getInputStream();
        assertTrue(stream.isTrailerFieldsReady(), "the container read the body to its end");
        stream.releaseParts();

        var reader = request(body(), true, Headers.of("X-Sum", "1"));
        assertEquals(1, reader.getParts().size());
        reader.getReader();
        assertTrue(reader.isTrailerFieldsReady(), "the container read the body to its end");
        reader.releaseParts();
    }
}
