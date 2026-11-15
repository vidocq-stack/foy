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
package io.vidocq.foy.websocket.it;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour of established sessions on a real Foy and Chappe: undeploy, a connection left idle
 * (the Vaadin "page idle for five minutes" case), a peer that vanishes, ordered asynchronous sends and
 * binary messages (WEBSOCKET.md sections 5.4 and 5.5), with the JDK client or a raw socket.
 */
@Timeout(60)
class SessionEndToEndTest {

    private static final long WAIT = 5;
    private static final int BURST = 500;
    /** Far more than the socket buffers hold: to a peer that does not read, the send blocks. */
    private static final int STALLED_SEND_BYTES = 32 * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** The reason of an undeploy's close, on the wire and in {@code onClose}. */
    private static final String STOPPING = "the application is stopping";
    private static final String ON_OPEN_FAILED = "onOpen failed";
    private static final String LEAVING = "leaving";

    /** The close reasons {@code Endpoint.onClose} received, in order. */
    static final BlockingQueue<CloseReason> CLOSES = new LinkedBlockingQueue<>();
    /** The errors {@code Endpoint.onError} received, in order. */
    static final BlockingQueue<Throwable> ERRORS = new LinkedBlockingQueue<>();
    /** {@code Endpoint.onClose} and the application's {@code contextDestroyed}, in the order they ran. */
    static final List<String> LIFECYCLE = new CopyOnWriteArrayList<>();
    /** The sessions the endpoints opened, in order. */
    static final BlockingQueue<Session> SESSIONS = new LinkedBlockingQueue<>();
    /** The results of the asynchronous sends of {@link BurstEndpoint}. */
    static final BlockingQueue<SendResult> RESULTS = new LinkedBlockingQueue<>();
    /** How the sends of {@link StalledSendsEndpoint} ended: a failure each, or an error if one was written. */
    static final BlockingQueue<Throwable> SEND_OUTCOMES = new LinkedBlockingQueue<>();
    /** The thread of the blocking send of {@link StalledSendsEndpoint}. */
    static final AtomicReference<Thread> BLOCKING_SENDER = new AtomicReference<>();
    /** What the {@code onOpen} of the held endpoints waits for before it fails. */
    static final AtomicReference<CountDownLatch> OPEN_GATE = new AtomicReference<>(new CountDownLatch(1));

    private WebSocketTestServer server;

    @BeforeEach
    void reset() {
        CLOSES.clear();
        ERRORS.clear();
        LIFECYCLE.clear();
        SESSIONS.clear();
        RESULTS.clear();
        SEND_OUTCOMES.clear();
        BLOCKING_SENDER.set(null);
        OPEN_GATE.set(new CountDownLatch(1));
    }

    @AfterEach
    void stop() {
        OPEN_GATE.get().countDown();
        if (server != null) {
            server.close();
        }
    }

    // ---------------------------------------------------------------- endpoints

    /** Echoes text and binary messages, and records its session, its errors and its close reason. */
    public static class EchoEndpoint extends Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            SESSIONS.add(session);
            session.addMessageHandler(String.class, text -> sendText(session, text));
            session.addMessageHandler(ByteBuffer.class, bytes -> sendBinary(session, bytes));
        }

        @Override
        public void onError(Session session, Throwable error) {
            ERRORS.add(error);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            CLOSES.add(closeReason);
            LIFECYCLE.add("onClose");
        }
    }

    /** Closes a session that has been silent for half a second. */
    public static final class ShortIdleEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            super.onOpen(session, config);
            session.setMaxIdleTimeout(500);
        }
    }

    /** Fails in {@code onOpen} with an {@code Error}, as an endpoint whose static initializer failed. */
    public static final class BrokenEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            super.onOpen(session, config);
            throw new ExceptionInInitializerError("static initializer failed");
        }
    }

    /** Fails in {@code onOpen} with an exception, which the endpoint hears in {@code onError}. */
    public static final class RefusingEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            super.onOpen(session, config);
            throw new IllegalStateException("refused");
        }
    }

    /** As {@link BrokenEndpoint}, once {@link #OPEN_GATE} opened. */
    public static final class HeldBrokenEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            super.onOpen(session, config);
            awaitOpenGate();
            throw new ExceptionInInitializerError("static initializer failed");
        }
    }

    /** As {@link RefusingEndpoint}, once {@link #OPEN_GATE} opened. */
    public static final class HeldRefusingEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            super.onOpen(session, config);
            awaitOpenGate();
            throw new IllegalStateException("refused");
        }
    }

    /** Takes text messages of 4 characters at most. */
    public static final class SmallLimitEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            SESSIONS.add(session);
            session.setMaxTextMessageBufferSize(4);
        }
    }

    /** Fails on every message with an {@code Error}, which the session does not catch. */
    public static final class FailingHandlerEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            SESSIONS.add(session);
            session.addMessageHandler(String.class, text -> {
                throw new ExceptionInInitializerError("handler failed");
            });
        }
    }

    /** Closes its session itself, with 1001, when a message arrives. */
    public static final class LeavingEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            SESSIONS.add(session);
            session.addMessageHandler(String.class, text -> {
                try {
                    session.close(new CloseReason(CloseCodes.GOING_AWAY, LEAVING));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /** Answers a message with {@value #BURST} asynchronous sends, issued without waiting for one another. */
    public static final class BurstEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            SESSIONS.add(session);
            session.addMessageHandler(String.class, text -> {
                for (int i = 0; i < BURST; i++) {
                    session.getAsyncRemote().sendText(Integer.toString(i), RESULTS::add);
                }
            });
        }
    }

    /**
     * Starts an asynchronous send, then a blocking one on a thread of its own, each far larger than the socket
     * buffers, to a peer that never reads: one holds the transport's write lock, the other waits behind it.
     */
    public static final class StalledSendsEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            SESSIONS.add(session);
            session.getAsyncRemote().sendBinary(ByteBuffer.allocate(STALLED_SEND_BYTES), result ->
                    SEND_OUTCOMES.add(result.isOK() ? new AssertionError("the asynchronous send was written")
                            : result.getException()));
            BLOCKING_SENDER.set(Thread.ofVirtual().start(() -> {
                try {
                    session.getBasicRemote().sendBinary(ByteBuffer.allocate(STALLED_SEND_BYTES));
                    SEND_OUTCOMES.add(new AssertionError("the blocking send was written"));
                } catch (IOException e) {
                    SEND_OUTCOMES.add(e);
                }
            }));
        }
    }

    // ---------------------------------------------------------------- tests

    /**
     * The peer reads the undeploy's 1001, the endpoint hears 1006: the container closed the session (Jakarta
     * WebSocket 2.2 section 2.1.5), and the reason says why (BUG-20261115-08).
     */
    @Test
    void undeployClosesSessionsWith1001AndReports1006() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        var client = new Client();
        client.connect();
        assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

        server.close();

        assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close frame");
        assertEquals(CloseCodes.GOING_AWAY.getCode(), client.closeCode);
        assertEquals(STOPPING, client.closeReason);
        assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), STOPPING);
    }

    /**
     * The undeploy closed first, so {@code onClose} reports its 1006 even when the server stops before the
     * peer answers the Close: the transport then sees a cut connection, the endpoint the undeploy's reason
     * (BUG-20261114-05). The raw client never answers, which makes deterministic what a slow JDK client
     * does on a loaded machine.
     */
    @Test
    void undeployReports1006WhenTheServerStopsBeforeThePeerAnswers() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        try (var _ = RawHandshake.connect(server.port(), "/echo")) {
            assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

            server.close();

            assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), STOPPING);
            assertTrue(CLOSES.isEmpty(), "Endpoint.onClose ran more than once");
        }
    }

    /**
     * The server stops right after the undeploy, before the peer answers its Close: the endpoint hears
     * {@code onClose} alone. The undeploy ended the session before the stop (BUG-20261114-08), so the read
     * the stop cuts finds it closed; the case where the stop reaches a session still open, which
     * BUG-20261114-07 fixed, is pinned in foy-core by
     * {@code WebSocketTakeoverEndToEndTest.aServerStopWhileTheUndeployWaitsForItsCloseReportsNoError}.
     */
    @Test
    void aServerStopAfterAnUndeployIsNoErrorOfTheSession() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        try (var _ = RawHandshake.connect(server.port(), "/echo")) {
            assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

            server.close();

            assertNotNull(CLOSES.poll(WAIT, SECONDS), "Endpoint.onClose did not run");
            assertTrue(ERRORS.isEmpty(), () -> "Endpoint.onError ran: " + ERRORS);
        }
    }

    /**
     * An undeploy runs {@code onClose} before the application's {@code contextDestroyed}, without waiting for
     * the peer to answer its Close: the endpoint can still use what the application tears down there
     * (BUG-20261114-08). The raw client never answers.
     */
    @Test
    void undeployRunsOnCloseBeforeContextDestroyed() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        try (var _ = RawHandshake.connect(server.port(), "/echo")) {
            assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

            server.close();

            assertEquals(List.of("onClose", "contextDestroyed"), List.copyOf(LIFECYCLE));
        }
    }

    /**
     * Sends blocked on a peer that does not read hold the undeploy's Close back: past its grace the undeploy
     * cuts the connection (BUG-20261115-01). The blocking send throws, and the asynchronous one reports, an
     * {@code IOException} that says the connection was cut; the endpoint hears the undeploy's 1006, and no
     * {@code onError}.
     */
    @Test
    void anUndeployCutsTheSendsBlockedOnAPeerThatDoesNotRead() throws Exception {
        server = WebSocketTestServer.start(endpoint(StalledSendsEndpoint.class));
        try (var _ = RawHandshake.connect(server.port(), "/echo")) {
            assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");
            awaitWaiting(BLOCKING_SENDER);

            server.close();

            for (String send : List.of("first", "second")) {
                Throwable outcome = SEND_OUTCOMES.poll(WAIT, SECONDS);
                assertNotNull(outcome, () -> "the " + send + " send stayed blocked");
                assertInstanceOf(IOException.class, outcome);
                assertTrue(String.valueOf(outcome.getMessage()).contains("was cut"), outcome::toString);
            }
            assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), STOPPING);
            assertTrue(ERRORS.isEmpty(), () -> "Endpoint.onError ran: " + ERRORS);
            // The cut session ended before the undeploy went on.
            assertEquals(List.of("onClose", "contextDestroyed"), List.copyOf(LIFECYCLE));
        }
    }

    @Test
    void anErrorFromOnOpenClosesWith1011Reports1006AndLeavesNoOpenSession() throws Exception {
        server = WebSocketTestServer.start(endpoint(BrokenEndpoint.class));
        var client = new Client();
        client.connect();
        Session session = SESSIONS.poll(WAIT, SECONDS);
        assertNotNull(session, "the endpoint did not open");

        assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close");
        assertEquals(CloseCodes.UNEXPECTED_CONDITION.getCode(), client.closeCode);
        assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), ON_OPEN_FAILED);
        assertEquals(Set.of(), session.getOpenSessions());
        assertTrue(CLOSES.isEmpty(), "Endpoint.onClose ran more than once");
    }

    @Test
    void anExceptionFromOnOpenIsReportedThenClosesWith1011AndReports1006() throws Exception {
        server = WebSocketTestServer.start(endpoint(RefusingEndpoint.class));
        var client = new Client();
        client.connect();

        assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close");
        assertEquals(CloseCodes.UNEXPECTED_CONDITION.getCode(), client.closeCode);
        assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), ON_OPEN_FAILED);
        assertInstanceOf(IllegalStateException.class, ERRORS.poll(WAIT, SECONDS));
    }

    /**
     * An {@code onOpen} that throws an {@code Error} once the undeploy's 1001 went out: the 1011 of a failed
     * {@code onOpen} never goes out, and the endpoint hears the undeploy's close, the one the peer read
     * (BUG-20261115-08).
     */
    @Test
    void anErrorFromOnOpenDuringTheUndeployReportsTheUndeploysClose() throws Exception {
        assertAFailedOnOpenHearsTheUndeploysClose(HeldBrokenEndpoint.class);
        assertTrue(ERRORS.isEmpty(), () -> "Endpoint.onError ran: " + ERRORS);
    }

    /** As {@link #anErrorFromOnOpenDuringTheUndeployReportsTheUndeploysClose}, with an exception it hears first. */
    @Test
    void anExceptionFromOnOpenDuringTheUndeployReportsTheUndeploysClose() throws Exception {
        assertAFailedOnOpenHearsTheUndeploysClose(HeldRefusingEndpoint.class);
        assertInstanceOf(IllegalStateException.class, ERRORS.poll(WAIT, SECONDS));
    }

    /** The peer reads 1009, the endpoint hears 1006 with the reason the peer read (BUG-20261115-08). */
    @Test
    void aMessageOverTheLimitClosesWith1009AndReports1006() throws Exception {
        server = WebSocketTestServer.start(endpoint(SmallLimitEndpoint.class));
        var client = new Client();
        WebSocket ws = client.connect();

        ws.sendText("hello", true).get(WAIT, SECONDS);

        assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close");
        assertEquals(CloseCodes.TOO_BIG.getCode(), client.closeCode);
        String reason = "text message of 5 characters, the maximum is 4";
        assertEquals(reason, client.closeReason);
        assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), reason);
    }

    /**
     * A protocol violation of the peer, here a text message that is not UTF-8: the transport closes with
     * 1007 and the endpoint hears 1006, with a reason naming the violation, after its {@code onError}.
     */
    @Test
    void aProtocolErrorOfThePeerClosesWith1007AndReports1006() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        try (Socket socket = RawHandshake.connect(server.port(), "/echo")) {
            assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

            // A final text frame of 2 bytes, masked with a zero key (RFC 6455 section 5.3): 0xC3 0x28 as sent.
            socket.getOutputStream().write(new byte[] {(byte) 0x81, (byte) 0x82, 0, 0, 0, 0, (byte) 0xC3, 0x28});
            socket.getOutputStream().flush();

            assertEquals(CloseCodes.NOT_CONSISTENT.getCode(),
                    RawHandshake.closeCodeAfterTheHead(socket.getInputStream().readAllBytes()));
            assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), "protocol error: Invalid UTF-8 in TEXT frame");
            assertNotNull(ERRORS.poll(WAIT, SECONDS), "Endpoint.onError did not run");
        }
    }

    /**
     * An {@code Error} from a message handler: the transport logs it, the endpoint hears it in {@code onError},
     * the transport closes with 1011, and {@code onClose} hears 1006 with a reason naming the {@code Error}
     * (BUG-20261115-08).
     */
    @Test
    void anErrorFromAMessageHandlerClosesWith1011AndReports1006() throws Exception {
        server = WebSocketTestServer.start(endpoint(FailingHandlerEndpoint.class));
        var client = new Client();
        WebSocket ws = client.connect();

        try (var warnings = new LogLines(LogLines.CHAPPE_CONNECTION, Level.WARNING)) {
            ws.sendText("fail", true).get(WAIT, SECONDS);

            assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close");
            assertEquals(List.of("WebSocket handler error"), warnings.messages());
        }
        assertEquals(CloseCodes.UNEXPECTED_CONDITION.getCode(), client.closeCode);
        assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS),
                "callback failed: java.lang.ExceptionInInitializerError: handler failed");
        assertInstanceOf(ExceptionInInitializerError.class, ERRORS.poll(WAIT, SECONDS));
    }

    /** A close the application initiates keeps its code, 1001 here, on the wire and in {@code onClose}. */
    @Test
    void anApplicationCloseKeepsItsCode() throws Exception {
        server = WebSocketTestServer.start(endpoint(LeavingEndpoint.class));
        var client = new Client();
        WebSocket ws = client.connect();

        ws.sendText("leave", true).get(WAIT, SECONDS);

        assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close");
        assertEquals(CloseCodes.GOING_AWAY.getCode(), client.closeCode);
        CloseReason reason = CLOSES.poll(WAIT, SECONDS);
        assertNotNull(reason, "Endpoint.onClose did not run");
        assertEquals(CloseCodes.GOING_AWAY, reason.getCloseCode());
        assertEquals(LEAVING, reason.getReasonPhrase());
    }

    /** A close the peer initiates gives {@code onClose} the peer's code and reason. */
    @Test
    void aPeerCloseReportsThePeersCode() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        var client = new Client();
        WebSocket ws = client.connect();
        assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

        ws.sendClose(4000, "bye").get(WAIT, SECONDS);

        CloseReason reason = CLOSES.poll(WAIT, SECONDS);
        assertNotNull(reason, "Endpoint.onClose did not run");
        assertEquals(4000, reason.getCloseCode().getCode());
        assertEquals("bye", reason.getReasonPhrase());
    }

    @Test
    void anIdleConnectionOutlivesChappesIdleTimeout() throws Exception {
        server = WebSocketTestServer.builder(endpoint(EchoEndpoint.class)).idleTimeout(Duration.ofSeconds(1)).start();
        var client = new Client();
        WebSocket ws = client.connect();

        assertFalse(client.closed.await(3, SECONDS), "the connection was closed while idle");

        ws.sendText("still here", true).get(WAIT, SECONDS);
        assertEquals("still here", client.nextText());
    }

    /**
     * The peer that vanished never answers the idle timeout's 1001: the endpoint hears 1006, the container
     * having closed the session (Jakarta WebSocket 2.2 section 2.1.5, BUG-20261115-08).
     */
    @Test
    void aVanishedPeerIsCutByTheIdleTimeout() throws Exception {
        // The raw client never reads nor closes: the server's close frame is never answered, so the
        // connection ends when Chappe's close handshake gives up, after the read timeout (1 s here).
        server = WebSocketTestServer.builder(endpoint(ShortIdleEndpoint.class))
                .readTimeout(Duration.ofSeconds(1)).start();
        try (Socket socket = RawHandshake.connect(server.port(), "/echo")) {
            Session session = SESSIONS.poll(WAIT, SECONDS);
            assertNotNull(session, "the endpoint did not open");

            assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), "idle timeout");
            assertFalse(session.isOpen());

            assertEquals(CloseCodes.GOING_AWAY.getCode(),
                    RawHandshake.closeCodeAfterTheHead(socket.getInputStream().readAllBytes()));
            assertTrue(CLOSES.isEmpty(), "Endpoint.onClose ran more than once");
        }
    }

    @Test
    void asyncSendsArriveInOrder() throws Exception {
        server = WebSocketTestServer.start(endpoint(BurstEndpoint.class));
        var client = new Client();
        WebSocket ws = client.connect();

        ws.sendText("go", true).get(WAIT, SECONDS);

        for (int i = 0; i < BURST; i++) {
            assertEquals(Integer.toString(i), client.nextText());
        }
        for (int i = 0; i < BURST; i++) {
            SendResult result = RESULTS.poll(WAIT, SECONDS);
            assertNotNull(result, "send " + i + " reported no result");
            assertTrue(result.isOK(), "send " + i + " failed: " + result.getException());
        }
    }

    @Test
    void aBinaryEchoRoundTrips() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        var client = new Client();
        WebSocket ws = client.connect();
        byte[] payload = new byte[40_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }

        ws.sendBinary(ByteBuffer.wrap(payload), true).get(WAIT, SECONDS);

        byte[] echoed = client.binaries.poll(WAIT, SECONDS);
        assertNotNull(echoed, "no binary message within " + WAIT + " s");
        assertArrayEquals(payload, echoed);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Asserts that {@code reason} is the one of a close the container initiated: 1006 with {@code phrase},
     * whatever the code on the wire (Jakarta WebSocket 2.2 section 2.1.5).
     */
    private static void assertClosedByTheContainer(CloseReason reason, String phrase) {
        assertNotNull(reason, "Endpoint.onClose did not run");
        assertEquals(CloseCodes.CLOSED_ABNORMALLY, reason.getCloseCode(), reason::toString);
        assertEquals(phrase, reason.getReasonPhrase());
    }

    /**
     * Opens an {@code endpointClass} held in {@code onOpen}, undeploys it, and lets that {@code onOpen} fail once
     * the peer read the undeploy's 1001: the endpoint hears the undeploy's close, once.
     */
    private void assertAFailedOnOpenHearsTheUndeploysClose(Class<?> endpointClass) throws Exception {
        server = WebSocketTestServer.start(endpoint(endpointClass));
        var client = new Client();
        client.connect();
        assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");
        Thread undeploy = Thread.ofVirtual().start(server::close);

        assertTrue(client.closed.await(WAIT, SECONDS), "the undeploy's Close did not go out");
        assertEquals(CloseCodes.GOING_AWAY.getCode(), client.closeCode);
        OPEN_GATE.get().countDown();

        assertTrue(undeploy.join(Duration.ofSeconds(WAIT)), "the undeploy did not return");
        assertClosedByTheContainer(CLOSES.poll(WAIT, SECONDS), STOPPING);
        assertTrue(CLOSES.isEmpty(), "Endpoint.onClose ran more than once");
    }

    /** Waits for {@link #OPEN_GATE}, twice the tests' wait at most. */
    private static void awaitOpenGate() {
        try {
            if (!OPEN_GATE.get().await(2 * WAIT, SECONDS)) {
                throw new IllegalStateException("the gate never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ServletContextListener endpoint(Class<?> endpointClass) {
        return new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                ServletContext ctx = event.getServletContext();
                try {
                    ((ServerContainer) ctx.getAttribute(ServerContainer.class.getName()))
                            .addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/echo").build());
                } catch (DeploymentException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void contextDestroyed(ServletContextEvent event) {
                LIFECYCLE.add("contextDestroyed");
            }
        };
    }

    /**
     * Waits until the thread is started and seen waiting: blocked on a peer that does not read, in the socket
     * write or on the transport's write lock. One read of the state per check, and the assertion is on that
     * read: the park of a send in the socket write ends whenever the kernel takes more bytes (BUG-20261114-09).
     */
    private static void awaitWaiting(AtomicReference<Thread> thread) {
        long deadline = System.nanoTime() + SECONDS.toNanos(WAIT);
        Thread current = thread.get();
        Thread.State state = stateOf(current);
        while (state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            current = thread.get();
            state = stateOf(current);
        }
        assertNotNull(current, "the send never started");
        assertEquals(Thread.State.WAITING, state, current.getName());
    }

    /** The state of {@code thread}, read once, or {@code null} before it started. */
    private static Thread.State stateOf(Thread thread) {
        return thread == null ? null : thread.getState();
    }

    private static void sendText(Session session, String text) {
        try {
            session.getBasicRemote().sendText(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sendBinary(Session session, ByteBuffer bytes) {
        try {
            session.getBasicRemote().sendBinary(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A JDK client connection: the text and binary messages it receives, and its close. */
    private final class Client implements WebSocket.Listener {
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> binaries = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile int closeCode = -1;
        volatile String closeReason;
        private final StringBuilder partialText = new StringBuilder();
        private final ByteArrayOutputStream partialBinary = new ByteArrayOutputStream();

        WebSocket connect() throws Exception {
            return HTTP.newWebSocketBuilder().buildAsync(server.uri("ws", "/echo"), this).get(WAIT, SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                texts.add(partialText.toString());
                partialText.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partialBinary.writeBytes(chunk);
            if (last) {
                binaries.add(partialBinary.toByteArray());
                partialBinary.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            closeReason = reason;
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }

        String nextText() throws InterruptedException {
            String text = texts.poll(WAIT, SECONDS);
            assertNotNull(text, "no message within " + WAIT + " s");
            return text;
        }
    }
}
