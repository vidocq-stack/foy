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

    /** What the handlers of the running test record; reset before each test. */
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile CountDownLatch destroyed;
    static volatile CountDownLatch errored;
    static volatile CountDownLatch notReadySeen;
    static final AtomicInteger DESTROY_CALLS = new AtomicInteger();
    static final AtomicInteger WRITE_POSSIBLE_CALLS = new AtomicInteger();
    static final AtomicReference<WebConnection> CONNECTION = new AtomicReference<>();
    static final int LARGE = 4 * 1024 * 1024;

    private Server server;
    private int port;
    private VidocqServletContext context;

    @BeforeEach
    void reset() {
        EVENTS.clear();
        destroyed = new CountDownLatch(1);
        errored = new CountDownLatch(1);
        notReadySeen = new CountDownLatch(1);
        DESTROY_CALLS.set(0);
        WRITE_POSSIBLE_CALLS.set(0);
        CONNECTION.set(null);
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

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
        assertTrue(destroyed.await(5, TimeUnit.SECONDS), "destroy() after the connection closed");
        assertEquals(1, DESTROY_CALLS.get());
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
        assertTrue(destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(1, DESTROY_CALLS.get(), "a second close() is a no-op");
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
        assertEquals(0, DESTROY_CALLS.get());
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
                    EVENTS.add("second upgrade accepted");
                } catch (IllegalStateException e) {
                    EVENTS.add("ise");
                }
            }
        });
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertEquals("bye", client.readAll());
        }
        assertEquals("ise", EVENTS.getFirst());
    }

    /** The request leaves the HTTP lifecycle (requestDestroyed) before the handler takes over. */
    @Test
    void requestDestroyedFiresBeforeInit() throws Exception {
        start(upgradingServlet(ClosingHandler.class));
        context.listenerRegistry().register(new ServletRequestListener() {
            @Override public void requestInitialized(ServletRequestEvent sre) { EVENTS.add("requestInitialized"); }
            @Override public void requestDestroyed(ServletRequestEvent sre) { EVENTS.add("requestDestroyed"); }
        });
        try (var client = new Client(port)) {
            client.send(tckHead());
            assertTrue(client.readHead().startsWith("HTTP/1.1 101 "));
            assertEquals("bye", client.readAll());
        }
        assertEquals(List.of("requestInitialized", "requestDestroyed", "init"), EVENTS);
    }

    /** A connection reset while the ReadListener waits: onError, then destroy() and the close. */
    @Test
    void aReadErrorGoesToOnErrorThenDestroy() throws Exception {
        start(upgradingServlet(EchoHandler.class));
        var client = new Client(port);
        client.send(tckHead());
        client.readHead();
        client.readUntil("TCKHttpUpgradeHandler.init");
        client.reset();
        assertTrue(errored.await(5, TimeUnit.SECONDS), "onError after the reset");
        assertTrue(destroyed.await(5, TimeUnit.SECONDS), "destroy() after the read error");
        assertEquals(1, DESTROY_CALLS.get());
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
            assertTrue(destroyed.await(5, TimeUnit.SECONDS));
            assertEquals("", client.readAll(), "the connection closed");
        }
        assertEquals(1, DESTROY_CALLS.get());
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
            assertTrue(notReadySeen.await(10, TimeUnit.SECONDS), "isReady() never answered false");
            byte[] body = client.readAllBytes();
            assertEquals(LARGE, body.length);
            for (int i = 0; i < LARGE; i++) {
                if (body[i] != (byte) i) fail("byte " + i + " out of order");
            }
        }
        assertTrue(WRITE_POSSIBLE_CALLS.get() >= 2, "onWritePossible resumed the writer");
        assertTrue(destroyed.await(5, TimeUnit.SECONDS));
    }

    // ---- handlers ----

    /** The TCK's TCKHttpUpgradeHandler and TCKReadListener. */
    public static final class EchoHandler implements HttpUpgradeHandler {
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
                        errored.countDown();
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
            DESTROY_CALLS.incrementAndGet();
            destroyed.countDown();
        }
    }

    /** Writes "bye" and closes the connection twice. */
    public static final class ClosingHandler implements HttpUpgradeHandler {
        @Override
        public void init(WebConnection wc) {
            EVENTS.add("init");
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
            DESTROY_CALLS.incrementAndGet();
            destroyed.countDown();
        }
    }

    /** Writes "idle" and keeps the connection open. */
    public static final class IdleHandler implements HttpUpgradeHandler {
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
            DESTROY_CALLS.incrementAndGet();
            destroyed.countDown();
        }
    }

    /** Writes {@link #LARGE} bytes through a WriteListener, then closes the connection. */
    public static final class LargeWriteHandler implements HttpUpgradeHandler {
        @Override
        public void init(WebConnection wc) {
            try {
                ServletOutputStream out = wc.getOutputStream();
                out.setWriteListener(new WriteListener() {
                    private int written;

                    @Override public void onWritePossible() throws IOException {
                        WRITE_POSSIBLE_CALLS.incrementAndGet();
                        while (true) {
                            if (!out.isReady()) {
                                notReadySeen.countDown();
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
                        errored.countDown();
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void destroy() {
            DESTROY_CALLS.incrementAndGet();
            destroyed.countDown();
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
