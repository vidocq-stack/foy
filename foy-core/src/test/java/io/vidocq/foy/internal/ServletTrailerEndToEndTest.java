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
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Servlet 6.1 trailer fields ({@code getTrailerFields()} /
 * {@code isTrailerFieldsReady()}) over HTTP/1.1 chunked, end to end through
 * the chappe bridge. Mirrors the TCK
 * {@code HttpServletRequest40Tests.TrailerTest} servlet and request
 * (foy BUG-20260611-01: the TCK client hangs forever on its failure path, so
 * these assertions passing is what keeps the suite alive).
 */
class ServletTrailerEndToEndTest {

    private Server server;
    private int port;

    private void startWith(io.vidocq.chappe.api.Handler handler) {
        var r = TestServerLauncher.start(handler);
        this.server = r.server;
        this.port = r.port;
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** Replica of the TCK TrailerTestServlet: drain body, then report trailers. */
    private static HttpServlet trailerServlet() {
        return new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                var in = req.getInputStream();
                var buf = new java.io.ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1) buf.write(b);
                var out = resp.getWriter();
                out.write("isTrailerFieldsReady: " + req.isTrailerFieldsReady() + "\n");
                out.write("Chunk Data: " + buf.toString(StandardCharsets.ISO_8859_1) + "\n");
                out.write("Trailer: " + req.getTrailerFields() + "\n");
            }
        };
    }

    private void startTrailerServlet() {
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/TrailerTestServlet"), trailerServlet(), "T")));
        startWith(new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/"));
    }

    /** Byte-for-byte the TCK TrailerTest request. */
    @Test
    void tckTrailerRequest_trailersExposedLowercase() throws IOException {
        startTrailerServlet();
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();

            write(out, "POST /TrailerTestServlet HTTP/1.1\r\n");
            write(out, "Host: 127.0.0.1:" + port + "\r\n");
            write(out, "Connection: keep-alive\r\n");
            write(out, "Content-Type: text/plain\r\n");
            write(out, "Transfer-Encoding: chunked\r\n");
            write(out, "Trailer: myTrailer, myTrailer2\r\n");
            write(out, "\r\n");
            write(out, "3\r\nABC\r\n0\r\nmyTrailer:foo\r\nmyTrailer2:bar\r\n\r\n");
            out.flush();

            var response = readOneResponse(socket.getInputStream());
            assertTrue(response.contains("200"), "Should be 200 OK: " + response);
            assertTrue(response.contains("isTrailerFieldsReady: true"),
                    "Trailers must be ready after full body read: " + response);
            assertTrue(response.contains("Chunk Data: ABC"), "Body should be decoded: " + response);
            // Servlet 6.1: getTrailerFields() keys are lowercase
            assertTrue(response.contains("mytrailer=foo"), "Trailer 1 (lowercase key): " + response);
            assertTrue(response.contains("mytrailer2=bar"), "Trailer 2 (lowercase key): " + response);
        }
    }

    /** Non-chunked request: ready immediately, empty map. */
    @Test
    void nonChunkedRequest_readyTrueAndEmpty() throws IOException {
        startTrailerServlet();
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            write(out, "POST /TrailerTestServlet HTTP/1.1\r\nHost: h\r\n"
                    + "Content-Length: 3\r\nConnection: close\r\n\r\nXYZ");
            out.flush();

            var response = readAll(socket.getInputStream());
            assertTrue(response.contains("isTrailerFieldsReady: true"), response);
            assertTrue(response.contains("Trailer: {}"), "Empty trailer map expected: " + response);
        }
    }

    /** Chunked body not read yet: isTrailerFieldsReady() must be false. */
    @Test
    void chunkedBodyNotRead_notReadyYet() throws IOException {
        HttpServlet peeking = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                // Do NOT read the body first.
                resp.getWriter().write("readyBeforeRead: " + req.isTrailerFieldsReady());
            }
        };
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/peek"), peeking, "P")));
        startWith(new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/"));

        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            write(out, "POST /peek HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
            write(out, "3\r\nABC\r\n0\r\nmyTrailer:foo\r\n\r\n");
            out.flush();

            var response = readAll(socket.getInputStream());
            assertTrue(response.contains("readyBeforeRead: false"),
                    "Chunked body unread -> trailers not ready: " + response);
        }
    }

    private static void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
    }

    /** Reads exactly one Content-Length-framed response (keep-alive safe). */
    private static String readOneResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            int headerEnd = sb.indexOf("\r\n\r\n");
            if (headerEnd >= 0) {
                String headers = sb.substring(0, headerEnd + 4);
                var clIdx = headers.toLowerCase().indexOf("content-length: ");
                int contentLength = 0;
                if (clIdx >= 0) {
                    var clEnd = headers.indexOf("\r\n", clIdx);
                    contentLength = Integer.parseInt(headers.substring(clIdx + 16, clEnd).trim());
                }
                if (sb.length() - (headerEnd + 4) >= contentLength) return sb.toString();
            }
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
}
