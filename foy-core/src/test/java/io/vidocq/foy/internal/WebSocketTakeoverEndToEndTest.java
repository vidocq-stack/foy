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
import java.util.concurrent.atomic.AtomicBoolean;
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
        final AtomicReference<String> closeReason = new AtomicReference<>();
        volatile boolean failOpen;
        /** The listener's {@code onText} throws an {@code Error}. */
        volatile boolean failTextWithError;
        volatile boolean failOpenWithError;
        volatile boolean failListener;
        /** The listener closes the connection with 1000 at the end of its {@code onOpen}. */
        volatile boolean closeOnOpen;
        /** {@code onText} waits for {@link #textGate} to open before it echoes. */
        volatile boolean holdText;
        /** {@code onText} records the message without echoing it. */
        volatile boolean noEcho;
        final CountDownLatch textGate = new CountDownLatch(1);
        /** One permit per {@code onText} that started holding its message. */
        final Semaphore textEntered = new Semaphore(0);
        /** One permit per {@code destroy()}. */
        final Semaphore destroys = new Semaphore(0);
        /** The listener's {@code onClose} throws an {@code Error} once it recorded the close. */
        volatile boolean failCloseWithError;
        /** The listener's {@code onOpen} starts, on a thread of its own, a send the peer never reads. */
        volatile boolean blockASend;
        /** The listener's {@code onOpen} itself makes a send the peer never reads, and rethrows its failure. */
        volatile boolean blockASendInOnOpen;
        /** The listener's {@code onOpen} waits for {@link #openGate}, then throws an {@code IllegalStateException}. */
        volatile boolean failOpenOnceReleased;
        /** With {@link #failOpenOnceReleased}: an {@code IOException} instead. */
        volatile boolean failOpenWithIo;
        final CountDownLatch openGate = new CountDownLatch(1);
        /**
         * The listener's {@code onError} waits for {@link #errorGate} once it recorded the error, and counted
         * {@link #errorHeld} down.
         */
        volatile boolean holdError;
        final CountDownLatch errorGate = new CountDownLatch(1);
        final CountDownLatch errorHeld = new CountDownLatch(1);
        /** The thread of the blocked send. */
        final AtomicReference<Thread> sender = new AtomicReference<>();
        /** How the blocked send ended: its {@code IOException}, or {@code null} once written. */
        final CompletableFuture<IOException> sendFailure = new CompletableFuture<>();
        final List<ClassLoader> loaders = new CopyOnWriteArrayList<>();
        /** The listener's {@code onClose} waits for {@link #closeGate} to open once it recorded the close. */
        volatile boolean holdClose;
        final CountDownLatch closeGate = new CountDownLatch(1);
        /**
         * Whether the connection was still open when {@code onClose} began (no Close sent nor received);
         * {@code true} until {@code onClose} recorded it.
         */
        final AtomicBoolean openAtClose = new AtomicBoolean(true);
        /** The name of the thread {@code onClose} ran on. */
        final AtomicReference<String> closeThread = new AtomicReference<>();
    }

    /** Far more than the socket buffers hold: to a peer that does not read, the send blocks. */
    private static final int BLOCKING_SEND_BYTES = 32 * 1024 * 1024;

    /** The text the listener holds in {@code onText}, and the event it records for it. */
    private static final String HELD = "held";
    private static final String HELD_EVENT = "text:" + HELD;
    private static final String ON_OPEN = "onOpen";
    private static final String WAITED_FOR_THE_BLOCKED_SEND = "the undeploy waited for the blocked send";
    /** The logger of chappe's WebSocket connection, which logs a failed {@code onOpen} before its onError. */
    private static final String CHAPPE_CONNECTION_LOGGER = "io.vidocq.chappe.http.ws.WebSocketConnection";
    private static final String ON_OPEN_FAILED_ON_ITS_OWN = "onOpen failed on its own";
    /** How many of the last bytes before the end of a cut connection the tests check. */
    private static final int TAIL = 64;
    /** The reason of the undeploy's 1001 Close, the last bytes of its frame. */
    private static final String STOPPING_REASON = "the application is stopping";
    private static final byte[] STOPPING = STOPPING_REASON.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /** The reason of the close of a failed {@code onOpen}, on the wire and to the listener. */
    private static final String ON_OPEN_FAILED = "onOpen failed";
    private static final String TAKEOVER_LOGGER = "io.vidocq.foy.internal.bridge.WebSocketTakeover";
    /** The prefix of the virtual threads the context runs its undeploy hooks on. */
    private static final String HOOK_THREAD = "foy-upgrade-close-";

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
        probe.openGate.countDown();
        probe.errorGate.countDown();
        if (context != null) closeConnections();
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
        closeConnections();
        assertEquals(1001, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(1, probe.destroyCalls.get());
    }

    /**
     * An undeploy started on an interrupted thread still waits for its connections: the interrupt it
     * arrives with is set aside for the wait, so the takeover has ended (its peer read 1001, its listener heard
     * 1006) when the undeploy returns, and the thread gets its interrupt back.
     */
    @Test
    void anInterruptedUndeployStillClosesWith1001() throws Exception {
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        connect(new TextListener(new CompletableFuture<>(), closedByServer));
        boolean stillInterrupted;
        Thread.currentThread().interrupt();
        try {
            closeConnections();
        } finally {
            stillInterrupted = Thread.interrupted();
        }
        assertTrue(stillInterrupted, "the undeploy thread lost its interrupt");
        assertEquals(List.of("open", "close", "destroy"), probe.events, "the undeploy did not wait for its connection");
        assertEquals(1001, closedByServer.get(5, TimeUnit.SECONDS));
        assertClosedByTheUndeploy();
        assertEquals(1, probe.destroyCalls.get());
    }

    /**
     * The side that closed first gives the code: the listener hears the undeploy's own close, 1006 for a close
     * the container initiated, not the code the peer answers its Close with (BUG-20261114-05,
     * BUG-20261115-08).
     */
    @Test
    void anUndeployReportsItsOwn1006WhateverThePeerAnswers() throws Exception {
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        connect(new WebSocket.Listener() {
            @Override
            public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                closedByServer.complete(code);
                return ws.sendClose(4000, "another code");
            }
        });
        closeConnections();
        assertEquals(1001, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertClosedByTheUndeploy();
        assertEquals(List.of("open", "close", "destroy"), probe.events);
    }

    /**
     * The server stops before the peer answers the undeploy's Close: chappe sees a cut connection (1006 with
     * no reason), the listener still hears the close its side initiated, with its reason (BUG-20261114-05).
     */
    @Test
    void anUndeployReports1006WhenTheServerStopsBeforeThePeerAnswers() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            closeConnections();
            server.stop();
            assertTrue(probe.closed.await(5, TimeUnit.SECONDS));
            assertClosedByTheUndeploy();
        }
    }

    /**
     * The server stops right after the undeploy, before the peer answers its Close: the listener hears the
     * undeploy's close and no error. The undeploy ended the takeover before the stop (BUG-20261114-08), so the read the
     * stop cuts finds it closed; {@link #aServerStopWhileTheUndeployWaitsForItsCloseReportsNoError} pins the
     * case of BUG-20261114-07, a stop that reaches a takeover still open.
     */
    @Test
    void aServerStopAfterAnUndeployReportsNoError() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            closeConnections();
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
            closeConnections();
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertClosedByTheUndeploy();
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
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
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
        Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
        assertFalse(probe.closed.await(300, TimeUnit.MILLISECONDS), "onClose ran while onText was running");
        probe.textGate.countDown();
        assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
        assertClosedByTheUndeploy();
    }

    /**
     * An event that does not return within the undeploy's grace does not stop the undeploy: the takeover
     * then ends on the transport's thread, once the event returned, still with the undeploy's close (1006).
     */
    @Test
    void anUndeployDoesNotWaitForAnEventPastItsGrace() throws Exception {
        holdATextInOnText();
        Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
        assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy waited past its grace");
        assertEquals(List.of("open", HELD_EVENT), probe.events);
        probe.textGate.countDown();
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
        assertClosedByTheUndeploy();
    }

    /**
     * Once the undeploy closed the connection, no frame reaches the listener: a frame the peer sends after the
     * undeploy stopped waiting for an event in progress is not delivered after the application stopped, and
     * the takeover ends with the undeploy's close (1006) as soon as that event returns.
     */
    @Test
    void aFrameAfterTheUndeployGaveUpReachesNoListener() throws Exception {
        probe.holdText = true;
        probe.noEcho = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            client.send(clientText(HELD));
            assertTrue(probe.textEntered.tryAcquire(5, TimeUnit.SECONDS), "onText");
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy waited past its grace");
            client.send(clientText("late"));
            probe.textGate.countDown();
            boolean ended = probe.destroyed.await(5, TimeUnit.SECONDS);
            assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events,
                    "a frame reached the listener after the undeploy closed the connection");
            assertTrue(ended, "the takeover did not end once the event returned");
            assertClosedByTheUndeploy();
        }
    }

    /**
     * The undeploy's grace runs out on an event in progress, then the server stops: the held {@code onText}
     * cannot echo any more, and the takeover ends with the undeploy's close (1006) as soon as it returns, so the
     * stop's I/O failure finds it closed and the listener hears no error.
     */
    @Test
    void aServerStopAfterTheUndeployGaveUpReportsNoError() throws Exception {
        holdATextInOnText();
        Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
        assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy waited past its grace");
        assertEquals(List.of("open", HELD_EVENT), probe.events);
        server.stop();
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
        assertClosedByTheUndeploy();
    }

    /**
     * The server stops while the undeploy hook still waits for its 1001 Close, which a send blocked on the
     * peer holds back: the connection is marked closed by the undeploy but its takeover has not ended yet, so
     * the read the stop cuts reaches the takeover's {@code onError}, which must not report it
     * (BUG-20261114-07). The listener hears the undeploy's close (1006) and no error.
     */
    @Test
    void aServerStopWhileTheUndeployWaitsForItsCloseReportsNoError() throws Exception {
        probe.blockASend = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            awaitState(probe.sender.get(), Thread.State.WAITING);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertFalse(undeploy.join(Duration.ofMillis(500)), "the undeploy did not wait for its held Close");
            // Still open: the stop reaches a takeover the undeploy marked closed but has not ended yet.
            assertEquals(List.of("open"), probe.events, "the takeover ended before the stop");
            server.stop();
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertClosedByTheUndeploy();
        }
    }

    /**
     * An event in progress that returns while a send of the endpoint still holds back the undeploy's 1001
     * Close does not end the takeover: the listener hears {@code onClose} once that Close went out (Jakarta
     * WebSocket 2.2 section 2.1.5), here once the peer read what the send had queued.
     */
    @Test
    void onCloseFollowsTheUndeploysCloseFrame() throws Exception {
        probe.blockASend = true;
        probe.holdText = true;
        probe.noEcho = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            awaitState(probe.sender.get(), Thread.State.WAITING);
            client.send(clientText(HELD));
            assertTrue(probe.textEntered.tryAcquire(5, TimeUnit.SECONDS), "onText");
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertFalse(undeploy.join(Duration.ofMillis(300)), "the undeploy did not wait for its held Close");
            probe.textGate.countDown();
            client.skipPast(STOPPING);
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
            assertFalse(probe.openAtClose.get(), "onClose ran before the 1001 Close went out");
            assertClosedByTheUndeploy();
        }
    }

    /**
     * The event in progress returns after the undeploy sent its Close, so the takeover ends on the
     * connection's thread, and there its {@code onClose} outlives the undeploy hook's grace: the hook waits
     * for it rather than report an event still running, and the undeploy goes on only at the context's own
     * deadline, not while {@code onClose} runs.
     */
    @Test
    void anUndeployWaitsForAnOnCloseRunningOnTheConnectionsThread() throws Exception {
        probe.holdText = true;
        probe.noEcho = true;
        probe.holdClose = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port);
             var takeoverLog = LogCapture.of(TAKEOVER_LOGGER);
             var contextLog = LogCapture.of(VidocqServletContext.class.getName())) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            client.send(clientText(HELD));
            assertTrue(probe.textEntered.tryAcquire(5, TimeUnit.SECONDS), "onText");
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            client.skipPast(STOPPING);
            assertFalse(undeploy.join(Duration.ofMillis(300)), "the undeploy did not wait for the event in progress");
            probe.textGate.countDown();
            assertTrue(probe.closed.await(5, TimeUnit.SECONDS), "onClose");
            assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy did not return");
            assertFalse(probe.closeThread.get().startsWith(HOOK_THREAD), probe.closeThread.get());
            assertEquals(List.of(), takeoverLog.warnings());
            assertEquals(1, contextLog.warnings().size(), contextLog.warnings().toString());
        } finally {
            probe.closeGate.countDown();
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", HELD_EVENT, "close", "destroy"), probe.events);
        assertClosedByTheUndeploy();
    }

    /**
     * A closing handshake under way ends on the connection's thread when the peer answers, and there its
     * {@code onClose} outlives the undeploy hook's grace: the hook waits for it rather than report the
     * handshake as still under way.
     */
    @Test
    void anUndeployWaitsForTheOnCloseOfAClosingHandshakeThatEndsLate() throws Exception {
        probe.closeOnOpen = true;
        probe.holdClose = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port);
             var takeoverLog = LogCapture.of(TAKEOVER_LOGGER);
             var contextLog = LogCapture.of(VidocqServletContext.class.getName())) {
            client.send(handshake("/ws"));
            client.readHead();
            client.readUntil("done");
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertFalse(undeploy.join(Duration.ofMillis(300)), "the undeploy did not wait for the closing handshake");
            client.send(new byte[] {(byte) 0x88, (byte) 0x82, 0, 0, 0, 0, 0x03, (byte) 0xE8});
            assertTrue(probe.closed.await(5, TimeUnit.SECONDS), "onClose");
            assertTrue(undeploy.join(Duration.ofSeconds(10)), "the undeploy did not return");
            assertFalse(probe.closeThread.get().startsWith(HOOK_THREAD), probe.closeThread.get());
            assertEquals(List.of(), takeoverLog.warnings());
            assertEquals(1, contextLog.warnings().size(), contextLog.warnings().toString());
        } finally {
            probe.closeGate.countDown();
        }
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", "close", "destroy"), probe.events);
        assertEquals(1000, probe.closeCode.get());
    }

    /**
     * A send blocked on a peer that does not read holds chappe's write lock, which the 1001 Close needs: the
     * undeploy does not wait for that send past its grace, and still ends the takeover with its close (1006),
     * once (BUG-20261115-01).
     */
    @Test
    void anUndeployDoesNotWaitForASendBlockedOnThePeer() throws Exception {
        probe.blockASend = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            awaitState(probe.sender.get(), Thread.State.WAITING);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertTrue(undeploy.join(Duration.ofSeconds(5)), WAITED_FOR_THE_BLOCKED_SEND);
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertClosedByTheUndeploy();
            assertEquals(1, probe.destroyCalls.get());
        }
    }

    /**
     * Past its grace, the undeploy cuts a connection whose 1001 Close a send blocked on the peer holds back
     * (BUG-20261115-01, CHAPPE-060): the peer reads the end of the connection, with no Close frame, the
     * application's blocked send fails with an {@code IOException} that says so, and the listener hears the
     * undeploy's close (1006), once, with no {@code onError}.
     */
    @Test
    void anUndeployCutsAConnectionWhoseCloseASendHoldsBack() throws Exception {
        probe.blockASend = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port); var log = LogCapture.of(TAKEOVER_LOGGER)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            awaitState(probe.sender.get(), Thread.State.WAITING);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertTrue(undeploy.join(Duration.ofSeconds(5)), WAITED_FOR_THE_BLOCKED_SEND);
            assertEndedBeforeTheUndeployWentOn();
            assertCutUnder(probe.sendFailure.get(5, TimeUnit.SECONDS));
            assertTrue(client.readHead().startsWith("HTTP/1.1 101"));
            assertNoCloseFrame(client.readToEndWithin(Duration.ofSeconds(5), TAIL));
            assertTrue(log.warnings().stream().anyMatch(w -> w.contains("cut")), log.warnings().toString());
        }
    }

    /**
     * A send blocked in the listener's {@code onOpen}, which rethrows its failure: the undeploy cuts the
     * connection, {@code onOpen} fails, and the listener still hears the undeploy's close, not the close of a
     * failed {@code onOpen}, with no {@code onError}: the failure is the undeploy's own cut.
     */
    @Test
    void aSendBlockedInOnOpenIsCutAndTheListenerHearsTheUndeploysClose() throws Exception {
        probe.blockASendInOnOpen = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            awaitState(probe.sender.get(), Thread.State.WAITING);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertTrue(undeploy.join(Duration.ofSeconds(5)), WAITED_FOR_THE_BLOCKED_SEND);
            assertEndedBeforeTheUndeployWentOn();
            assertCutUnder(probe.sendFailure.get(5, TimeUnit.SECONDS));
            assertTrue(client.readHead().startsWith("HTTP/1.1 101"));
            assertNoCloseFrame(client.readToEndWithin(Duration.ofSeconds(5), TAIL));
        }
    }

    /**
     * An {@code onOpen} that fails on its own while the undeploy waits for it, its Close already sent and
     * nothing cut, keeps what any failed {@code onOpen} gets: its {@code onError}, then its close (1006,
     * "onOpen failed"). The undeploy leaves that end to chappe's {@code onError}, which always follows a failed
     * {@code onOpen}.
     */
    @Test
    void anOnOpenThatFailsOnItsOwnDuringTheUndeployKeepsItsErrorAndItsClose() throws Exception {
        probe.failOpenOnceReleased = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertTrue(client.readHead().startsWith("HTTP/1.1 101"));
            // The undeploy's Close went out: the undeploy began while onOpen still runs.
            client.skipPast(STOPPING);
            probe.openGate.countDown();
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertEquals(List.of("open", "error", "close", "destroy"), probe.events);
            assertClosedOnAFailedOnOpen();
            assertEquals(1, probe.destroyCalls.get());
        }
    }

    /**
     * An {@code onOpen} that fails on its own after the undeploy cut the connection, its failure not an I/O
     * one, keeps its {@code onError} and its close: the undeploy's close is only for the failure the cut caused.
     */
    @Test
    void anOnOpenThatFailsOnItsOwnAfterTheCutKeepsItsErrorAndItsClose() throws Exception {
        probe.blockASend = true;
        probe.failOpenOnceReleased = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            awaitState(probe.sender.get(), Thread.State.WAITING);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertCutUnder(probe.sendFailure.get(5, TimeUnit.SECONDS));
            probe.openGate.countDown();
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertEquals(List.of("open", "error", "close", "destroy"), probe.events);
            assertClosedOnAFailedOnOpen();
            assertEquals(1, probe.destroyCalls.get());
        }
    }

    /**
     * An {@code onOpen} that fails on its own with an {@code IOException} once the undeploy closed the
     * connection, nothing cut: the failure is not reported (BUG-20261114-07), and the takeover ends with the
     * close of a failed {@code onOpen}.
     */
    @Test
    void anIoFailureOfOnOpenDuringTheUndeployIsNotReportedAndEndsWithItsClose() throws Exception {
        probe.failOpenOnceReleased = true;
        probe.failOpenWithIo = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertTrue(client.readHead().startsWith("HTTP/1.1 101"));
            client.skipPast(STOPPING);
            probe.openGate.countDown();
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertClosedOnAFailedOnOpen();
            assertEquals(1, probe.destroyCalls.get());
        }
    }

    /**
     * The {@code onError} that follows a failed {@code onOpen} is certain to come and ends the takeover: the
     * undeploy, which took the lock of the listener's events before it, waits for it past its own 2 s grace,
     * within the context's 3 s, so {@code onClose} and {@code destroy()} still run before the undeploy goes on.
     * Chappe's thread is held in the WARNING it logs for the failure, after {@code onOpen} released the lock
     * and before its {@code onError} takes it, so the undeploy's hook takes it first.
     */
    @Test
    void theUndeployTakingTheLockFirstWaitsPastItsGraceForTheOnErrorOfAFailedOnOpen() throws Exception {
        probe.failOpenOnceReleased = true;
        var chappeHeld = new CountDownLatch(1);
        var releaseChappe = new CountDownLatch(1);
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port);
                var _ = LogCapture.holding(CHAPPE_CONNECTION_LOGGER, ON_OPEN_FAILED_ON_ITS_OWN, chappeHeld,
                        releaseChappe)) {
            client.send(handshake("/ws"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            assertTrue(client.readHead().startsWith("HTTP/1.1 101"));
            client.skipPast(STOPPING);
            probe.openGate.countDown();
            assertTrue(chappeHeld.await(5, TimeUnit.SECONDS), "chappe never logged the failure of onOpen");
            // 2.2 s: past the hook's own grace (2 s), before the context's (3 s).
            assertFalse(undeploy.join(Duration.ofMillis(2_200)), "the undeploy did not wait for onError");
            assertEquals(List.of("open"), probe.events);
            releaseChappe.countDown();
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertEquals(List.of("open", "error", "close", "destroy"), probe.events);
            assertClosedOnAFailedOnOpen();
            assertEquals(1, probe.destroyCalls.get());
        } finally {
            releaseChappe.countDown();
        }
    }

    /**
     * The {@code onError} that follows a failed {@code onOpen} already holds the lock of the listener's events
     * when the undeploy comes: the undeploy still waits for it past its own 2 s grace, within the context's
     * 3 s, so {@code onClose} and {@code destroy()} run before it goes on.
     */
    @Test
    void theUndeployWaitsPastItsGraceForAnOnErrorThatHoldsTheLockFirst() throws Exception {
        probe.failOpenOnceReleased = true;
        probe.openGate.countDown();
        probe.holdError = true;
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(probe.errorHeld.await(5, TimeUnit.SECONDS), "onError never ran");
            Thread undeploy = Thread.ofVirtual().start(this::closeConnections);
            // 2.2 s: past the hook's own grace (2 s), before the context's (3 s).
            assertFalse(undeploy.join(Duration.ofMillis(2_200)),
                    () -> "the undeploy went on while onError still ran, with " + probe.events);
            probe.errorGate.countDown();
            assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
            assertEquals(List.of("open", "error", "close", "destroy"), probe.events);
            assertClosedOnAFailedOnOpen();
            assertEquals(1, probe.destroyCalls.get());
        }
    }

    /** The cut takeover ended with the undeploy's 1006, once, before the undeploy went on. */
    private void assertEndedBeforeTheUndeployWentOn() {
        assertEquals(List.of("open", "close", "destroy"), probe.events);
        assertClosedByTheUndeploy();
        assertEquals(1, probe.destroyCalls.get());
    }

    /**
     * The listener heard the close of the undeploy, which the container initiated: 1006 with its reason,
     * whatever went on the wire (Jakarta WebSocket 2.2 section 2.1.5, BUG-20261115-08).
     */
    private void assertClosedByTheUndeploy() {
        assertClosedByTheContainer(STOPPING_REASON);
    }

    /** The listener heard the close of a failed {@code onOpen}, which the container initiated: 1006. */
    private void assertClosedOnAFailedOnOpen() {
        assertClosedByTheContainer(ON_OPEN_FAILED);
    }

    private void assertClosedByTheContainer(String reason) {
        assertEquals(1006, probe.closeCode.get(), probe.closeReason::get);
        assertEquals(reason, probe.closeReason.get());
    }

    /** The code of the Close frame a raw client read after the 101, {@code received} being all it read. */
    private static int closeFrameCode(byte[] received) {
        assertTrue(received.length >= 4, () -> "no close frame with a code: " + java.util.HexFormat.of().formatHex(received));
        assertEquals(0x88, received[0] & 0xFF, "a close frame");
        return ((received[2] & 0xFF) << 8) | (received[3] & 0xFF);
    }

    /**
     * No Close frame, whatever its code, followed the stalled send: its payload is all zeros, and every Close
     * frame has a non-zero opcode and code, so the last bytes before the end of the connection are zeros.
     */
    private static void assertNoCloseFrame(byte[] tail) {
        assertEquals(TAIL, tail.length, "the connection ended before the stalled send had filled the buffers");
        for (byte b : tail) {
            assertEquals(0, b, () -> "a frame followed the stalled send: " + java.util.HexFormat.of().formatHex(tail));
        }
    }

    /** The failure of a send the undeploy's cut ended: an {@code IOException} that says so. */
    private static void assertCutUnder(IOException failure) {
        assertNotNull(failure, "the send was written: the peer read it");
        assertTrue(String.valueOf(failure.getMessage()).contains("was cut"), failure.toString());
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
        closeConnections();
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
            assertDoesNotThrow(this::closeConnections);
            assertEquals(List.of("open", "close", "destroy"), probe.events);
            assertTrue(log.thrown().stream().anyMatch(ExceptionInInitializerError.class::isInstance),
                    log.thrown().toString());
        }
    }

    /**
     * Once the undeploy began, a WebSocket handshake is a request like any other: it gets 503 and never
     * reaches the application's filters and servlets, so no takeover starts (BUG-20261115-02); it is told
     * to retry once the undeploy's bound passed (BUG-20261115-06).
     */
    @Test
    void aHandshakeOnceTheUndeployBeganGets503() throws Exception {
        start(new VidocqServletContext("/"));
        context.refuseRequests();
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            String head = client.readHead();
            assertTrue(head.startsWith("HTTP/1.1 503 "), head);
            assertTrue(head.toLowerCase(java.util.Locale.ROOT).contains(
                    "\r\nretry-after: " + VidocqServletContext.UNDEPLOY_RETRY_AFTER_SECONDS + "\r\n"), head);
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
        assertEquals("bye", probe.closeReason.get());
        assertEquals(List.of("open", "text:hi", "close", "destroy"), probe.events);
        assertEquals(1, probe.destroyCalls.get());
    }

    /**
     * A protocol violation of the peer, here a text message that is not UTF-8: chappe closes with 1007 and
     * reports no code of the peer's (1005); the listener hears its {@code onError}, then 1006 with a reason
     * naming the violation, since this side closed (Jakarta WebSocket 2.2 section 2.1.5, BUG-20261115-08).
     */
    @Test
    void aProtocolErrorOfThePeerClosesWith1007AndReports1006() throws Exception {
        start(new VidocqServletContext("/"));
        try (var client = new RawClient(port)) {
            client.send(handshake("/ws"));
            assertTrue(client.readHead().startsWith("HTTP/1.1 101"));
            assertTrue(probe.opened.await(5, TimeUnit.SECONDS), ON_OPEN);
            client.send(clientFrame(0x81, new byte[] {(byte) 0xC3, 0x28}));

            assertEquals(1007, closeFrameCode(client.readAllBytes()));
            assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("open", "error", "close", "destroy"), probe.events);
            assertClosedByTheContainer("protocol error: Invalid UTF-8 in TEXT frame");
        }
    }

    /**
     * An {@code Error} from the listener's {@code onText}: chappe reports it, closes with 1011 and reports no
     * code of the peer's (1005); the listener hears 1006 with a reason naming the failure (BUG-20261115-08).
     */
    @Test
    void anErrorFromACallbackClosesWith1011AndReports1006() throws Exception {
        probe.failTextWithError = true;
        start(new VidocqServletContext("/"));
        var closedByServer = new CompletableFuture<Integer>();
        WebSocket ws = connect(new TextListener(new CompletableFuture<>(), closedByServer));

        ws.sendText("hi", true).get(5, TimeUnit.SECONDS);

        assertEquals(1011, closedByServer.get(5, TimeUnit.SECONDS));
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("open", "text:hi", "error", "close", "destroy"), probe.events);
        assertClosedByTheContainer("callback failed: java.lang.ExceptionInInitializerError: onText failed");
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
        assertClosedOnAFailedOnOpen();
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
        assertClosedOnAFailedOnOpen();
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
        closeConnections();
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

    /** A masked client text frame (RFC 6455 section 5.2) of fewer than 126 bytes. */
    private static byte[] clientText(String text) {
        return clientFrame(0x81, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A masked client frame (RFC 6455 section 5.2) whose first byte is {@code head}, of fewer than 126 bytes. */
    private static byte[] clientFrame(int head, byte[] payload) {
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        byte[] frame = new byte[2 + mask.length + payload.length];
        frame[0] = (byte) head;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, mask.length);
        for (int i = 0; i < payload.length; i++) {
            frame[2 + mask.length + i] = (byte) (payload[i] ^ mask[i % mask.length]);
        }
        return frame;
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

    /** Undeploys the connections of {@link #context}, the deadline counted from now. */
    private void closeConnections() {
        context.closeUpgradedConnections(System.nanoTime());
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
                    if (probe.blockASend) probe.sender.set(Thread.ofVirtual().start(() -> sendOutsideAnEvent(p)));
                    if (probe.blockASendInOnOpen) probe.sender.set(Thread.currentThread());
                    probe.opened.countDown();
                    if (probe.closeOnOpen) p.close(1000, "done");
                    if (probe.blockASendInOnOpen) sendWhatThePeerNeverReads(p);
                    if (probe.failOpenOnceReleased) {
                        awaitGate(probe.openGate);
                        if (probe.failOpenWithIo) throw new IOException(ON_OPEN_FAILED_ON_ITS_OWN);
                        throw new IllegalStateException(ON_OPEN_FAILED_ON_ITS_OWN);
                    }
                }

                @Override
                public void onText(String text) throws IOException {
                    probe.events.add("text:" + text);
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    if (probe.failTextWithError) throw new ExceptionInInitializerError("onText failed");
                    if (probe.holdText) {
                        probe.textEntered.release();
                        awaitGate(probe.textGate);
                    }
                    if (!probe.noEcho) peer.get().sendText(text);
                }

                @Override
                public void onError(Throwable error) {
                    probe.events.add("error");
                    if (probe.holdError) {
                        probe.errorHeld.countDown();
                        awaitGate(probe.errorGate);
                    }
                }

                @Override
                public void onClose(int code, String reason) {
                    var p = peer.get();
                    probe.openAtClose.set(p != null && p.isOpen());
                    probe.closeThread.set(Thread.currentThread().getName());
                    probe.events.add("close");
                    probe.loaders.add(Thread.currentThread().getContextClassLoader());
                    probe.closeCode.set(code);
                    probe.closeReason.set(reason);
                    probe.closed.countDown();
                    if (probe.holdClose) awaitGate(probe.closeGate);
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

        /**
         * Holds chappe's write lock for as long as the peer does not read; records how the send ended, and
         * rethrows its failure.
         */
        private void sendWhatThePeerNeverReads(WebSocketPeer p) throws IOException {
            try {
                p.sendBinary(ByteBuffer.allocate(BLOCKING_SEND_BYTES));
                probe.sendFailure.complete(null);
            } catch (IOException e) {
                probe.sendFailure.complete(e);
                throw e;
            }
        }

        /** {@link #sendWhatThePeerNeverReads} on a thread of the application's own, outside any event. */
        private void sendOutsideAnEvent(WebSocketPeer p) {
            try {
                sendWhatThePeerNeverReads(p);
            } catch (IOException _) {
                // recorded in the probe: the connection closed under the send
            }
        }

        private static void awaitGate(CountDownLatch gate) {
            try {
                if (!gate.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("the gate never opened");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
