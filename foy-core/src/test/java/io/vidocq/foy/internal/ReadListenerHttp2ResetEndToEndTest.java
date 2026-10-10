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
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Over HTTP/2, a request body cut by a stream reset (RST_STREAM) is a read error: the ReadListener
 * hears {@code onError}, never {@code onAllDataRead} (chappe CHAPPE-015). Raw HTTP/2 client with
 * prior knowledge, as in chappe's own tests.
 */
@Timeout(30)
class ReadListenerHttp2ResetEndToEndTest {

    private static final byte[] H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_ACK = 0x1;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void aStreamResetMidBodyGoesToOnError() throws Exception {
        var outcome = new CompletableFuture<String>();
        int port = start(outcome);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, requestHeaders());
            writeFrame(out, TYPE_DATA, 0, 1, "abc".getBytes(StandardCharsets.US_ASCII));
            writeFrame(out, TYPE_RST_STREAM, 0, 1, new byte[] {0, 0, 0, 0x8}); // CANCEL
            out.flush();
            assertEquals("onError", outcome.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void aBodyEndedByEndStreamGoesToOnAllDataRead() throws Exception {
        var outcome = new CompletableFuture<String>();
        int port = start(outcome);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, requestHeaders());
            writeFrame(out, TYPE_DATA, FLAG_END_STREAM, 1, "abc".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals("onAllDataRead:abc", outcome.get(10, TimeUnit.SECONDS));
        }
    }

    // ---- helpers ----

    /** A servlet reading the body through a ReadListener; the outcome is how the body ended. */
    private int start(CompletableFuture<String> outcome) {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                var async = req.startAsync();
                async.setTimeout(0);
                ServletInputStream body = req.getInputStream();
                var read = new StringBuilder();
                body.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        byte[] buf = new byte[64];
                        int n;
                        while (body.isReady() && (n = body.read(buf)) != -1) {
                            read.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                        }
                    }
                    @Override public void onAllDataRead() {
                        outcome.complete("onAllDataRead:" + read);
                        async.complete();
                    }
                    @Override public void onError(Throwable t) {
                        outcome.complete("onError");
                        async.complete();
                    }
                });
            }
        };
        var mappings = List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/nio"), servlet, "S"));
        var bridge = new ChappeServletBridge(new ServletDispatcher(mappings),
                new FilterRegistry(List.of()), new VidocqServletContext("/"), null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        return r.port;
    }

    private static void sendPrefaceAndSettings(DataInputStream in, DataOutputStream out) throws IOException {
        out.write(H2_PREFACE);
        writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
        out.flush();
        for (int i = 0; i < 2; i++) skipFrame(in); // server SETTINGS + ACK of ours
        writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        out.flush();
    }

    private static void writeFrame(DataOutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    private static void skipFrame(DataInputStream in) throws IOException {
        int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        in.readUnsignedByte(); // type
        in.readUnsignedByte(); // flags
        in.readInt(); // stream id
        in.readNBytes(len);
    }

    /** HPACK literal header fields without indexing, no Huffman (RFC 7541 section 6.2.2). */
    private static byte[] requestHeaders() {
        var out = new ByteArrayOutputStream();
        literal(out, ":method", "POST");
        literal(out, ":scheme", "http");
        literal(out, ":authority", "127.0.0.1");
        literal(out, ":path", "/nio");
        return out.toByteArray();
    }

    private static void literal(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        out.write(n.length);
        out.write(n, 0, n.length);
        byte[] v = value.getBytes(StandardCharsets.ISO_8859_1);
        out.write(v.length);
        out.write(v, 0, v.length);
    }
}
