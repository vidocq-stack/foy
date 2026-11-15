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
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
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
        volatile boolean failOpen;
        volatile boolean failOpenWithError;
        volatile boolean failListener;
        /** The listener closes the connection with 1000 at the end of its {@code onOpen}. */
        volatile boolean closeOnOpen;
        /** {@code onText} waits for {@link #textGate} to open before it echoes. */
        volatile boolean holdText;
        final CountDownLatch textGate = new CountDownLatch(1);
        /** One permit per {@code onText} that started holding its message. */
        final Semaphore textEntered = new Semaphore(0);
        /** One permit per {@code destroy()}. */
        final Semaphore destroys = new Semaphore(0);
        /** The listener's {@code onClose} throws an {@code Error} once it recorded the close. */
        volatile boolean failCloseWithError;
        /** The listener's {@code onOpen} starts, on a thread of its own, a send the peer never reads. */
        volatile boolean blockASend;
        final AtomicReference<Thread> sender = new AtomicReference<>();
        final List<ClassLoader> loaders = new CopyOnWriteArrayList<>();
    }

    /** Far more than the socket buffers hold: to a peer that does not read, the send blocks. */
    private static final int BLOCKING_SEND_BYTES = 32 * 1024 * 1024;

    /** The text the listener holds in {@code onText}, and the event it records for it. */
    private static final String HELD = "held";
    private static final String HELD_EVENT = "text:" + HELD;
    private static final String ON_OPEN = "onOpen";

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
        probe.loaders.forEach(loader -> assertSame(appLoader, loader, probe.loaders.toString()));
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

    /**
     * An undeploy started on an interrupted thread still waits for its connections: the interrupt it
     * arrives with is set aside for the wait, so the takeover has ended with 1001 when the undeploy returns,
     * and the thread gets its interrupt back.
     */
    @Test
    void anInterruptedUndeployStillClosesWith1001() throws Exception {
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        connect(new TextListener(new CompletableFuture<>(), closedByServer));
        boolean stillInterrupted;
        Thread.currentThread().interrupt();
        try {
            context.closeUpgradedConnections();
        } finally {
            stillInterrupted = Thread.interrupted();
        }
        assertTrue(stillInterrupted, "the undeploy thread lost its interrupt");
        assertEquals(List.of("open", "close", "destroy"), probe.events, "the undeploy did not wait for its connection");
        assertEquals(1001, closedByServer.get(5, TimeUnit.SECONDS));
        assertEquals(1, probe.destroyCalls.get());
    }

    /**
     * The side that closed first gives the code: the listener hears the undeploy's own 1001, not the code
     * the peer answers its Close with (BUG-20261114-05).
     */
    @Test
    void anUndeployReportsItsOwn1001WhateverThePeerAnswers() throws Exception {
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        connect(new WebSocket.Listener() {
            @Override
            public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                closedByServer.complete(code);
                return ws.sendClose(4000, "another code");
            }
        });
        context.closeUpgradedConnections();
        assertEquals(1001, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(1001, probe.closeCode.get());
        assertEquals(List.of("open", "close", "destroy"), probe.events);
    }

    /**
     * The server stops before the peer answers the undeploy's Close: chappe sees a cut connection (1006),
     * the listener still hears the 1001 its side closed with (BUG-20261114-05).
     */
    @Test
    void anUndeployReports1001WhenTheServerStopsBeforeThePeerAnswers() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            context.closeUpgradedConnections();
            server.stop();
            assertTrue(probe.closed.await(5, TimeUnit.SECONDS));
            assertEquals(1001, probe.closeCode.get());
        }
    }

    /**
     * The server stops right after the undeploy, before the peer answers its Close: the read the stop
     * cuts is the transport going down, not an error of the connection, so the listener hears the 1001
     * and no error (BUG-20261114-07).
     */
    @Test
    void aServerStopAfterAnUndeployReportsNoError() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            context.closeUpgradedConnections();
            server.stop();
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("open", "close", "destroy"), probe.events);
        }
    }

    /**
     * The undeploy ends the takeover before it returns, so the listener's {@code onClose} and the
     * handler's {@code destroy()} run before {@code contextDestroyed}: it does not wait for the peer,
     * which here never answers (BUG-20261114-08).
     */
    @Test
    void anUndeployEndsTheTakeoverBeforeItReturns() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            context.closeUpgradedConnections();
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertEquals(1001, probe.closeCode.get());
        }
    }

    /**
     * A connection whose listener closed first keeps the peer's answer as its code: the undeploy waits for
     * that closing handshake to end before it returns (BUG-20261114-08).
     */
    @Test
    void anUndeployWaitsForAClosingHandshakeUnderWay() throws Exception {
        probe.closeOnOpen = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            client.readHead();
            client.readUntil("done");
            Thread undeploy = Thread.ofVirtual().start(context::closeUpgradedConnections);
            assertFalse(undeploy.join(Duration.ofMillis(300)), "the undeploy did not wait for the closing handshake");
            client.send(new byte[] {(byte) 0x88, (byte) 0x82, 0, 0, 0, 0, 0x03, (byte) 0xE8});
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertEquals(1000, probe.closeCode.get());
        }
    }

    /**
     * The undeploy's {@code onClose} waits for the event in progress: the listener never runs on two
     * threads at once (Jakarta WebSocket 2.2 section 5.1).
     */
    @Test
    void anUndeployWaitsForTheEventInProgress() throws Exception {
        holdATextInOnText();
        Thread undeploy = Thread.ofVirtual().start(context::closeUpgradedConnections);
        assertFalse(probe.closed.await(300, TimeUnit.MILLISECONDS), "onClose ran while onText was running");
        probe.textGate.countDown();
        assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
        assertEquals(1001, probe.closeCode.get());
    }

    /**
     * An event that does not return within the undeploy's grace does not stop the undeploy: the takeover
     * then ends on the transport's thread, once the event returned, still with the undeploy's 1001.
     */
    @Test
    void anUndeployDoesNotWaitForAnEventPastItsGrace() throws Exception {
        holdATextInOnText();
        Thread undeploy = Thread.ofVirtual().start(context::closeUpgradedConnections);
        assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy waited past its grace");
        assertEquals(List.of("open", HELD_EVENT), probe.events);
        probe.textGate.countDown();
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
        assertEquals(1001, probe.closeCode.get());
    }

    /**
     * The undeploy's grace runs out on an event in progress, so the takeover is still open when the server
     * stops: the I/O failure the stop causes (the held {@code onText} cannot echo any more) reaches the
     * takeover's {@code onError}, which must not report it, the undeploy having closed the session
     * (BUG-20261114-07).
     */
    @Test
    void aServerStopAfterTheUndeployGaveUpReportsNoError() throws Exception {
        holdATextInOnText();
        Thread undeploy = Thread.ofVirtual().start(context::closeUpgradedConnections);
        assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy waited past its grace");
        assertEquals(List.of("open", HELD_EVENT), probe.events);
        server.stop();
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
        assertEquals(1001, probe.closeCode.get());
    }

    /**
     * A send blocked on a peer that does not read holds chappe's write lock, which the 1001 Close needs: the
     * undeploy does not wait for that send past its grace, and still ends the takeover with 1001, once
     * (BUG-20261115-01).
     */
    @Test
    void anUndeployDoesNotWaitForASendBlockedOnThePeer() throws Exception {
        probe.blockASend = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            awaitState(probe.sender.get(), Thread.State.WAITING);
            Thread undeploy = Thread.ofVirtual().start(context::closeUpgradedConnections);
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy waited for the blocked send");
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertEquals(1001, probe.closeCode.get());
            assertEquals(1, probe.destroyCalls.get());
        }
    }

    /**
     * The undeploy closes its connections all at once: several held by an event in progress cost it about
     * one grace, not one each; each takeover ends once its event returns.
     */
    @Test
    void anUndeployWaitsForStuckConnectionsTogether() throws Exception {
        int connections = 4;
        holdATextInOnText(connections);
        long start = System.nanoTime();
        context.closeUpgradedConnections();
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        // A grace is 2 s: one connection after another, four of them would take 8 s.
        assertTrue(took.compareTo(Duration.ofSeconds(4)) < 0, "the undeploy took " + took);
        probe.textGate.countDown();
        assertTrue(probe.destroys.tryAcquire(connections, 5, TimeUnit.SECONDS), "a takeover never ended");
    }

    /**
     * An {@code Error} from the listener's {@code onClose} at undeploy is logged and stops neither
     * {@code destroy()} nor the undeploy, whose servlets and {@code contextDestroyed} come next.
     */
    @Test
    void anErrorFromOnCloseDoesNotStopTheUndeploy() throws Exception {
        probe.failCloseWithError = true;
        start(new VidocqServletContext("/"));
        connect(new TextListener(new CompletableFuture<>(), new CompletableFuture<>()));
        try (var log = LogCapture.of(VidocqServletContext.class.getName())) {
            assertDoesNotThrow(context::closeUpgradedConnections);
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertTrue(log.thrown().stream().anyMatch(ExceptionInInitializerError.class::isInstance),
                    log.thrown().toString());
        }
    }

    /**
     * Once the undeploy began, a WebSocket handshake is a request like any other: it gets 503 and never
     * reaches the application's filters and servlets, so no takeover starts (BUG-20261115-02).
     */
    @Test
    void aHandshakeOnceTheUndeployBeganGets503() throws Exception {
        start(new VidocqServletContext("/"));
        context.refuseRequests();
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            String head = client.readHead();
            assertTrue(head.startsWith("HTTP/1.1 503 "), head);
        }
        assertEquals(List.of(), probe.events);
        assertEquals(0, registeredConnections());
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

    @Test
    void aFailingOnOpenEndsWithErrorCloseDestroyAndUnregisters() throws Exception {
        probe.failOpen = true;
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        connectWithoutOpen(closedByServer);
        assertEquals(1011, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("error", "close", "destroy"), probe.events);
        assertEquals(1011, probe.closeCode.get());
        assertEquals(1, probe.destroyCalls.get());
        assertEquals(0, registeredConnections());
    }

    /**
     * An {@code Error} from {@code onOpen} ends chappe's connection with neither {@code onError} nor
     * {@code onClose}: the takeover closes with 1011 and ends itself before the {@code Error} propagates.
     */
    @Test
    void anErrorFromOnOpenEndsWithCloseDestroyAndUnregisters() throws Exception {
        probe.failOpenWithError = true;
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        connectWithoutOpen(closedByServer);
        assertEquals(1011, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("close", "destroy"), probe.events);
        assertEquals(1011, probe.closeCode.get());
        assertEquals(1, probe.destroyCalls.get());
        assertEquals(0, registeredConnections());
    }

    @Test
    void aFailingListenerFactoryEndsWithDestroyAndUnregisters() throws Exception {
        probe.failListener = true;
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        connectWithoutOpen(closedByServer);
        assertEquals(1011, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("destroy"), probe.events, "no listener: nobody hears the error or the close");
        assertEquals(1, probe.destroyCalls.get());
        assertEquals(0, registeredConnections());
    }

    /**
     * A registration refused after undeploy: the client gets 1001, the handler never started, so its
     * listener hears nothing and {@code destroy()} is not called (like {@code WebConnectionImpl}).
     */
    @Test
    void aRegistrationRefusedAfterUndeployClosesWith1001WithoutDestroy() throws Exception {
        start(new VidocqServletContext("/"));
        context.closeUpgradedConnections();
        var closedByServer = new CompletableFuture<Integer>();
        connectWithoutOpen(closedByServer);
        assertEquals(1001, closedByServer.get(5, TimeUnit.SECONDS));
        assertEquals(0, probe.destroyCalls.get());
        assertEquals(List.of(), probe.events);
        assertEquals(0, registeredConnections());
    }

    // ---- helpers ----

    /** Starts a takeover whose {@code onText} holds the message {@value #HELD} until the probe's gate opens. */
    private void holdATextInOnText() throws Exception {
        holdATextInOnText(1);
    }

    /** Starts {@code connections} takeovers, each holding the message {@value #HELD} in {@code onText}. */
    private void holdATextInOnText(int connections) throws Exception {
        probe.holdText = true;
        start(new VidocqServletContext("/"));
        for (int i = 0; i < connections; i++) {
            WebSocket ws = connect(new TextListener(new CompletableFuture<>(), new CompletableFuture<>()));
            ws.sendText(HELD, true).get(5, TimeUnit.SECONDS);
        }
        assertTrue(probe.textEntered.tryAcquire(connections, 5, TimeUnit.SECONDS), "onText");
    }

    /**
     * Waits until the thread is seen in {@code expected}: a send to a peer that does not read parks
     * ({@code WAITING}) in the socket write, and holds chappe's write lock from then on; the undeploy hook
     * waits for its Close with a time limit ({@code TIMED_WAITING}). One read per check: the park of a send
     * ends whenever the kernel takes more bytes.
     */
    private static void awaitState(Thread thread, Thread.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Thread.State state = thread.getState();
        while (state != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            state = thread.getState();
        }
        assertEquals(expected, state, thread.getName());
    }

    private void connectWithoutOpen(CompletableFuture<Integer> closedByServer) throws Exception {
        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"),
                        new TextListener(new CompletableFuture<>(), closedByServer))
                .get(5, TimeUnit.SECONDS);
    }

    /** The number of upgraded connections the context still tracks. */
    private int registeredConnections() throws ReflectiveOperationException {
        return hooks().size();
    }

    /** The undeploy hooks of the upgraded connections the context still tracks. */
    private List<AutoCloseable> hooks() throws ReflectiveOperationException {
        var field = VidocqServletContext.class.getDeclaredField("upgradedConnections");
        field.setAccessible(true);
        return ((java.util.Collection<?>) field.get(context)).stream().map(AutoCloseable.class::cast).toList();
    }

    private WebSocket connect(WebSocket.Listener listener) throws Exception {
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
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
            if (probe.failListener) throw new IllegalStateException("no listener");
            return new WebSocketListener() {
                @Override
                public void onOpen(WebSocketPeer p) throws IOException {
                    if (probe.failOpen) throw new IllegalStateException("onOpen refused");
                    if (probe.failOpenWithError) throw new ExceptionInInitializerError("static initializer failed");
                    peer.set(p);
                    probe.events.add("open");
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    if (probe.blockASend) probe.sender.set(Thread.ofVirtual().start(() -> sendWhatThePeerNeverReads(p)));
                    probe.opened.countDown();
                    if (probe.closeOnOpen) p.close(1000, "done");
                }

                @Override
                public void onText(String text) throws IOException {
                    probe.events.add("text:" + text);
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    if (probe.holdText) {
                        probe.textEntered.release();
                        awaitGate(probe.textGate);
                    }
                    peer.get().sendText(text);
                }

                @Override
                public void onError(Throwable error) {
                    probe.events.add("error");
                }

                @Override
                public void onClose(int code, String reason) {
                    probe.events.add("close");
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    probe.closeCode.set(code);
                    probe.closed.countDown();
                    if (probe.failCloseWithError) throw new ExceptionInInitializerError("onClose failed");
                }
            };
        }

        @Override
        public void destroy() {
            probe.events.add("destroy");
            probe.destroyCalls.incrementAndGet();
            probe.destroyed.countDown();
            probe.destroys.release();
        }

        /** Holds chappe's write lock for as long as the peer does not read. */
        private static void sendWhatThePeerNeverReads(WebSocketPeer p) {
            try {
                p.sendBinary(ByteBuffer.allocate(BLOCKING_SEND_BYTES));
            } catch (IOException _) {
                // the connection closed under the send
            }
        }

        private static void awaitGate(CountDownLatch gate) {
            try {
                if (!gate.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("the gate never opened");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
