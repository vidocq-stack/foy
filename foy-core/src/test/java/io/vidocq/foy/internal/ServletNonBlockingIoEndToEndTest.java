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
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 section 3.7: non-blocking request input ({@link ReadListener}) over a real connection. */
class ServletNonBlockingIoEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    /** The TCK's ReadListenerTests.nioInputTest scenario: two chunks one second apart. */
    @Test
    void twoChunksYieldTwoCallbacks() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ServletOutputStream out = resp.getOutputStream();
                ServletInputStream in = req.getInputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        out.println("=onDataAvailable");
                        byte[] buf = new byte[1024];
                        int n;
                        while (in.isReady() && (n = in.read(buf)) != -1) {
                            out.println("=" + new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                    @Override public void onAllDataRead() throws IOException {
                        out.println("=onAllDataRead");
                        ac.complete();
                    }
                    @Override public void onError(Throwable t) {
                        ac.complete();
                    }
                });
            }
        });

        try (var client = new Client(port)) {
            client.send(head());
            client.send("5\r\nHello\r\n");
            Thread.sleep(1000); // part of the scenario: the second chunk comes later
            client.send("5\r\nWorld\r\n0\r\n\r\n");
            String body = client.readAll();
            assertTrue(body.startsWith("HTTP/1.1 200"), body);
            assertEquals(List.of("=onDataAvailable", "=Hello", "=onDataAvailable", "=World", "=onAllDataRead"),
                    tokens(body));
        }
    }

    /**
     * Callbacks run one at a time and never while the dispatch that set the listener is still in
     * progress: the dispatch waits until the body is buffered (so a callback is due) and only then
     * returns; the first callback must start after that.
     */
    @Test
    void callbacksNeverOverlap() throws Exception {
        var inside = new AtomicBoolean();
        var overlap = new AtomicBoolean();
        var callbacks = new AtomicInteger();
        var dataReadyDuringDispatch = new AtomicBoolean();
        var dispatchReturned = new AtomicBoolean();
        var callbackDuringDispatch = new AtomicBoolean();
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                enter(inside, overlap);
                try {
                    AsyncContext ac = req.startAsync();
                    ServletInputStream in = req.getInputStream();
                    in.setReadListener(new ReadListener() {
                        @Override public void onDataAvailable() throws IOException {
                            if (!dispatchReturned.get()) callbackDuringDispatch.set(true);
                            enter(inside, overlap);
                            try {
                                callbacks.incrementAndGet();
                                pause();
                                byte[] buf = new byte[1];
                                while (in.isReady() && in.read(buf) != -1) { /* one byte at a time */ }
                            } finally {
                                inside.set(false);
                            }
                        }
                        @Override public void onAllDataRead() throws IOException {
                            enter(inside, overlap);
                            try {
                                pause();
                                resp.getWriter().print("overlap=" + overlap.get() + " callbacks>0=" + (callbacks.get() > 0));
                            } finally {
                                inside.set(false);
                            }
                            ac.complete();
                        }
                        @Override public void onError(Throwable t) { ac.complete(); }
                    });
                    // Bounded wait until the pump buffered the body: a callback is now due.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!in.isReady() && System.nanoTime() < deadline) Thread.onSpinWait();
                    dataReadyDuringDispatch.set(in.isReady());
                    pause(); // the dispatch keeps running while the callback is due
                } finally {
                    inside.set(false);
                    dispatchReturned.set(true);
                }
            }
        });

        try (var client = new Client(port)) {
            client.send(head());
            client.send("3\r\nabc\r\n");
            client.send("3\r\ndef\r\n0\r\n\r\n");
            String body = client.readAll();
            assertTrue(body.contains("overlap=false callbacks>0=true"), body);
        }
        assertTrue(dataReadyDuringDispatch.get(), "the body was buffered while the dispatch ran");
        assertFalse(callbackDuringDispatch.get(), "a callback started before the dispatch returned");
        assertFalse(overlap.get());
    }

    /**
     * An async timeout with a listener waiting for a body the client never finishes: the 500 is
     * delivered at once; the silent client does not hold it back.
     */
    @Test
    void timeoutWithAnActiveListenerAnswersWhileTheClientIsSilent() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(300);
                ServletInputStream in = req.getInputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        byte[] buf = new byte[64];
                        while (in.isReady() && in.read(buf) != -1) { /* drain */ }
                    }
                    @Override public void onAllDataRead() { ac.complete(); }
                    @Override public void onError(Throwable t) {}
                });
            }
        });

        try (var client = new Client(port)) {
            client.send(head());
            client.send("5\r\nHello\r\n"); // then silence: the body never ends
            assertEquals("HTTP/1.1 500", client.statusLine().substring(0, 12));
        }
    }

    /** complete() before the body ends (an early rejection): the response goes out at once. */
    @Test
    void anEarlyCompleteAnswersWhileTheClientIsSilent() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ServletInputStream in = req.getInputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        byte[] buf = new byte[64];
                        while (in.isReady() && in.read(buf) != -1) { /* drain */ }
                        resp.setStatus(413);
                        ac.complete();
                    }
                    @Override public void onAllDataRead() {}
                    @Override public void onError(Throwable t) {}
                });
            }
        });

        try (var client = new Client(port)) {
            client.send(head());
            client.send("5\r\nHello\r\n"); // then silence
            assertEquals("HTTP/1.1 413", client.statusLine().substring(0, 12));
        }
    }

    /**
     * A client that neither reads the response nor sends the rest of its body: chappe's write
     * times out, and the connection is closed within a bound; the body pump (blocked on the silent
     * upload) does not pin the connection thread and the socket.
     */
    @Test
    void aWriteTimeoutWithAPumpBlockedOnASilentUploadClosesTheConnection() throws Exception {
        var writeFailed = new CountDownLatch(1);
        var servlet = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(0);
                ServletOutputStream out = resp.getOutputStream();
                ServletInputStream in = req.getInputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        byte[] buf = new byte[64];
                        while (in.isReady() && in.read(buf) != -1) { /* drain */ }
                        byte[] block = new byte[64 * 1024];
                        try {
                            for (int i = 0; i < 256; i++) out.write(block); // 16 MiB nobody reads
                        } catch (IOException e) {
                            writeFailed.countDown();
                            throw e;
                        }
                        ac.complete();
                    }
                    @Override public void onAllDataRead() {}
                    @Override public void onError(Throwable t) {}
                });
            }
        };
        var mappings = List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/nio"), servlet, "S"));
        var bridge = new ChappeServletBridge(new ServletDispatcher(mappings),
                new FilterRegistry(List.of()), new VidocqServletContext("/"), null, "/");
        server = Server.builder().host("127.0.0.1").port(0).writeTimeout(java.time.Duration.ofMillis(500))
                .handler(bridge).build();
        server.start();
        port = server.port();

        try (var socket = new Socket()) {
            socket.setReceiveBufferSize(4096);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port));
            socket.setSoTimeout(5_000);
            var out = socket.getOutputStream();
            out.write((head() + "5\r\nHello\r\n").getBytes(StandardCharsets.US_ASCII)); // then silence
            out.flush();
            assertTrue(writeFailed.await(10, TimeUnit.SECONDS), "chappe's write never timed out");
            // The connection must now be closed: reading reaches its end (or a reset) instead of
            // waiting for bytes that never come.
            var in = socket.getInputStream();
            byte[] buf = new byte[64 * 1024];
            try {
                while (in.read(buf) != -1) { /* what was sent before the timeout */ }
            } catch (SocketException reset) {
                // a reset closes the connection as well
            }
        }
    }

    /**
     * A committed response aborted by the async timeout while the pump waits on a silent upload:
     * chappe's write fails (not a socket failure: the body stream throws), and the connection must
     * still close within a bound instead of waiting for the client's next byte.
     */
    @Test
    void anAbortedCommittedResponseWithAPumpBlockedOnASilentUploadClosesTheConnection() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(300);
                resp.getOutputStream().print("partial");
                resp.flushBuffer(); // committed: the timeout aborts the body
                ServletInputStream in = req.getInputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        byte[] buf = new byte[64];
                        while (in.isReady() && in.read(buf) != -1) { /* drain */ }
                    }
                    @Override public void onAllDataRead() { ac.complete(); }
                    @Override public void onError(Throwable t) {}
                });
            }
        });

        try (var client = new Client(port)) {
            client.send(head());
            client.send("5\r\nHello\r\n"); // then silence: the body never ends
            // Head, "partial", then the end of the connection (EOF or reset) within the 5 s timeout.
            String all = client.readAll();
            assertTrue(all.startsWith("HTTP/1.1 200"), all);
            assertTrue(all.contains("partial"), all);
        }
    }

    @Test
    void readListenerInAsyncDispatchWithoutStartAsyncThrowsIse() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (req.getDispatcherType() == DispatcherType.REQUEST) {
                    req.startAsync().dispatch();
                    return;
                }
                try {
                    req.getInputStream().setReadListener(new ReadListener() {
                        @Override public void onDataAvailable() {}
                        @Override public void onAllDataRead() {}
                        @Override public void onError(Throwable t) {}
                    });
                    resp.getWriter().print("no exception");
                } catch (IllegalStateException e) {
                    resp.getWriter().print("IllegalStateException");
                }
            }
        });

        try (var client = new Client(port)) {
            client.send(head());
            client.send("5\r\nHello\r\n0\r\n\r\n");
            String body = client.readAll();
            assertTrue(body.contains("IllegalStateException"), body);
        }
    }

    /** A client gone mid-body: ReadListener.onError, then the async cycle fails (AsyncListener.onError). */
    @Test
    void aReadErrorReachesBothOnErrors() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var completed = new CountDownLatch(1);
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ac.addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent e) { events.add("async.onComplete"); completed.countDown(); }
                    @Override public void onTimeout(AsyncEvent e) { events.add("async.onTimeout"); }
                    @Override public void onError(AsyncEvent e) { events.add("async.onError"); }
                    @Override public void onStartAsync(AsyncEvent e) {}
                });
                ServletInputStream in = req.getInputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        byte[] buf = new byte[64];
                        while (in.isReady() && in.read(buf) != -1) { /* drain */ }
                        events.add("read.onDataAvailable");
                    }
                    @Override public void onAllDataRead() { events.add("read.onAllDataRead"); }
                    @Override public void onError(Throwable t) { events.add("read.onError"); }
                });
            }
        });

        try (var client = new Client(port)) {
            client.send(head());
            client.send("5\r\nHello\r\n3\r\nab"); // truncated chunk, then the connection goes away
        }
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        int readError = events.indexOf("read.onError");
        int asyncError = events.indexOf("async.onError");
        assertTrue(readError >= 0 && asyncError > readError, events.toString());
        assertEquals("async.onComplete", events.get(events.size() - 1), events.toString());
        assertFalse(events.contains("read.onAllDataRead"), events.toString());
    }

    /** The TCK's WriteListenerTests.nioOutputTest scenario. */
    @Test
    void onWritePossibleIsCalled() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ServletOutputStream out = resp.getOutputStream();
                out.setWriteListener(new WriteListener() {
                    @Override public void onWritePossible() throws IOException {
                        out.write("=onWritePossible".getBytes(StandardCharsets.US_ASCII));
                        ac.complete();
                    }
                    @Override public void onError(Throwable t) { ac.complete(); }
                });
            }
        });
        try (var client = new Client(port)) {
            client.send("GET /nio HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String response = client.readAll();
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertEquals(List.of("=onWritePossible"), tokens(response));
        }
    }

    /**
     * A listener writes 16 MiB while {@code isReady()} holds; the client reads nothing until the
     * listener has seen {@code isReady()} answer {@code false}. 16 MiB is far above what the
     * kernel socket buffers absorb, so the backpressure does not depend on the host's TCP tuning.
     * The writes never block, every byte arrives in order, and {@code onWritePossible} resumes the
     * listener once capacity frees up.
     */
    @Test
    void largeNonBlockingWriteResumesOnCapacity() throws Exception {
        int total = 16 << 20;
        var calls = new AtomicInteger();
        var notReady = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<Throwable>();
        start(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(30_000);
                resp.setContentLength(total);
                ServletOutputStream out = resp.getOutputStream();
                var next = new AtomicInteger();
                out.setWriteListener(new WriteListener() {
                    @Override public void onWritePossible() throws IOException {
                        calls.incrementAndGet();
                        while (out.isReady()) {
                            int start = next.get();
                            if (start == total) {
                                ac.complete();
                                return;
                            }
                            int n = Math.min(4096, total - start);
                            byte[] chunk = new byte[n];
                            for (int i = 0; i < n; i++) chunk[i] = (byte) ((start + i) % 251);
                            out.write(chunk);
                            next.addAndGet(n);
                        }
                        notReady.countDown();
                    }
                    @Override public void onError(Throwable t) {
                        errors.add(t);
                        ac.complete();
                    }
                });
            }
        });
        try (var socket = new Socket()) {
            socket.setReceiveBufferSize(64 * 1024);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port));
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /nio HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(notReady.await(10, TimeUnit.SECONDS), "isReady() never answered false");
            InputStream in = socket.getInputStream();
            var received = new ByteArrayOutputStream(total + 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                received.write(buf, 0, n);
            }
            byte[] all = received.toByteArray();
            String text = new String(all, StandardCharsets.ISO_8859_1);
            int bodyStart = text.indexOf("\r\n\r\n") + 4;
            assertTrue(text.startsWith("HTTP/1.1 200"), text.substring(0, Math.min(200, text.length())));
            assertEquals(total, all.length - bodyStart);
            for (int i = 0; i < total; i++) {
                if (all[bodyStart + i] != (byte) (i % 251)) fail("byte " + i + " out of order");
            }
        }
        assertEquals(List.of(), errors);
        assertTrue(calls.get() > 1, "onWritePossible called " + calls.get() + " time(s)");
    }

    /**
     * The response writer in non-blocking mode: a print while {@code isReady()} last answered
     * {@code false} throws {@link IllegalStateException} and none of its characters is sent.
     */
    @Test
    void writerPrintWhenNotReadyThrowsIseAndSendsNothing() throws Exception {
        String block = "a".repeat(4096);
        var refused = new AtomicBoolean();
        start(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(30_000);
                ServletOutputStream nio = resp.getOutputStream();
                // resetBuffer() lets the application switch to the writer: the listener stays set.
                resp.resetBuffer();
                var writer = resp.getWriter();
                var blocks = new AtomicInteger();
                nio.setWriteListener(new WriteListener() {
                    @Override public void onWritePossible() {
                        if (refused.get()) {
                            ac.complete();
                            return;
                        }
                        // Capped (16 MiB): a pipe that never fills must fail the test, not hang it.
                        while (nio.isReady() && blocks.get() < 4096) {
                            writer.print(block);
                            blocks.incrementAndGet();
                        }
                        try {
                            writer.print("!");
                        } catch (IllegalStateException expected) {
                            refused.set(true);
                        }
                    }
                    @Override public void onError(Throwable t) { ac.complete(); }
                });
            }
        });
        try (var client = new Client(port)) {
            client.send("GET /nio HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String response = client.readAll();
            assertTrue(response.startsWith("HTTP/1.1 200"), response.substring(0, Math.min(100, response.length())));
            assertTrue(refused.get());
            assertFalse(response.contains("!"), "a refused print sends nothing");
        }
    }

    // ---- helpers ----

    private static void enter(AtomicBoolean inside, AtomicBoolean overlap) {
        if (!inside.compareAndSet(false, true)) overlap.set(true);
    }

    private static void pause() {
        try { Thread.sleep(100); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String head() {
        return "POST /nio HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
    }

    private static final Pattern TOKEN = Pattern.compile("=[A-Za-z]+");

    private static List<String> tokens(String response) {
        var body = response.substring(response.indexOf("\r\n\r\n") + 4);
        var out = new ArrayList<String>();
        var m = TOKEN.matcher(body);
        while (m.find()) out.add(m.group());
        return out;
    }

    private void start(HttpServlet servlet) {
        var mappings = List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/nio"), servlet, "S"));
        var bridge = new ChappeServletBridge(new ServletDispatcher(mappings),
                new FilterRegistry(List.of()), new VidocqServletContext("/"), null, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void send(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** The status line, read within the socket timeout (5 s). */
        String statusLine() throws IOException {
            var line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') line.append((char) c);
            return line.toString().trim();
        }

        String readAll() throws IOException {
            var all = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            try {
                int n;
                while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
            } catch (SocketException reset) {
                // a reset ends the response as well
            }
            return all.toString(StandardCharsets.UTF_8);
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
