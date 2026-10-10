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
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming responses over a real chappe server, observed with a raw socket so the arrival time
 * of every byte and the exact framing (Content-Length, chunked, connection close) are visible.
 */
class ServletStreamingEndToEndTest {

    private Server server;
    private int port;
    private final ListenerRegistry listeners = new ListenerRegistry();

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @Test
    void flushBufferSendsTheFirstChunkWhileTheServletRuns() throws Exception {
        start("/flush", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                PrintWriter w = resp.getWriter();
                w.println("first line");
                resp.flushBuffer();
                try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                w.println("second line");
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /flush HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals(200, head.status());
            InputStream body = c.body(head);
            assertEquals("first line", readLine(body));
            long first = System.nanoTime();
            assertEquals("second line", readLine(body));
            long deltaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first);
            assertTrue(deltaMs >= 1000, "the first line must arrive before the servlet resumes; delta=" + deltaMs);
        }
    }

    @Test
    void bufferOverflowCommits() throws Exception {
        var committedAfterOverflow = new AtomicBoolean();
        start("/overflow", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setBufferSize(16);
                assertEquals(16, resp.getBufferSize());
                ServletOutputStream out = resp.getOutputStream();
                out.write("0123456789ABCDEF0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
                committedAfterOverflow.set(resp.isCommitted());
                resp.setHeader("X-Late", "ignored");
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /overflow HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals(200, head.status());
            assertNull(head.header("X-Late"));
            assertEquals("0123456789ABCDEF0123456789abcdef",
                    new String(c.body(head).readAllBytes(), StandardCharsets.US_ASCII));
        }
        assertTrue(committedAfterOverflow.get());
    }

    @Test
    void setBufferSizeAfterWriteThrows() throws Exception {
        start("/late-buffer", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                ServletOutputStream out = resp.getOutputStream();
                out.write('x');
                try {
                    resp.setBufferSize(1024);
                    out.print("-no-ise");
                } catch (IllegalStateException e) {
                    out.print("-ise");
                }
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /late-buffer HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals("x-ise", new String(c.body(head).readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void smallResponseKeepsContentLength() throws Exception {
        start("/small", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /small HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals("5", head.header("Content-Length"));
            assertNull(head.header("Transfer-Encoding"));
            assertEquals("hello", new String(c.body(head).readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void declaredContentLengthIsStreamedWithoutChunking() throws Exception {
        var clientGotFirstPart = new CountDownLatch(1);
        var servletSawClient = new AtomicBoolean();
        start("/declared", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentLength(20);
                ServletOutputStream out = resp.getOutputStream();
                out.write("first-part".getBytes(StandardCharsets.US_ASCII));
                resp.flushBuffer();
                // The client can only see the first part if chappe flushed it with a known length.
                try { servletSawClient.set(clientGotFirstPart.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write("secondpart".getBytes(StandardCharsets.US_ASCII));
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /declared HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals(List.of("20"), head.headers("Content-Length"));
            assertNull(head.header("Transfer-Encoding"));
            InputStream body = c.body(head);
            assertEquals("first-part", new String(body.readNBytes(10), StandardCharsets.US_ASCII));
            clientGotFirstPart.countDown();
            assertEquals("secondpart", new String(body.readAllBytes(), StandardCharsets.US_ASCII));
        }
        assertTrue(servletSawClient.get(), "the first part must reach the client before the servlet ends");
    }

    @Test
    void applicationTransferEncodingIsIgnored() throws Exception {
        start("/te", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setHeader("Transfer-Encoding", "chunked");
                resp.getOutputStream().write("abc".getBytes(StandardCharsets.US_ASCII));
                if (req.getParameter("flush") != null) {
                    resp.flushBuffer();
                    resp.getOutputStream().write("def".getBytes(StandardCharsets.US_ASCII));
                }
            }
        });
        try (var c = new RawClient(port)) {
            // Buffered: the container frames with Content-Length.
            c.send("GET /te HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertNull(head.header("Transfer-Encoding"));
            assertEquals("3", head.header("Content-Length"));
            assertEquals("abc", new String(c.body(head).readAllBytes(), StandardCharsets.US_ASCII));
            // Streamed: the container chunks the body itself, exactly once.
            c.send("GET /te?flush=1 HTTP/1.1\r\nHost: localhost\r\n\r\n");
            head = c.readHead();
            assertEquals(List.of("chunked"), head.headers("Transfer-Encoding"));
            assertEquals("abcdef", new String(c.body(head).readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void clientDisconnectFailsTheNextWrite() throws Exception {
        var writeFailure = new CompletableFuture<IOException>();
        var destroyed = new CountDownLatch(1);
        listeners.register(new ServletRequestListener() {
            @Override public void requestDestroyed(ServletRequestEvent sre) { destroyed.countDown(); }
        });
        start("/endless", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                try {
                    ServletOutputStream out = resp.getOutputStream();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (System.nanoTime() < deadline) {
                        out.write("tick\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        Thread.sleep(50);
                    }
                    writeFailure.completeExceptionally(new AssertionError("no write failed within 10 s"));
                } catch (IOException e) {
                    writeFailure.complete(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /endless HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals("tick", readLine(c.body(head)));
        }
        assertNotNull(writeFailure.get(5, TimeUnit.SECONDS));
        assertTrue(destroyed.await(5, TimeUnit.SECONDS), "requestDestroyed must fire after the failed write");
    }

    @Test
    void exceptionAfterCommitAbortsTheConnection() throws Exception {
        start("/boom", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getOutputStream().write("partial".getBytes(StandardCharsets.US_ASCII));
                resp.flushBuffer();
                throw new IllegalStateException("failure after commit (expected by the test)");
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /boom HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals(200, head.status());
            assertEquals("chunked", head.header("Transfer-Encoding"));
            // Everything after the head, raw: the partial chunk, then the connection ends without the
            // last chunk and without a second response.
            byte[] rest = c.readRawToEof();
            String raw = new String(rest, StandardCharsets.US_ASCII);
            assertTrue(raw.contains("partial"), raw);
            assertFalse(raw.contains("HTTP/1.1"), "no second status line: " + raw);
            assertFalse(raw.endsWith("0\r\n\r\n"), "the body must not end normally: " + raw);
        }
    }

    @Test
    void asyncWriteAfterCommitStreams() throws Exception {
        var clientGotFirst = new CountDownLatch(1);
        var asyncSawClient = new AtomicBoolean();
        start("/async", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.start(() -> {
                    try {
                        var r = (HttpServletResponse) ac.getResponse();
                        r.setContentType("text/plain");
                        PrintWriter w = r.getWriter();
                        w.println("one");
                        r.flushBuffer();
                        asyncSawClient.set(clientGotFirst.await(5, TimeUnit.SECONDS));
                        w.println("two");
                        r.flushBuffer();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        ac.complete();
                    }
                });
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals(200, head.status());
            InputStream body = c.body(head);
            assertEquals("one", readLine(body));
            clientGotFirst.countDown();
            assertEquals("two", readLine(body));
            assertEquals(-1, body.read());
        }
        assertTrue(asyncSawClient.get());
    }

    @Test
    void headRequestWithALargeStreamedBodyDoesNotBlock() throws Exception {
        start("/head", new HttpServlet() {
            @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                // A servlet writing its body for HEAD as well (no HttpServlet.doHead wrapper).
                ServletOutputStream out = resp.getOutputStream();
                byte[] block = new byte[4096];
                for (int i = 0; i < 64; i++) out.write(block);
            }
        });
        try (var c = new RawClient(port)) {
            c.send("HEAD /head HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertEquals(200, head.status());
            // The connection stays usable: no body bytes were sent for the HEAD.
            c.send("GET /head HTTP/1.1\r\nHost: localhost\r\n\r\n");
            head = c.readHead();
            assertEquals(200, head.status());
            assertEquals(64 * 4096, c.body(head).readAllBytes().length);
        }
    }

    @Test
    void sessionCookieIsSentWithACommittedResponse() throws Exception {
        start("/session", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                req.getSession(true);
                resp.getOutputStream().write("s".getBytes(StandardCharsets.US_ASCII));
                resp.flushBuffer();
            }
        });
        try (var c = new RawClient(port)) {
            c.send("GET /session HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var head = c.readHead();
            assertNotNull(head.header("Set-Cookie"));
            assertTrue(head.header("Set-Cookie").startsWith("JSESSIONID="), head.header("Set-Cookie"));
            assertEquals("s", new String(c.body(head).readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void aFailureWhileCommittingSettlesTheHead() throws Exception {
        var flushFailure = new AtomicReference<Throwable>();
        var flushFailed = new CountDownLatch(1);
        // The session cookie is serialised at the commit; a domain chappe cannot write fails it.
        start("/bad-commit", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                req.getSession(true);
                resp.getOutputStream().write("x".getBytes(StandardCharsets.US_ASCII));
                try {
                    resp.flushBuffer();
                } catch (IOException e) {
                    flushFailure.set(e);
                    flushFailed.countDown();
                    throw e;
                }
            }
        }, ctx -> ctx.sessionCookieConfigInternal().setDomain("badĀdomain"));
        try (var c = new RawClient(port)) {
            c.send("GET /bad-commit HTTP/1.1\r\nHost: localhost\r\n\r\n");
            // chappe must answer (its plain 500) rather than park on a head nobody settles.
            var head = c.readHead();
            assertEquals(500, head.status());
        }
        // The 500 can reach the client before the servlet thread's catch has run.
        assertTrue(flushFailed.await(5, TimeUnit.SECONDS), "the servlet's flush never failed");
        assertInstanceOf(IOException.class, flushFailure.get(), "the commit failure reaches the servlet's flush");
    }

    // ---- helpers ----

    private void start(String pattern, HttpServlet servlet) {
        start(pattern, servlet, ctx -> {});
    }

    private void start(String pattern, HttpServlet servlet, Consumer<VidocqServletContext> setup) {
        var ctx = new VidocqServletContext("/");
        setup.accept(ctx);
        ctx.setListenerRegistry(listeners);
        var sessionManager = new SessionManager(new InMemorySessionStore(), ctx, 1800);
        sessionManager.setListenerRegistry(listeners);
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of(pattern), servlet, "S")));
        var bridge = new ChappeServletBridge(dispatcher, new FilterRegistry(List.of()), ctx, sessionManager, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') line.write(b);
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.US_ASCII);
    }

    record Head(int status, List<String[]> fields) {
        String header(String name) {
            for (String[] f : fields) if (f[0].equalsIgnoreCase(name)) return f[1];
            return null;
        }
        List<String> headers(String name) {
            var out = new ArrayList<String>();
            for (String[] f : fields) if (f[0].equalsIgnoreCase(name)) out.add(f[1]);
            return out;
        }
    }

    /** A minimal HTTP/1.1 client over a socket: reads heads and decodes Content-Length or chunked bodies. */
    static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        RawClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void send(String request) throws IOException {
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        Head readHead() throws IOException {
            String status = readLine(in);
            assertNotNull(status, "connection closed before the status line");
            int code = Integer.parseInt(status.split(" ")[1]);
            var fields = new ArrayList<String[]>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                fields.add(new String[] {line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
            }
            return new Head(code, fields);
        }

        /** The decoded body of the response whose head was just read. */
        InputStream body(Head head) {
            String te = head.header("Transfer-Encoding");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) return new Chunked(in);
            String cl = head.header("Content-Length");
            if (cl != null) return new Bounded(in, Long.parseLong(cl));
            return in;
        }

        byte[] readRawToEof() throws IOException {
            var all = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            try {
                int n;
                while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
            } catch (SocketException reset) {
                // A reset is an acceptable way for the connection to end.
            }
            return all.toByteArray();
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long remaining;
        Bounded(InputStream in, long length) { this.in = in; this.remaining = length; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }

    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean eof;
        Chunked(InputStream in) { this.in = in; }
        @Override public int read() throws IOException {
            if (!ensure()) return -1;
            int b = in.read();
            if (b < 0) throw new IOException("truncated chunk");
            if (--remaining == 0) readLine(in); // CRLF after the chunk data
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!ensure()) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new IOException("truncated chunk");
            remaining -= n;
            if (remaining == 0) readLine(in);
            return n;
        }
        private boolean ensure() throws IOException {
            if (eof) return false;
            if (remaining > 0) return true;
            String size = readLine(in);
            if (size == null) throw new IOException("connection closed inside a chunked body");
            remaining = Long.parseLong(size.split(";")[0].trim(), 16);
            if (remaining == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) { /* trailers ignored */ }
                eof = true;
                return false;
            }
            return true;
        }
    }
}
