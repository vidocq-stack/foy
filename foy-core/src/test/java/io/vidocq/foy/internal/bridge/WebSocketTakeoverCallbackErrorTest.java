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
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.WebSocket;
import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code Error} thrown by the application's listener never reaches a thread of chappe, whichever thread
 * delivers the callback: it is logged on the takeover's logger and the takeover goes on (BUG-20261116-01). The
 * undeploy's hook runs {@code onClose} too, but so does chappe's connection thread when the peer's Close answers
 * the undeploy's 1001 first; only the first has a context that logs what escapes.
 */
class WebSocketTakeoverCallbackErrorTest {

    private static final String LOGGER = WebSocketTakeover.class.getName();
    private static final Duration NO_BOUND_REACHED = Duration.ofSeconds(30);

    private final AtomicInteger destroys = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final VidocqServletContext context = new VidocqServletContext("/");

    @Test
    void anErrorFromOnCloseDeliveredByTheTransportIsLoggedAndDestroyStillRuns() throws Exception {
        var takeover = openTakeover(new FailingListener(false));
        try (var log = LogCapture.of(LOGGER)) {
            assertDoesNotThrow(() -> takeover.onClose(new StubWebSocket(), 1000, "bye"));
            assertTrue(log.thrown().stream().anyMatch(ExceptionInInitializerError.class::isInstance),
                    log.thrown().toString());
            assertEquals(1, log.thrown().size(), "logged once");
        }
        assertEquals(1, closes.get());
        assertEquals(1, destroys.get());
    }

    @Test
    void anErrorFromOnOpenDoesNotReachTheThreadOfTheTransport() {
        var takeover = new WebSocketTakeover(new Handler(new FailingListener(true)), context,
                getClass().getClassLoader(), NO_BOUND_REACHED);
        try (var log = LogCapture.of(LOGGER)) {
            var failure = assertThrows(IOException.class, () -> takeover.onOpen(new StubWebSocket(), null));
            assertInstanceOf(ExceptionInInitializerError.class, failure.getCause());
            // Two failures, each logged once: the onOpen's, and the onClose the failed onOpen's end delivers.
            var messages = log.thrown().stream().map(Throwable::getMessage).sorted().toList();
            assertEquals(List.of("onClose failed", "onOpen failed"), messages);
            // Chappe then reports the wrapper to onError: the takeover ended, the listener hears nothing of it.
            takeover.onError(new StubWebSocket(), failure);
        }
        assertEquals(0, errors.get(), "the listener heard the wrapper of its own Error");
        assertEquals(1, closes.get(), "the listener hears the end of the failed onOpen");
        assertEquals(1, destroys.get());
    }

    private WebSocketTakeover openTakeover(FailingListener listener) throws IOException {
        var takeover = new WebSocketTakeover(new Handler(listener), context, getClass().getClassLoader(),
                NO_BOUND_REACHED);
        takeover.onOpen(new StubWebSocket(), null);
        return takeover;
    }

    private final class Handler implements WebSocketUpgradeHandler {
        private final WebSocketListener listener;

        Handler(WebSocketListener listener) {
            this.listener = listener;
        }

        @Override
        public String subprotocol() {
            return null;
        }

        @Override
        public WebSocketListener listener() {
            return listener;
        }

        @Override
        public void destroy() {
            destroys.incrementAndGet();
        }
    }

    private final class FailingListener implements WebSocketListener {
        private final boolean failOpen;

        FailingListener(boolean failOpen) {
            this.failOpen = failOpen;
        }

        @Override
        public void onOpen(io.vidocq.foy.spi.websocket.WebSocketPeer peer) {
            if (failOpen) throw new ExceptionInInitializerError("onOpen failed");
        }

        @Override
        public void onError(Throwable error) {
            errors.incrementAndGet();
        }

        @Override
        public void onClose(int code, String reason) {
            closes.incrementAndGet();
            throw new ExceptionInInitializerError("onClose failed");
        }
    }

    /** A connection that accepts every Close. */
    private static final class StubWebSocket implements WebSocket {
        @Override public void close(int code, String reason) { /* accepted */ }
        @Override public boolean sendClose(int code, String reason) { return true; }
        @Override public void abort() { /* nothing to cut */ }
        @Override public boolean isOpen() { return true; }
        @Override public void sendText(String message) { /* nothing is sent */ }
        @Override public void sendBinary(ByteBuffer payload) { /* nothing is sent */ }
        @Override public void sendPing(ByteBuffer payload) { /* nothing is sent */ }
        @Override public void sendPong(ByteBuffer payload) { /* nothing is sent */ }
        @Override public InetSocketAddress remoteAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public String subprotocol() { return null; }
        @Override public Object attribute(String key) { return null; }
        @Override public WebSocket attribute(String key, Object value) { return this; }
    }
}
