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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Chappe bounds Content-Length bodies only; Foy bounds the others (maxChunkedRequestBodySize). */
class ChunkedBodyLimitEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    private void start(long cap) {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String outcome;
                try {
                    outcome = Integer.toString(req.getInputStream().readAllBytes().length);
                } catch (IOException e) {
                    outcome = e.getClass().getSimpleName();
                }
                resp.getWriter().write(outcome);
            }
        };
        var ctx = new VidocqServletContext("/");
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(cap));
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/body"), s, "B"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(ctx), null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;
    }

    private String post(HttpRequest.BodyPublisher body) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body"))
                        .timeout(Duration.ofSeconds(5)).POST(body).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void chunkedBodyAboveTheCapFailsTheRead() throws Exception {
        start(1024);
        assertEquals("RequestBodyTooLargeException",
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[4096]))));
    }

    @Test
    void chunkedBodyWithinTheCapIsRead() throws Exception {
        start(1024);
        assertEquals("1000",
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[1000]))));
    }

    @Test
    void anEndlessChunkedBodyIsCutOffAfterTheAnswer() throws Exception {
        start(1024);
        var written = new AtomicLong();
        var writerDone = new AtomicBoolean();
        Thread writer = null;
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write("POST /body HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] chunk = chunk();
            writer = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        out.write(chunk);
                        out.flush();
                        written.addAndGet(chunk.length);
                    }
                } catch (IOException cutOff) {
                    // a clean close or a reset both mean the server cut the upload off
                } finally {
                    writerDone.set(true);
                }
            });

            var response = new ByteArrayOutputStream();
            try {
                socket.getInputStream().transferTo(response);
            } catch (SocketException reset) {
                // a reset instead of a clean EOF also counts as cut off
            }
            String text = response.toString(StandardCharsets.ISO_8859_1);
            assertTrue(text.startsWith("HTTP/1.1 200"), text);
            assertTrue(text.contains("RequestBodyTooLargeException"), text);

            assertTrue(writer.join(Duration.ofSeconds(15)), "the writer must stop once the server closes");
            assertTrue(writerDone.get());
            // chappe drains 1 MiB at most (maxDrainSize), but the client also fills the kernel's socket
            // buffers before its writes fail: on Linux, autotuning grows them up to 6 MiB (receive) and
            // 4 MiB (send) by default, so about 11 MiB can be written against a correct server
            // (BUG-20261021-02). The writer stopping within 15 s already rules out an unbounded drain;
            // this bound only catches a drain far beyond the limit.
            assertTrue(written.get() < 64L * 1024 * 1024, "wrote " + written.get() + " bytes");
        } finally {
            socket.close();
            if (writer != null) writer.interrupt();
        }
    }

    private static byte[] chunk() {
        var head = "1000\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] chunk = new byte[head.length + 4096 + 2];
        System.arraycopy(head, 0, chunk, 0, head.length);
        chunk[chunk.length - 2] = '\r';
        chunk[chunk.length - 1] = '\n';
        return chunk;
    }

    @Test
    void aSizedBodyIsLeftToChappesLimit() throws Exception {
        start(1024);
        assertEquals("4096", post(HttpRequest.BodyPublishers.ofByteArray(new byte[4096])));
    }
}
