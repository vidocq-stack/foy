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
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketPeer;
import io.vidocq.foy.spi.websocket.WebSocketUpgradeHandler;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@link WebSocketUpgradeHandler} given to {@code HttpServletRequest#upgrade} takes the connection
 * over through chappe's native RFC 6455 support (WEBSOCKET.md section 5.2).
 */
@Timeout(30)
class WebSocketTakeoverEndToEndTest {

    /** What the takeover of one test records; each handler captures the probe current at construction. */
    static final class Probe {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch destroyed = new CountDownLatch(1);
        final AtomicInteger destroyCalls = new AtomicInteger();
        final AtomicInteger closeCode = new AtomicInteger(-1);
        final List<ClassLoader> loaders = new CopyOnWriteArrayList<>();
    }

    private static volatile Probe current;

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
        if (context != null) context.closeUpgradedConnections();
        if (server != null) server.stop();
    }

    @Test
    void aTakeoverGetsA101AndEchoes() throws Exception {
        start(new VidocqServletContext("/"));
        var echoed = new CompletableFuture<String>();
        WebSocket ws = connect(new TextListener(echoed, new CompletableFuture<>()));
        ws.sendText("hi", true).get(5, TimeUnit.SECONDS);
        assertEquals("hi", echoed.get(5, TimeUnit.SECONDS));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void headersAndCookiesSetBeforeTheUpgradeReachThe101() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            String head = client.readHead();
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            assertTrue(head.contains("\r\nX-Test: 1\r\n"), head);
            assertTrue(head.contains("\r\nSet-Cookie: a=b\r\n"), head);
        }
    }

    @Test
    void aSessionCreatedDuringTheHandshakeSendsItsCookie() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws?session"));
            String head = client.readHead();
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            assertTrue(head.contains("\r\nSet-Cookie: JSESSIONID="), head);
        }
    }

    @Test
    void callbacksRunWithTheApplicationClassLoader() throws Exception {
        var ctx = new VidocqServletContext("/");
        var appLoader = new URLClassLoader(new URL[0], getClass().getClassLoader());
        ctx.setApplicationClassLoader(appLoader);
        start(ctx);
        var echoed = new CompletableFuture<String>();
        var closedByServer = new CompletableFuture<Integer>();
        WebSocket ws = connect(new TextListener(echoed, closedByServer));
        ws.sendText("hi", true).get(5, TimeUnit.SECONDS);
        echoed.get(5, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        assertTrue(probe.closed.await(5, TimeUnit.SECONDS));
        assertEquals(3, probe.loaders.size(), "onOpen, onText and onClose");
        assertTrue(probe.loaders.stream().allMatch(l -> l == appLoader), probe.loaders.toString());
    }

    @Test
    void undeployClosesWith1001AndDestroysOnce() throws Exception {
        start(new VidocqServletContext("/"));
        var echoed = new CompletableFuture<String>();
        var closedByServer = new CompletableFuture<Integer>();
        WebSocket ws = connect(new TextListener(echoed, closedByServer));
        ws.sendText("hi", true).get(5, TimeUnit.SECONDS);
        echoed.get(5, TimeUnit.SECONDS);
        context.closeUpgradedConnections();
        assertEquals(1001, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(1, probe.destroyCalls.get());
    }

    @Test
    void aPeerCloseCallsOnCloseThenDestroyOnce() throws Exception {
        start(new VidocqServletContext("/"));
        var echoed = new CompletableFuture<String>();
        WebSocket ws = connect(new TextListener(echoed, new CompletableFuture<>()));
        ws.sendText("hi", true).get(5, TimeUnit.SECONDS);
        echoed.get(5, TimeUnit.SECONDS);
        ws.sendClose(4000, "bye").get(5, TimeUnit.SECONDS);
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(4000, probe.closeCode.get());
        assertEquals(List.of("open", "text:hi", "close", "destroy"), probe.events);
        assertEquals(1, probe.destroyCalls.get());
    }

    // ---- helpers ----

    private WebSocket connect(WebSocket.Listener listener) throws Exception {
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        assertTrue(probe.opened.await(5, TimeUnit.SECONDS), "onOpen");
        return ws;
    }

    private static String handshake(String target) {
        return "GET " + target + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
    }

    private void start(VidocqServletContext ctx) {
        context = ctx;
        var servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                resp.setHeader("X-Test", "1");
                resp.addHeader("Set-Cookie", "a=b");
                if (req.getQueryString() != null) req.getSession();
                req.upgrade(EchoTakeover.class);
            }
        };
        var bridge = new ChappeServletBridge(new ServletDispatcher(
                List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/ws"), servlet, "WS"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(context),
                new SessionManager(new InMemorySessionStore(), context, 1800), "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    /** The client side: the echoed text, and the close code the server sent. */
    private static final class TextListener implements WebSocket.Listener {
        private final CompletableFuture<String> echoed;
        private final CompletableFuture<Integer> closeCode;

        TextListener(CompletableFuture<String> echoed, CompletableFuture<Integer> closeCode) {
            this.echoed = echoed;
            this.closeCode = closeCode;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            echoed.complete(data.toString());
            ws.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closeCode.complete(code);
            return null;
        }
    }

    /** Echoes text and records its lifecycle in the probe of the running test. */
    public static final class EchoTakeover implements WebSocketUpgradeHandler {
        private final Probe probe = current;
        private final AtomicReference<WebSocketPeer> peer = new AtomicReference<>();

        @Override public String subprotocol() { return null; }

        @Override
        public WebSocketListener listener() {
            return new WebSocketListener() {
                @Override
                public void onOpen(WebSocketPeer p) {
                    peer.set(p);
                    probe.events.add("open");
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    probe.opened.countDown();
                }

                @Override
                public void onText(String text) throws IOException {
                    probe.events.add("text:" + text);
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    peer.get().sendText(text);
                }

                @Override
                public void onClose(int code, String reason) {
                    probe.events.add("close");
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    probe.closeCode.set(code);
                    probe.closed.countDown();
                }
            };
        }

        @Override
        public void destroy() {
            probe.events.add("destroy");
            probe.destroyCalls.incrementAndGet();
            probe.destroyed.countDown();
        }
    }
}
