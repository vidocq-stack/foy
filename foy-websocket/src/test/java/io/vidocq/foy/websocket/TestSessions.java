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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sessions opened on a {@link FakePeer}, as the handshake and the transport open them. */
final class TestSessions {

    /** The local address of the handshake request. */
    static final String LOCAL_ADDR = "198.51.100.1";
    static final int LOCAL_PORT = 8080;

    private TestSessions() {
    }

    /** A session of {@code endpoint} on a new container, not opened yet. */
    static FoySession session(Endpoint endpoint) {
        return session(new FoyServerContainer(OriginPolicy.parse(null), null), endpoint);
    }

    /** A session of {@code endpoint} on {@code container}, not opened yet. */
    static FoySession session(FoyServerContainer container, Endpoint endpoint) {
        ServerEndpointConfig registered = ServerEndpointConfig.Builder.create(endpoint.getClass(), "/chat").build();
        return new FoySession(container, new ConnectionConfig(registered), endpoint,
                new HandshakeRequestImpl(request()), Map.of(), "");
    }

    /** A session of {@code endpoint} opened on {@code peer}. */
    static FoySession open(Endpoint endpoint, FakePeer peer) {
        FoySession session = session(endpoint);
        session.onOpen(peer);
        return session;
    }

    /**
     * Asserts that {@code endpoint} heard one {@code onClose}, for a close the container initiated: 1006 with
     * {@code reason}, whatever the code on the wire (Jakarta WebSocket 2.2 section 2.1.5).
     */
    static void assertClosedByTheContainer(Recorder endpoint, String reason) {
        assertEquals(1, endpoint.closes.size(), endpoint.closes::toString);
        CloseReason heard = endpoint.closes.getFirst();
        assertEquals(CloseCodes.CLOSED_ABNORMALLY, heard.getCloseCode(), heard::toString);
        assertEquals(reason, heard.getReasonPhrase());
    }

    /** A handshake request answering only the methods the handshake and the session read. */
    private static HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(TestSessions.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getHeaderNames" -> Collections.enumeration(List.of("Host"));
                    case "getHeaders" -> Collections.enumeration(List.of("example.com"));
                    case "getHeader" -> "Host".equalsIgnoreCase((String) args[0]) ? "example.com" : null;
                    case "getRequestURI" -> "/chat";
                    case "isSecure" -> false;
                    case "getParameterMap" -> Map.of();
                    case "getLocalAddr" -> LOCAL_ADDR;
                    case "getLocalPort" -> LOCAL_PORT;
                    case "getQueryString", "getUserPrincipal" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** An endpoint that records its events; {@link #onOpen} runs the action a test gives. */
    static final class Recorder extends Endpoint {
        final List<CloseReason> closes = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        private final Consumer<Session> atOpen;
        volatile EndpointConfig config;

        Recorder() {
            this(session -> { });
        }

        Recorder(Consumer<Session> atOpen) {
            this.atOpen = atOpen;
        }

        @Override
        public void onOpen(Session session, EndpointConfig endpointConfig) {
            config = endpointConfig;
            atOpen.accept(session);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            closes.add(closeReason);
            closed.countDown();
        }

        @Override
        public void onError(Session session, Throwable thr) {
            errors.add(thr);
            failed.countDown();
        }
    }
}
