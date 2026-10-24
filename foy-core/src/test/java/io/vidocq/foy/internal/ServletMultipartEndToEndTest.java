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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.http.RequestLimits;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 section 3.2 over real chappe: contract, storage lifecycle, keep-alive. */
class ServletMultipartEndToEndTest {

    private static final String BOUNDARY = "FOY-TEST-BOUNDARY";

    /**
     * Bounds every exchange so that a hang fails the test. It is not a latency check: a request here
     * creates, writes and deletes temporary files, and a loaded runner's disk can take seconds per
     * file operation (BUG-20261024-01); the server is then slow, not stuck.
     */
    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(60);

    @TempDir Path temp;
    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    /** What a test servlet does with the request; its result, or its exception's simple name, is the body. */
    interface Action { String run(HttpServletRequest req) throws Exception; }

    private static HttpServlet servlet(Action action) {
        return new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String out;
                try {
                    out = action.run(req);
                } catch (Exception e) {
                    out = e.getClass().getSimpleName();
                }
                resp.setContentType("text/plain");
                resp.getWriter().write(out);
            }

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("second");
            }
        };
    }

    /** Serves {@code servlet} at /upload as "U", with {@code config} (none when null), TEMPDIR = {@code temp}. */
    private void start(HttpServlet servlet, MultipartConfigElement config) {
        start(servlet, config, RequestLimits.DEFAULTS);
    }

    private void start(HttpServlet servlet, MultipartConfigElement config, RequestLimits limits) {
        var ctx = new VidocqServletContext("/");
        ctx.setAttribute(ServletContext.TEMPDIR, temp.toFile());
        ctx.setRequestLimits(limits);
        if (config != null) ctx.addServlet("U", servlet).setMultipartConfig(config);
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/upload"), servlet, "U"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(ctx), null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;
    }

    private static String field(String name, String value) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String file(String name, String fileName, String type, String content) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + type + "\r\n\r\n" + content + "\r\n";
    }

    private static String end() { return "--" + BOUNDARY + "--\r\n"; }

    private HttpResponse<String> post(String contentType, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload"))
                        .header("Content-Type", contentType).timeout(EXCHANGE_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMultipart(String body) throws Exception {
        return post("multipart/form-data; boundary=" + BOUNDARY, body);
    }

    /** A multipart POST without Content-Length (HTTP/1.1 chunked). */
    private HttpResponse<String> postChunkedMultipart(String body) throws Exception {
        byte[] bytes = body.getBytes(UTF_8);
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload"))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .timeout(EXCHANGE_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long tempFiles() throws IOException {
        try (var files = Files.list(temp)) { return files.count(); }
    }

    @Test
    void uploadsFileAndFormField() throws Exception {
        start(servlet(req -> {
            Part field = req.getPart("desc");
            Part file = req.getPart("upload");
            return new String(field.getInputStream().readAllBytes(), UTF_8) + "|" + file.getSubmittedFileName()
                    + "(" + file.getContentType() + "):" + new String(file.getInputStream().readAllBytes(), UTF_8);
        }), new MultipartConfigElement(""));
        var resp = postMultipart(field("desc", "release notes")
                + file("upload", "changelog.txt", "text/markdown", "# M2g\r\nmultipart support") + end());
        assertEquals(200, resp.statusCode());
        assertEquals("release notes|changelog.txt(text/markdown):# M2g\r\nmultipart support", resp.body());
    }

    /** getParts() Javadoc: a request that is not multipart/form-data is a ServletException (it used to yield no part). */
    @Test
    void getPartsOnANonMultipartRequestThrowsServletException() throws Exception {
        start(servlet(req -> Integer.toString(req.getParts().size())), new MultipartConfigElement(""));
        assertEquals("ServletException", post("application/json", "{}").body());
        assertEquals("ServletException", post("multipart/form-datax; boundary=" + BOUNDARY, end()).body());
        assertEquals("ServletException", post("  text/plain; boundary=" + BOUNDARY, end()).body());
    }

    @Test
    void getPartWithoutAMultipartConfigThrowsIllegalStateException() throws Exception {
        start(servlet(req -> String.valueOf(req.getPart("a"))), null);
        assertEquals("IllegalStateException", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void getPartReturnsNullForAMissingName() throws Exception {
        start(servlet(req -> String.valueOf(req.getPart("missing"))), new MultipartConfigElement(""));
        assertEquals("null", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void getPartsAfterGetInputStreamThrowsIllegalStateException() throws Exception {
        start(servlet(req -> {
            req.getInputStream();
            return Integer.toString(req.getParts().size());
        }), new MultipartConfigElement(""));
        assertEquals("IllegalStateException", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void aFailedParseIsRethrownWithoutReadingTheBodyAgain() throws Exception {
        start(servlet(req -> {
            IllegalStateException first = null;
            IllegalStateException again = null;
            try { req.getParts(); } catch (IllegalStateException e) { first = e; }
            try { req.getPart("a"); } catch (IllegalStateException e) { again = e; }
            String type = first == null ? "nothing thrown" : first.getClass().getSimpleName();
            return (first != null && first.equals(again)) + ":" + type + ":" + req.getInputStream().read();
        }), new MultipartConfigElement("", 4, -1, 0));
        assertEquals("true:MultipartLimitException:-1", postMultipart(field("a", "too long") + end()).body());
    }

    @Test
    void partsSpillToTheContextTempDirAndAreDeletedAfterTheRequest() throws Exception {
        start(servlet(req -> req.getParts().size() + " parts, " + tempFiles() + " files"), new MultipartConfigElement(""));
        assertEquals("2 parts, 2 files",
                postMultipart(field("a", "1") + file("f", "x.txt", "text/plain", "data") + end()).body());
        assertEquals(0, tempFiles(), "deleted at the end of the request");
    }

    @Test
    void aRelativeWriteLandsInTheLocationAndOutlivesTheRequest() throws Exception {
        start(servlet(req -> {
            req.getPart("f").write("saved.txt");
            return "written";
        }), new MultipartConfigElement(""));
        assertEquals("written", postMultipart(file("f", "x.txt", "text/plain", "data") + end()).body());
        assertEquals("data", Files.readString(temp.resolve("saved.txt")), "the application's file stays");
        assertEquals(1, tempFiles(), "only the application's file is left");
    }

    @Test
    void theContainerBodyCapBoundsAMultipartBodyWithoutMaxRequestSize() throws Exception {
        start(servlet(req -> Integer.toString(req.getParts().size())), new MultipartConfigElement(""),
                RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(256));
        assertEquals("RequestBodyTooLargeException",
                postChunkedMultipart(field("a", "x".repeat(1024)) + end()).body());
        assertEquals(0, tempFiles(), "the partial part is deleted");
    }

    @Test
    void anUncaughtMultipartLimitAnswers413() throws Exception {
        HttpServlet throwing = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.getWriter().write(Integer.toString(req.getParts().size()));
            }
        };
        start(throwing, new MultipartConfigElement("", 4, -1, 0));
        assertEquals(413, postMultipart(field("a", "too long") + end()).statusCode());
    }

    @Test
    void anUncaughtBodyCapAnswers413() throws Exception {
        HttpServlet throwing = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.getWriter().write(Integer.toString(req.getParts().size()));
            }
        };
        start(throwing, new MultipartConfigElement(""), RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(256));
        assertEquals(413, postChunkedMultipart(field("a", "x".repeat(1024)) + end()).statusCode());
    }

    @Test
    void partsOfAnAsyncRequestLiveUntilItCompletes() throws Exception {
        var during = new AtomicLong(-1);
        HttpServlet async = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                req.getParts();
                AsyncContext ac = req.startAsync();
                ac.start(() -> {
                    try {
                        during.set(tempFiles());
                        ac.getResponse().getWriter().write(
                                new String(req.getPart("f").getInputStream().readAllBytes(), UTF_8));
                    } catch (Exception _) {
                        during.set(-2);
                    }
                    ac.complete();
                });
            }
        };
        start(async, new MultipartConfigElement(""));
        assertEquals("data", postMultipart(file("f", "x.txt", "text/plain", "data") + end()).body());
        assertEquals(1, during.get(), "the file exists while the async cycle runs");
        assertEquals(0, tempFiles(), "deleted once the async cycle completed");
    }

    @Test
    void aWrappedRequestUsesTheExecutingServletsConfiguration() throws Exception {
        start(servlet(req -> Integer.toString(new HttpServletRequestWrapper(req).getParts().size())),
                new MultipartConfigElement(""));
        assertEquals("1", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void theConnectionServesTheNextRequestAfterParts() throws Exception {
        start(servlet(req -> new String(req.getPart("a").getInputStream().readAllBytes(), UTF_8)),
                new MultipartConfigElement(""));
        byte[] body = (field("a", "first") + end() + "an epilogue the parser never reads").getBytes(UTF_8);
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) EXCHANGE_TIMEOUT.toMillis());
            var out = socket.getOutputStream();
            var in = new java.io.BufferedInputStream(socket.getInputStream());
            out.write(("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary="
                    + BOUNDARY + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(US_ASCII));
            out.write(body);
            out.flush();
            String first = readResponse(in);
            assertTrue(first.startsWith("HTTP/1.1 200") && first.endsWith("|first"), first);
            out.write("GET /upload HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            out.flush();
            String second = readResponse(in);
            assertTrue(second.startsWith("HTTP/1.1 200") && second.endsWith("|second"), second);
        }
    }

    /** A chunked multipart body with trailers: once the container parsed the parts, the trailers are ready. */
    @Test
    void trailersAreReadyAfterTheContainerParsedAChunkedMultipartBody() throws Exception {
        start(servlet(req -> req.getParts().size() + ":" + req.isTrailerFieldsReady() + ":"
                + req.getTrailerFields().get("x-checksum")), new MultipartConfigElement(""));
        byte[] body = (field("a", "1") + end()).getBytes(UTF_8);
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) EXCHANGE_TIMEOUT.toMillis());
            var out = socket.getOutputStream();
            var in = new java.io.BufferedInputStream(socket.getInputStream());
            out.write(("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary="
                    + BOUNDARY + "\r\nTransfer-Encoding: chunked\r\nTrailer: X-Checksum\r\n\r\n").getBytes(US_ASCII));
            out.write((Integer.toHexString(body.length) + "\r\n").getBytes(US_ASCII));
            out.write(body);
            out.write("\r\n0\r\nX-Checksum: abc\r\n\r\n".getBytes(US_ASCII));
            out.flush();
            String response = readResponse(in);
            assertTrue(response.startsWith("HTTP/1.1 200") && response.endsWith("|1:true:abc"), response);
        }
    }

    /** "status line|body" of one HTTP/1.1 response (Content-Length or chunked). */
    private static String readResponse(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        while (!head.toString(ISO_8859_1).endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) throw new EOFException("connection closed after: " + head.toString(ISO_8859_1));
            head.write(c);
        }
        String[] lines = head.toString(ISO_8859_1).split("\r\n", -1);
        int length = -1;
        boolean chunked = false;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
        }
        var body = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                int n = Integer.parseInt(readLine(in).split(";", -1)[0].trim(), 16);
                if (n == 0) {
                    while (!readLine(in).isEmpty()) { /* trailers */ }
                    break;
                }
                body.write(in.readNBytes(n));
                readLine(in);
            }
        } else if (length > 0) {
            body.write(in.readNBytes(length));
        }
        return lines[0] + "|" + body.toString(UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new StringBuilder();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) throw new EOFException();
            if (c != '\r') line.append((char) c);
        }
        return line.toString();
    }
}
