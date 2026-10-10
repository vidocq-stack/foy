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
