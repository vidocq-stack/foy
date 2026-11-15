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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** The close reasons {@code Endpoint.onClose} received, in order. */
    static final BlockingQueue<CloseReason> CLOSES = new LinkedBlockingQueue<>();
    /** The errors {@code Endpoint.onError} received, in order. */
    static final BlockingQueue<Throwable> ERRORS = new LinkedBlockingQueue<>();
    /** The sessions the endpoints opened, in order. */
    static final BlockingQueue<Session> SESSIONS = new LinkedBlockingQueue<>();
    /** The results of the asynchronous sends of {@link BurstEndpoint}. */
    static final BlockingQueue<SendResult> RESULTS = new LinkedBlockingQueue<>();

    private WebSocketTestServer server;

    @BeforeEach
    void reset() {
        CLOSES.clear();
        ERRORS.clear();
        SESSIONS.clear();
        RESULTS.clear();
    }

    @AfterEach
    void stop() {
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

    // ---------------------------------------------------------------- tests

    @Test
    void undeployClosesSessionsWith1001() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        var client = new Client();
        client.connect();
        assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

        server.close();

        assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close frame");
        assertEquals(CloseCodes.GOING_AWAY.getCode(), client.closeCode);
        CloseReason reason = CLOSES.poll(WAIT, SECONDS);
        assertNotNull(reason, "Endpoint.onClose did not run");
        assertEquals(CloseCodes.GOING_AWAY, reason.getCloseCode());
    }

    /**
     * The undeploy closed first, so {@code onClose} reports its 1001 even when the server stops before the
     * peer answers the Close: the transport then sees a cut connection (1006), the endpoint does not
     * (BUG-20261114-05). The raw client never answers, which makes deterministic what a slow JDK client
     * does on a loaded machine.
     */
    @Test
    void undeployReports1001WhenTheServerStopsBeforeThePeerAnswers() throws Exception {
        server = WebSocketTestServer.start(endpoint(EchoEndpoint.class));
        try (var _ = RawHandshake.connect(server.port(), "/echo")) {
            assertNotNull(SESSIONS.poll(WAIT, SECONDS), "the endpoint did not open");

            server.close();

            CloseReason reason = CLOSES.poll(WAIT, SECONDS);
            assertNotNull(reason, "Endpoint.onClose did not run");
            assertEquals(CloseCodes.GOING_AWAY, reason.getCloseCode());
            assertTrue(CLOSES.isEmpty(), "Endpoint.onClose ran more than once");
        }
    }

    /**
     * The server stops right after the undeploy, before the peer answers its Close: the stop cuts the
     * transport's read, which is no error of the session, so the endpoint hears {@code onClose} alone
     * (BUG-20261114-07).
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

    @Test
    void anErrorFromOnOpenClosesWith1011AndLeavesNoOpenSession() throws Exception {
        server = WebSocketTestServer.start(endpoint(BrokenEndpoint.class));
        var client = new Client();
        client.connect();
        Session session = SESSIONS.poll(WAIT, SECONDS);
        assertNotNull(session, "the endpoint did not open");

        assertTrue(client.closed.await(WAIT, SECONDS), "the client saw no close");
        assertEquals(CloseCodes.UNEXPECTED_CONDITION.getCode(), client.closeCode);
        CloseReason reason = CLOSES.poll(WAIT, SECONDS);
        assertNotNull(reason, "Endpoint.onClose did not run");
        assertEquals(CloseCodes.UNEXPECTED_CONDITION, reason.getCloseCode());
        assertEquals(Set.of(), session.getOpenSessions());
        assertTrue(CLOSES.isEmpty(), "Endpoint.onClose ran more than once");
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

    @Test
    void aVanishedPeerIsCutByTheIdleTimeout() throws Exception {
        // The raw client never reads nor closes: the server's close frame is never answered, so the
        // connection ends when Chappe's close handshake gives up, after the read timeout (1 s here).
        server = WebSocketTestServer.builder(endpoint(ShortIdleEndpoint.class))
                .readTimeout(Duration.ofSeconds(1)).start();
        try (Socket socket = RawHandshake.connect(server.port(), "/echo")) {
            Session session = SESSIONS.poll(WAIT, SECONDS);
            assertNotNull(session, "the endpoint did not open");

            CloseReason reason = CLOSES.poll(WAIT, SECONDS);
            assertNotNull(reason, "the idle timeout did not close the session");
            assertEquals(CloseCodes.GOING_AWAY, reason.getCloseCode());
            assertFalse(session.isOpen());

            byte[] received = socket.getInputStream().readAllBytes();
            String text = new String(received, StandardCharsets.ISO_8859_1);
            int frameAt = text.indexOf("\r\n\r\n") + 4;
            assertTrue(frameAt > 4 && text.startsWith("HTTP/1.1 101"), text);
            assertEquals(0x88, received[frameAt] & 0xFF, "the server sent a close frame");
            assertEquals(CloseCodes.GOING_AWAY.getCode(),
                    ((received[frameAt + 2] & 0xFF) << 8) | (received[frameAt + 3] & 0xFF));
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
        };
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
