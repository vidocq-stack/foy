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
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.vidocq.foy.internal.LogCapture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Servlet 6.1 section 2.3.3.5: HTTP upgrade ({@link HttpServletRequest#upgrade},
 * {@link HttpUpgradeHandler}, {@link WebConnection}) over a real connection. The first scenario
 * mirrors the TCK's {@code HttpUpgradeHandlerTests.upgradeTest}.
 */
@Timeout(30)
class ServletUpgradeEndToEndTest {

    static final int LARGE = 4 * 1024 * 1024;

    /**
     * What the handlers of one test record. Each test gets its own probe and every handler captures
     * the probe current when the container instantiates it, so a late {@code destroy()} from an
     * earlier test's connection only ever touches that earlier test's probe.
     */
    static final class Probe {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch destroyed = new CountDownLatch(1);
        final CountDownLatch errored = new CountDownLatch(1);
        final CountDownLatch notReadySeen = new CountDownLatch(1);
        final CountDownLatch writing = new CountDownLatch(1);
        final AtomicInteger destroyCalls = new AtomicInteger();
        final AtomicInteger writePossibleCalls = new AtomicInteger();
        final AtomicReference<WebConnection> connection = new AtomicReference<>();
        final AtomicReference<Thread> callbackThread = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicLong closeMillis = new java.util.concurrent.atomic.AtomicLong(-1);
        final CountDownLatch closeReturned = new CountDownLatch(1);

        void destroy() {
            destroyCalls.incrementAndGet();
            destroyed.countDown();
        }
    }

    /** The probe of the running test, captured by each handler at construction. */
    static volatile Probe current;

    private Server server;
    private int port;
    private VidocqServletContext context;
    private Probe probe;

    @BeforeEach
    void newProbe() {
        probe = new Probe();
        current = probe;
    }

    @AfterEach
    void tearDown() {
        // Undeploy first: every connection still open is destroyed now, never during the next test.
        if (context != null) context.closeUpgradedConnections();
        if (server != null) server.stop();
    }

    /** The TCK's upgradeTest: 101 with the application's headers, then an echo through a ReadListener. */
    @Test
    void upgradeEchoesThroughAReadListener() throws Exception {
        start(upgradingServlet(EchoHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            String head = client.readHead();
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            assertTrue(head.contains("\r\nUpgrade: YES\r\n"), head);
            assertTrue(head.contains("\r\nConnection: Upgrade\r\n"), head);
            client.readUntil("===============TCKHttpUpgradeHandler.init");
            client.send("Hello");
            client.readUntil("Hello");
            client.send("World");
            client.readUntil("World");
            // The peer ends its side: onAllDataRead closes the output, and the connection ends.
            client.shutdownOutput();
            String rest = client.readAll();
            assertTrue(rest.contains("=onAllDataRead"), rest);
            String all = client.received();
            assertFalse(all.contains("End of Test"), all);
            int init = all.indexOf("TCKHttpUpgradeHandler.init");
            int hello = all.indexOf("Hello");
            int world = all.indexOf("World");
            assertTrue(init < hello && hello < world, all);
            assertTrue(all.substring(init).contains("=onDataAvailable"), all);
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS), "destroy() after the connection closed");
        assertEquals(1, probe.destroyCalls.get());
    }

    /** WebConnection.close() calls destroy() once and closes the connection. */
    @Test
    void destroyCalledOnClose() throws Exception {
        start(upgradingServlet(ClosingHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertEquals("bye", client.readAll());
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(1, probe.destroyCalls.get(), "a second close() is a no-op");
    }

    @Test
    void upgradeOverHttp10Throws() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                try {
                    req.upgrade(EchoHandler.class);
                    resp.getWriter().print("upgraded");
                } catch (IOException e) {
                    resp.getWriter().print("IOException: " + e.getMessage());
                } catch (ServletException e) {
                    resp.getWriter().print("ServletException");
                }
            }
        });
        try (var client = new Client(port)) {
            client.send("POST /up HTTP/1.0\r\nHost: localhost\r\nUpgrade: YES\r\nConnection: Upgrade\r\n"
                    + "Content-Length: 0\r\n\r\n");
            String response = client.readAll();
            assertTrue(response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.1 200"), response);
            assertTrue(response.endsWith("IOException: HTTP upgrade is not supported over HTTP/1.0"), response);
        }
        assertEquals(0, probe.destroyCalls.get());
    }

    @Test
    void upgradeHandlerInstantiationFailureIsServletException() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                try {
                    req.upgrade(FailingHandler.class);
                    resp.getWriter().print("upgraded");
                } catch (ServletException e) {
                    resp.getWriter().print("ServletException");
                }
            }
        });
        try (var client = new Client(port)) {
            // No upgrade happens: the connection stays HTTP and closes after the response.
            client.send(tckHead().replace("Connection: Upgrade", "Connection: close"));
            String response = client.readAll();
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertTrue(response.endsWith("ServletException"), response);
        }
    }

    @Test
    void upgradeTwiceThrowsIse() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.setStatus(101);
                resp.setHeader("Upgrade", "YES");
                resp.setHeader("Connection", "Upgrade");
                req.upgrade(ClosingHandler.class);
                try {
                    req.upgrade(ClosingHandler.class);
                    probe.events.add("second upgrade accepted");
                } catch (IllegalStateException e) {
                    probe.events.add("ise");
                }
            }
        });
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertEquals("bye", client.readAll());
        }
        assertEquals("ise", probe.events.getFirst());
    }

    /** The request leaves the HTTP lifecycle (requestDestroyed) before the handler takes over. */
    @Test
    void requestDestroyedFiresBeforeInit() throws Exception {
        start(upgradingServlet(ClosingHandler.class));
        context.listenerRegistry().register(new ServletRequestListener() {
            @Override public void requestInitialized(ServletRequestEvent sre) { probe.events.add("requestInitialized"); }
            @Override public void requestDestroyed(ServletRequestEvent sre) { probe.events.add("requestDestroyed"); }
        });
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertEquals("bye", client.readAll());
        }
        assertEquals(List.of("requestInitialized", "requestDestroyed", "init"), probe.events);
    }

    /** A connection reset while the ReadListener waits: onError, then destroy() and the close. */
    @Test
    void aReadErrorGoesToOnErrorThenDestroy() throws Exception {
        start(upgradingServlet(EchoHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            client.readHead();
            client.readUntil("TCKHttpUpgradeHandler.init");
            client.reset();
            assertTrue(probe.errored.await(5, TimeUnit.SECONDS), "onError after the reset");
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS), "destroy() after the read error");
        }
        assertEquals(1, probe.destroyCalls.get());
    }

    /** A flush after upgrade() never commits: the upgrade still happens and the body never leaves. */
    @Test
    void bodyFlushedAfterUpgradeIsDiscarded() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.getWriter().println("Before upgrade");
                resp.setStatus(101);
                resp.setHeader("Upgrade", "YES");
                resp.setHeader("Connection", "Upgrade");
                req.upgrade(ClosingHandler.class);
                resp.getWriter().println("End of Test");
                resp.flushBuffer();
                resp.getWriter().print("z".repeat(64 * 1024)); // more than the buffer
                resp.getWriter().flush();
            }
        });
        try (var client = new Client(port)) {
            client.send(tckHead());
            String head = client.readHead();
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            assertEquals("bye", client.readAll());
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void upgradeAfterStartAsyncThrowsIse() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                var ac = req.startAsync();
                try {
                    req.upgrade(ClosingHandler.class);
                    resp.getWriter().print("upgraded");
                } catch (IllegalStateException e) {
                    resp.getWriter().print("ise");
                }
                ac.complete();
            }
        });
        try (var client = new Client(port)) {
            client.send(tckHead().replace("Connection: Upgrade", "Connection: close"));
            String response = client.readAll();
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertTrue(response.endsWith("ise"), response);
        }
        assertEquals(0, probe.destroyCalls.get());
    }

    @Test
    void startAsyncAfterUpgradeThrowsIse() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.setStatus(101);
                resp.setHeader("Upgrade", "YES");
                resp.setHeader("Connection", "Upgrade");
                req.upgrade(ClosingHandler.class);
                try {
                    req.startAsync();
                    probe.events.add("async accepted");
                } catch (IllegalStateException e) {
                    probe.events.add("ise");
                }
            }
        });
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertEquals("bye", client.readAll());
        }
        assertEquals("ise", probe.events.getFirst());
    }

    /** {@code out.write(lastFrame); wc.close();} from onWritePossible: the last frame still goes out. */
    @Test
    void closeDeliversTheNonBlockingWriteInFlight() throws Exception {
        start(upgradingServlet(LastFrameHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            byte[] body = client.readAllBytes();
            assertEquals(LastFrameHandler.SIZE, body.length, "the last frame was cut by the close");
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
    }

    /** The same close facing a peer that reads nothing: it waits a bounded time, then closes anyway. */
    @Test
    void closeAfterANonBlockingWriteIsBoundedOnAStalledPeer() throws Exception {
        start(upgradingServlet(StalledLastFrameHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertTrue(probe.closeReturned.await(10, TimeUnit.SECONDS), "close() never returned");
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        }
        long closeMillis = probe.closeMillis.get();
        assertTrue(closeMillis >= 0 && closeMillis < 5_000, "close() took " + closeMillis + " ms");
    }

    /** chappe refuses the upgrade head (invalid header): a 500, a WARNING naming it, no handler started. */
    @Test
    void anInvalidUpgradeHeaderIsA500AndAWarning() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.setStatus(101);
                resp.setHeader("Upgrade", "YES");
                resp.setHeader("Bad Name", "x");
                req.upgrade(ClosingHandler.class);
            }
        });
        try (var log = LogCapture.of(ChappeServletBridge.class.getName());
             var client = new Client(port)) {
            client.send(tckHead().replace("Connection: Upgrade", "Connection: close"));
            String response = client.readAll();
            assertTrue(response.startsWith("HTTP/1.1 500"), response);
            assertTrue(log.warnings().stream().anyMatch(w -> w.contains("Bad Name")), log.warnings().toString());
        }
        assertEquals(List.of(), probe.events, "init never ran");
        assertEquals(0, probe.destroyCalls.get(), "destroy never runs for a handler never initialised");
    }

    /** An upgrade with a status other than 101 still happens, with a WARNING. */
    @Test
    void anUpgradeWithAStatusOtherThan101Warns() throws Exception {
        start(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.setHeader("Upgrade", "YES");
                resp.setHeader("Connection", "Upgrade");
                req.upgrade(ClosingHandler.class);
            }
        });
        try (var log = LogCapture.of(ChappeServletBridge.class.getName());
             var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 200 "));
            assertEquals("bye", client.readAll());
            assertTrue(log.warnings().stream().anyMatch(w -> w.contains("200")), log.warnings().toString());
        }
    }

    /**
     * The output is closed before the peer ends its side: the end of the input still reaches the
     * ReadListener ({@code onAllDataRead}), and only then does the connection close.
     */
    @Test
    void onAllDataReadIsDeliveredWhenTheOutputClosedFirst() throws Exception {
        start(upgradingServlet(OutputFirstHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            client.readUntil("closed");
            client.send("Hi");
            client.shutdownOutput();
            assertEquals("", client.readAll(), "the connection closes after onAllDataRead");
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("onAllDataRead", "destroy"), probe.events);
    }

    /**
     * WebConnection.close() from another thread while a callback is blocked writing to a peer that
     * reads nothing: the connection closes first, so the blocked write fails and close() returns.
     */
    @Test
    void closeReturnsWhileACallbackIsBlockedOnAStalledPeer() throws Exception {
        start(upgradingServlet(StallingHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            client.send("go");
            assertTrue(probe.writing.await(5, TimeUnit.SECONDS), "the callback started writing");
            Thread writer = probe.callbackThread.get();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (writer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(Thread.State.WAITING, writer.getState(), "the callback never blocked on the write");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> probe.connection.get().close());
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        }
        assertEquals(1, probe.destroyCalls.get());
    }

    /** init throwing: destroy() runs and the connection closes. */
    @Test
    void initThrowingDestroysAndCloses() throws Exception {
        start(upgradingServlet(ThrowingInitHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertEquals("x", client.readAll());
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(1, probe.destroyCalls.get());
    }

    /** Undeploying the context closes the upgraded connections it still holds. */
    @Test
    void undeployClosesOpenUpgradedConnections() throws Exception {
        start(upgradingServlet(IdleHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            client.readUntil("idle");
            context.closeUpgradedConnections();
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertEquals("", client.readAll(), "the connection closed");
        }
        assertEquals(1, probe.destroyCalls.get());
    }

    /**
     * Non-blocking output on the upgraded connection: the client reads nothing until the handler
     * saw {@code isReady() == false}; every byte then arrives in order and onWritePossible resumed
     * the writer.
     */
    @Test
    void writeListenerResumesOnCapacity() throws Exception {
        start(upgradingServlet(LargeWriteHandler.class));
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertTrue(probe.notReadySeen.await(10, TimeUnit.SECONDS), "isReady() never answered false");
            byte[] body = client.readAllBytes();
            assertEquals(LARGE, body.length);
            for (int i = 0; i < LARGE; i++) {
                if (body[i] != (byte) i) fail("byte " + i + " out of order");
            }
        }
        assertTrue(probe.writePossibleCalls.get() >= 2, "onWritePossible resumed the writer");
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
    }

    // ---- handlers ----

    /** Closes its output in init, then records onAllDataRead and destroy. */
    public static final class OutputFirstHandler implements HttpUpgradeHandler {
        private final Probe probe = current;

        @Override
        public void init(WebConnection wc) {
            try {
                ServletInputStream in = wc.getInputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        byte[] buf = new byte[64];
                        while (in.isReady() && in.read(buf) != -1) { /* drain */ }
                    }
                    @Override public void onAllDataRead() { probe.events.add("onAllDataRead"); }
                    @Override public void onError(Throwable t) { probe.events.add("onError"); }
                });
                ServletOutputStream out = wc.getOutputStream();
                out.print("closed");
                out.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            probe.events.add("destroy");
            probe.destroy();
        }
    }

    /** On the first data, writes 64 MiB (blocking) from the callback: a peer that reads nothing stalls it. */
    public static final class StallingHandler implements HttpUpgradeHandler {
        private final Probe probe = current;

        @Override
        public void init(WebConnection wc) {
            probe.connection.set(wc);
            try {
                ServletInputStream in = wc.getInputStream();
                ServletOutputStream out = wc.getOutputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() {
                        probe.callbackThread.set(Thread.currentThread());
                        probe.writing.countDown();
                        byte[] chunk = new byte[64 * 1024];
                        try {
                            for (int i = 0; i < 1024; i++) out.write(chunk);
                        } catch (IOException closed) {
                            // the connection was closed under the write
                        }
                    }
                    @Override public void onAllDataRead() {}
                    @Override public void onError(Throwable t) {}
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            probe.destroy();
        }
    }

    /** From onWritePossible: one large non-blocking write, then {@code wc.close()} at once. */
    abstract static class FrameThenCloseHandler implements HttpUpgradeHandler {
        private final Probe probe = current;
        private final int size;

        FrameThenCloseHandler(int size) {
            this.size = size;
        }

        @Override
        public void init(WebConnection wc) {
            try {
                ServletOutputStream out = wc.getOutputStream();
                out.setWriteListener(new WriteListener() {
                    @Override public void onWritePossible() throws IOException {
                        out.write(new byte[size]);
                        long start = System.nanoTime();
                        try { wc.close(); }
                        catch (Exception e) { throw new IOException(e); }
                        probe.closeMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                        probe.closeReturned.countDown();
                    }
                    @Override public void onError(Throwable t) { probe.errored.countDown(); }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            probe.destroy();
        }
    }

    /** A last frame larger than the socket buffers: still in flight when close() is called. */
    public static final class LastFrameHandler extends FrameThenCloseHandler {
        static final int SIZE = 4 * 1024 * 1024;

        public LastFrameHandler() { super(SIZE); }
    }

    /** A last frame no peer buffer can hold, sent to a peer that reads nothing. */
    public static final class StalledLastFrameHandler extends FrameThenCloseHandler {
        public StalledLastFrameHandler() { super(64 * 1024 * 1024); }
    }

    /** Writes "x" then throws from init. */
    public static final class ThrowingInitHandler implements HttpUpgradeHandler {
        private final Probe probe = current;

        @Override
        public void init(WebConnection wc) {
            try {
                wc.getOutputStream().print("x");
                wc.getOutputStream().flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            throw new IllegalStateException("init failed");
        }

        @Override
        public void destroy() {
            probe.destroy();
        }
    }

    /** The TCK's TCKHttpUpgradeHandler and TCKReadListener. */
    public static final class EchoHandler implements HttpUpgradeHandler {
        private final Probe probe = current;

        @Override
        public void init(WebConnection wc) {
            try {
                ServletInputStream in = wc.getInputStream();
                ServletOutputStream out = wc.getOutputStream();
                in.setReadListener(new ReadListener() {
                    @Override public void onDataAvailable() throws IOException {
                        out.println("=onDataAvailable");
                        var sb = new StringBuilder();
                        byte[] buf = new byte[1024];
                        int n;
                        while (in.isReady() && (n = in.read(buf)) != -1) {
                            sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                        }
                        out.println(sb + "/");
                        out.flush();
                    }
                    @Override public void onAllDataRead() throws IOException {
                        out.println("=onAllDataRead");
                        out.close();
                    }
                    @Override public void onError(Throwable t) {
                        probe.errored.countDown();
                    }
                });
                out.println("===============TCKHttpUpgradeHandler.init");
                out.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            probe.destroy();
        }
    }

    /** Writes "bye" and closes the connection twice. */
    public static final class ClosingHandler implements HttpUpgradeHandler {
        private final Probe probe = current;

        @Override
        public void init(WebConnection wc) {
            probe.events.add("init");
            try {
                wc.getOutputStream().print("bye");
                wc.getOutputStream().flush();
                wc.close();
                wc.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            probe.destroy();
        }
    }

    /** Writes "idle" and keeps the connection open. */
    public static final class IdleHandler implements HttpUpgradeHandler {
        private final Probe probe = current;

        @Override
        public void init(WebConnection wc) {
            try {
                wc.getOutputStream().print("idle");
                wc.getOutputStream().flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            probe.destroy();
        }
    }

    /** Writes {@link #LARGE} bytes through a WriteListener, then closes the connection. */
    public static final class LargeWriteHandler implements HttpUpgradeHandler {
        private final Probe probe = current;

        @Override
        public void init(WebConnection wc) {
            try {
                ServletOutputStream out = wc.getOutputStream();
                out.setWriteListener(new WriteListener() {
                    private int written;

                    @Override public void onWritePossible() throws IOException {
                        probe.writePossibleCalls.incrementAndGet();
                        while (true) {
                            if (!out.isReady()) {
                                probe.notReadySeen.countDown();
                                return;
                            }
                            if (written == LARGE) {
                                // Every byte is on the wire (ready again): close now.
                                try { wc.close(); }
                                catch (Exception e) { throw new IOException(e); }
                                return;
                            }
                            byte[] chunk = new byte[Math.min(16 * 1024, LARGE - written)];
                            for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (written + i);
                            out.write(chunk);
                            written += chunk.length;
                        }
                    }
                    @Override public void onError(Throwable t) {
                        probe.errored.countDown();
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            probe.destroy();
        }
    }

    /** Its constructor throws: {@code upgrade} must answer a ServletException. */
    public static final class FailingHandler implements HttpUpgradeHandler {
        public FailingHandler() {
            throw new IllegalStateException("no instance");
        }

        @Override public void init(WebConnection wc) {}
        @Override public void destroy() {}
    }

    // ---- helpers ----

    /** The TCK's TestServlet: upgrades when the Upgrade header is present, then writes a body. */
    private static HttpServlet upgradingServlet(Class<? extends HttpUpgradeHandler> handler) {
        return new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                if (req.getHeader("Upgrade") != null) {
                    resp.setStatus(101);
                    resp.setHeader("Upgrade", "YES");
                    resp.setHeader("Connection", "Upgrade");
                    req.upgrade(handler);
                } else {
                    resp.getWriter().println("No upgrade");
                }
                resp.getWriter().println("End of Test");
            }
        };
    }

    /** The TCK request head: POST with Upgrade and Connection, no body length. */
    private static String tckHead() {
        return "POST /up HTTP/1.1\r\nUser-Agent: Java/25\r\nHost: localhost\r\nAccept: text/html\r\n"
                + "Upgrade: YES\r\nConnection: Upgrade\r\nContent-type: application/x-www-form-urlencoded\r\n\r\n";
    }

    private void start(HttpServlet servlet) {
        var mappings = List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/up"), servlet, "S"));
        context = new VidocqServletContext("/");
        var bridge = new ChappeServletBridge(new ServletDispatcher(mappings),
                new FilterRegistry(List.of()), context, null, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void send(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void shutdownOutput() throws IOException { socket.shutdownOutput(); }

        /** Aborts the connection with a TCP reset. */
        void reset() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        /** The response head, up to and including the blank line. */
        String readHead() throws IOException {
            var head = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                head.append((char) c);
                if (head.toString().endsWith("\r\n\r\n")) break;
            }
            return head.toString();
        }

        /** Reads until everything received after the head contains {@code marker}. */
        void readUntil(String marker) throws IOException {
            byte[] buf = new byte[1024];
            while (!received().contains(marker)) {
                int n = in.read(buf);
                if (n == -1) fail("connection closed before '" + marker + "': " + received());
                received.write(buf, 0, n);
            }
        }

        /** What was received after the head so far. */
        String received() { return received.toString(StandardCharsets.US_ASCII); }

        /** Reads to the end of the connection; returns what this call read. */
        String readAll() throws IOException {
            return new String(readAllBytes(), StandardCharsets.US_ASCII);
        }

        byte[] readAllBytes() throws IOException {
            var all = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            try {
                int n;
                while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
            } catch (SocketException reset) {
                // a reset ends the connection too
            }
            received.writeBytes(all.toByteArray());
            return all.toByteArray();
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
