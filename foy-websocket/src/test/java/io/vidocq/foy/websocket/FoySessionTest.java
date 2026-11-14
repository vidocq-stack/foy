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
package io.vidocq.foy.websocket;

import io.vidocq.foy.websocket.TestSessions.Recorder;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EncodeException;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.RemoteEndpoint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoySessionTest {

    private static final long WAIT = 5;

    private final FakePeer peer = new FakePeer();
    private final List<String> received = new CopyOnWriteArrayList<>();

    @Test
    void aMessageOverTheLimitClosesWith1009() {
        var endpoint = new Recorder(session ->
                session.addMessageHandler(String.class, (MessageHandler.Whole<String>) received::add));
        FoySession session = TestSessions.open(endpoint, peer);

        session.onText("a".repeat(65_536));
        session.onText("a".repeat(65_537));

        assertEquals(65_536, session.getMaxTextMessageBufferSize(), "the default is 64 KiB");
        assertEquals(1, received.size(), "the message over the limit is not delivered");
        assertEquals(CloseCodes.TOO_BIG.getCode(), peer.closeCode());
    }

    @Test
    void aBinaryMessageOverTheLimitClosesWith1009() {
        var endpoint = new Recorder(session ->
                session.addMessageHandler(ByteBuffer.class, (MessageHandler.Whole<ByteBuffer>) data ->
                        received.add("binary")));
        FoySession session = TestSessions.open(endpoint, peer);
        session.setMaxBinaryMessageBufferSize(4);

        session.onBinary(ByteBuffer.allocate(5));

        assertEquals(List.of(), received);
        assertEquals(CloseCodes.TOO_BIG.getCode(), peer.closeCode());
    }

    @Test
    void aHandlerThatThrowsReportsAndKeepsTheSessionOpen() {
        var failure = new IllegalArgumentException("bad message");
        var endpoint = new Recorder(session ->
                session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text -> {
                    throw failure;
                }));
        FoySession session = TestSessions.open(endpoint, peer);

        session.onText("hello");

        assertEquals(List.of(failure), endpoint.errors);
        assertTrue(session.isOpen());
        assertEquals(-1, peer.closeCode(), "no close frame");
    }

    @Test
    void anOnOpenThatThrowsReportsThenClosesWith1011() {
        var failure = new IllegalStateException("cannot open");
        var endpoint = new Recorder(session -> {
            throw failure;
        });
        FoySession session = TestSessions.session(endpoint);

        session.onOpen(peer);
        session.onClose(CloseCodes.UNEXPECTED_CONDITION.getCode(), "");

        assertEquals(List.of(failure), endpoint.errors);
        assertEquals(CloseCodes.UNEXPECTED_CONDITION.getCode(), peer.closeCode());
        assertEquals(1, endpoint.closes.size());
        assertEquals(CloseCodes.UNEXPECTED_CONDITION, endpoint.closes.getFirst().getCloseCode());
    }

    /**
     * An {@code Error} from {@code onOpen} (a failed static initializer) propagates to the transport, but
     * only once the session closed with 1011: {@code onClose} ran, the session left the open sessions, its
     * timer stopped and its sends fail.
     */
    @Test
    void anOnOpenThatThrowsAnErrorClosesWith1011BeforeItPropagates() {
        var container = new FoyServerContainer(OriginPolicy.parse(null), null);
        container.setDefaultMaxSessionIdleTimeout(60_000);
        var endpoint = new Recorder(session -> {
            throw new ExceptionInInitializerError("static initializer failed");
        });
        FoySession session = TestSessions.session(container, endpoint);

        assertThrows(ExceptionInInitializerError.class, () -> session.onOpen(peer));

        assertEquals(CloseCodes.UNEXPECTED_CONDITION.getCode(), peer.closeCode());
        assertEquals(1, endpoint.closes.size());
        assertEquals(CloseCodes.UNEXPECTED_CONDITION, endpoint.closes.getFirst().getCloseCode());
        assertEquals(Set.of(), session.getOpenSessions());
        assertFalse(session.isOpen());
        var send = session.getAsyncRemote().sendText("after");
        assertInstanceOf(IOException.class,
                assertThrows(ExecutionException.class, () -> send.get(WAIT, SECONDS)).getCause());
    }

    /**
     * Once an {@code Error} from {@code onOpen} closed the session, a transport error that comes later
     * (a transport that reports the failed {@code onOpen}) never reaches the endpoint: {@code onError}
     * precedes {@code onClose}, never follows it.
     */
    @Test
    void noOnErrorAfterTheSessionClosedOnAnErrorFromOnOpen() {
        var endpoint = new Recorder(session -> {
            throw new ExceptionInInitializerError("static initializer failed");
        });
        FoySession session = TestSessions.session(endpoint);
        assertThrows(ExceptionInInitializerError.class, () -> session.onOpen(peer));

        session.onError(new IOException("reported by the transport after the close"));

        assertEquals(1, endpoint.closes.size());
        assertEquals(List.of(), endpoint.errors);
    }

    @Test
    void aLocalCloseReportsTheLocalReason() throws IOException {
        var endpoint = new Recorder();
        FoySession session = TestSessions.open(endpoint, peer);

        session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(4000), "bye"));
        session.onClose(CloseCodes.NORMAL_CLOSURE.getCode(), "");

        assertEquals(4000, peer.closeCode());
        assertEquals("bye", peer.closeReason());
        assertEquals(4000, endpoint.closes.getFirst().getCloseCode().getCode());
        assertEquals("bye", endpoint.closes.getFirst().getReasonPhrase());
    }

    @Test
    void thePeerCloseReportsThePeerCode() {
        var endpoint = new Recorder();
        FoySession session = TestSessions.open(endpoint, peer);

        session.onClose(CloseCodes.NO_STATUS_CODE.getCode(), "");

        assertEquals(CloseCodes.NO_STATUS_CODE, endpoint.closes.getFirst().getCloseCode());
        assertFalse(session.isOpen());
    }

    @Test
    void aCloseCodeInvalidOnTheWireIsSentAs1000() throws IOException {
        FoySession session = TestSessions.open(new Recorder(), peer);

        session.close(new CloseReason(CloseCodes.CLOSED_ABNORMALLY, "gone"));

        assertEquals(CloseCodes.NORMAL_CLOSURE.getCode(), peer.closeCode());
        assertEquals("gone", peer.closeReason());
    }

    @Test
    void onCloseFiresOnce() throws InterruptedException {
        var endpoint = new Recorder();
        FoySession session = TestSessions.open(endpoint, peer);
        assertEquals(Set.of(session), session.getOpenSessions());

        session.onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), "");
        session.onClose(CloseCodes.NORMAL_CLOSURE.getCode(), "");

        assertTrue(endpoint.closed.await(WAIT, SECONDS));
        assertEquals(1, endpoint.closes.size());
        assertEquals(CloseCodes.CLOSED_ABNORMALLY, endpoint.closes.getFirst().getCloseCode());
        assertEquals(Set.of(), session.getOpenSessions(), "the closed session left the open sessions");
    }

    @Test
    void partialSendsAreSentWholeOnTheLastPart() throws IOException {
        FoySession session = TestSessions.open(new Recorder(), peer);
        RemoteEndpoint.Basic basic = session.getBasicRemote();

        basic.sendText("hel", false);
        basic.sendBinary(ByteBuffer.wrap(new byte[] {1, 2}), false);
        assertEquals(List.of(), peer.messages, "nothing is sent before the last part");
        basic.sendText("lo", true);
        basic.sendBinary(ByteBuffer.wrap(new byte[] {3}), true);
        try (Writer writer = basic.getSendWriter()) {
            writer.write("wri");
            writer.write("ter");
        }
        try (OutputStream stream = basic.getSendStream()) {
            stream.write(new byte[] {4, 5});
            stream.write(6);
        }

        assertEquals(4, peer.messages.size());
        assertEquals("hello", peer.messages.get(0));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) peer.messages.get(1));
        assertEquals("writer", peer.messages.get(2));
        assertArrayEquals(new byte[] {4, 5, 6}, (byte[]) peer.messages.get(3));
    }

    @Test
    void sendObjectSendsStringsBytesAndBoxedPrimitives() throws IOException, EncodeException {
        FoySession session = TestSessions.open(new Recorder(), peer);
        RemoteEndpoint.Basic basic = session.getBasicRemote();
        Object unknown = new Object();

        basic.sendObject("text");
        basic.sendObject(ByteBuffer.wrap(new byte[] {1}));
        basic.sendObject(new byte[] {2});
        basic.sendObject(42);
        basic.sendObject(true);

        assertEquals(List.of("text", "42", "true"), peer.texts());
        var thrown = assertThrows(EncodeException.class, () -> basic.sendObject(unknown));
        assertTrue(thrown.getMessage().contains("lot 2"), thrown.getMessage());
    }

    @Test
    void pingsAndPongsReachThePeerAndPongsTheHandler() throws IOException {
        var pongs = new CopyOnWriteArrayList<ByteBuffer>();
        FoySession session = TestSessions.open(new Recorder(s -> s.addMessageHandler(PongMessage.class,
                (MessageHandler.Whole<PongMessage>) pong -> pongs.add(pong.getApplicationData()))), peer);
        ByteBuffer tooLong = ByteBuffer.allocate(126);

        session.getBasicRemote().sendPing(StandardCharsets.UTF_8.encode("p1"));
        session.getAsyncRemote().sendPong(StandardCharsets.UTF_8.encode("p2"));
        session.onPing(ByteBuffer.wrap(new byte[] {1}));
        session.onPong(ByteBuffer.wrap(new byte[] {7}));

        assertEquals(List.of("ping:p1", "pong:p2"), peer.controls, "a ping is never answered by the session");
        assertEquals(List.of(ByteBuffer.wrap(new byte[] {7})), pongs);
        RemoteEndpoint.Basic basic = session.getBasicRemote();
        assertThrows(IllegalArgumentException.class, () -> basic.sendPing(tooLong));
    }

    @Test
    void theUserPropertiesCarryBothAddresses() {
        var endpoint = new Recorder();
        FoySession session = TestSessions.session(endpoint);
        Map<String, Object> registered = Map.copyOf(session.config().getUserProperties());

        session.onOpen(peer);

        Map<String, Object> properties = session.getUserProperties();
        assertEquals(FakePeer.REMOTE, properties.get("jakarta.websocket.endpoint.remoteAddress"));
        var local = assertInstanceOf(InetSocketAddress.class,
                properties.get("jakarta.websocket.endpoint.localAddress"));
        assertEquals(TestSessions.LOCAL_ADDR, local.getAddress().getHostAddress());
        assertEquals(TestSessions.LOCAL_PORT, local.getPort());
        assertEquals(properties, endpoint.config.getUserProperties(), "onOpen sees the same properties");
        assertEquals(registered, session.config().getUserProperties(), "the registered config is untouched");
    }

    @Test
    void aSendOnAClosedSessionFails() {
        FoySession session = TestSessions.open(new Recorder(), peer);
        session.onClose(CloseCodes.NORMAL_CLOSURE.getCode(), "");
        RemoteEndpoint.Basic basic = session.getBasicRemote();

        assertThrows(IOException.class, () -> basic.sendText("late"));
        assertFalse(session.isOpen());
    }
}
