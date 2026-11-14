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

import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.api.WebSocketUpgrade;
import io.vidocq.foy.internal.container.ContextLoaderScope;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketPeer;
import io.vidocq.foy.spi.websocket.WebSocketUpgradeHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The takeover of an upgraded request by a {@link WebSocketUpgradeHandler}: chappe speaks RFC 6455
 * (handshake already answered, frames, closing handshake) and this class forwards its events to the
 * handler's {@link WebSocketListener}, one at a time, each with the application class loader as
 * context class loader (WEBSOCKET.md section 5.2).
 *
 * <p>The transport answers every ping with a pong itself: {@code onPing} is only forwarded. The
 * connection is registered with the context at {@code onOpen} so that an undeploy closes it with
 * 1001; {@code onClose} calls the listener, then unregisters the connection and calls
 * {@link WebSocketUpgradeHandler#destroy()}, exactly once. An {@code Error} from the listener's
 * {@code onOpen}, after which chappe sends no event, closes with 1011 and ends the takeover the same way
 * before it propagates.</p>
 */
final class WebSocketTakeover implements WebSocketHandler {

    private static final System.Logger LOG = System.getLogger(WebSocketTakeover.class.getName());

    private static final int GOING_AWAY = 1001;
    private static final int INTERNAL_ERROR = 1011;
    private static final String STOPPING = "the application is stopping";
    private static final String ON_OPEN_FAILED = "onOpen failed";

    private final WebSocketUpgradeHandler handler;
    private final VidocqServletContext context;
    private final ClassLoader applicationLoader;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean registered;
    private volatile boolean openFailed;
    private final AtomicReference<WebSocketListener> listener = new AtomicReference<>();
    private final AtomicReference<AutoCloseable> undeployHook = new AtomicReference<>();

    WebSocketTakeover(WebSocketUpgradeHandler handler, VidocqServletContext context, ClassLoader applicationLoader) {
        this.handler = handler;
        this.context = context;
        this.applicationLoader = applicationLoader;
    }

    /**
     * The 101 response chappe completes the handshake with: the handler's subprotocol and the headers the
     * application set (chappe drops the ones it writes itself).
     *
     * @throws IllegalArgumentException when chappe refuses the subprotocol or a header
     */
    Response response(Headers headers) {
        return new WebSocketUpgrade(this, handler.subprotocol(), null, headers);
    }

    @Override
    public void onOpen(WebSocket ws, Request handshake) throws Exception {
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            AutoCloseable hook = () -> stopping(ws);
            undeployHook.set(hook);
            if (context != null && !context.registerUpgradedConnection(hook)) {
                // Undeployed meanwhile: the handler never starts, so it is not destroyed either.
                stopping(ws);
                return;
            }
            registered = true;
            boolean returned = false;
            try {
                var opened = handler.listener();
                listener.set(opened);
                opened.onOpen(new Peer(ws));
                returned = true;
            } catch (Exception e) {
                // Chappe reports the failure through onError and closes with 1011, but never calls
                // onClose: onError finishes the takeover (see there).
                openFailed = true;
                throw e;
            } finally {
                if (!returned && !openFailed) {
                    // An Error: chappe lets it end the connection with neither onError nor onClose.
                    // Close with 1011 and finish here, then the Error propagates.
                    closeQuietly(ws, INTERNAL_ERROR, ON_OPEN_FAILED);
                    finish(INTERNAL_ERROR, ON_OPEN_FAILED);
                }
            }
        }
    }

    @Override
    public void onText(WebSocket ws, String message) throws Exception {
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            var current = listener.get();
            if (current != null) current.onText(message);
        }
    }

    @Override
    public void onBinary(WebSocket ws, ByteBuffer data) throws Exception {
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            var current = listener.get();
            if (current != null) current.onBinary(data);
        }
    }

    @Override
    public void onPing(WebSocket ws, ByteBuffer payload) throws Exception {
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            var current = listener.get();
            if (current != null) current.onPing(payload);
        }
    }

    @Override
    public void onPong(WebSocket ws, ByteBuffer payload) throws Exception {
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            var current = listener.get();
            if (current != null) current.onPong(payload);
        }
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            var current = listener.get();
            if (current != null) current.onError(error);
        } finally {
            // A failed onOpen is followed by no onClose from chappe: the listener hears the 1011 chappe
            // closes with, then the takeover is released, once.
            if (openFailed) finish(INTERNAL_ERROR, ON_OPEN_FAILED);
        }
    }

    @Override
    public void onClose(WebSocket ws, int code, String reason) {
        finish(code, reason);
    }

    /**
     * The end of the takeover, once (also when {@code onOpen} failed): {@code listener.onClose}, then the
     * connection is unregistered and {@code destroy()} runs. A refused registration (undeployed
     * meanwhile) never started the handler: no {@code onClose}, no {@code destroy()}.
     */
    private void finish(int code, String reason) {
        if (!registered || !closed.compareAndSet(false, true)) return;
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            try {
                var current = listener.get();
                if (current != null) current.onClose(code, reason);
            } finally {
                if (context != null) context.unregisterUpgradedConnection(undeployHook.get());
                handler.destroy();
            }
        }
    }

    /**
     * The undeploy hook: closes the socket with 1001. Chappe takes its write lock interruptibly, so on
     * an interrupted thread it would send no Close and arm no close deadline: the interrupt is cleared
     * for the close and restored afterwards. A close that still throws (dead connection) is logged;
     * chappe's own teardown then ends the connection and calls {@code onClose}.
     */
    private static void stopping(WebSocket ws) {
        boolean interrupted = Thread.interrupted();
        try {
            closeQuietly(ws, GOING_AWAY, STOPPING);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Sends the Close frame of {@code code}; a close that throws is logged, the transport ends the connection. */
    private static void closeQuietly(WebSocket ws, int code, String reason) {
        try {
            ws.close(code, reason);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "closing the WebSocket with " + code + " failed", e);
        }
    }

    /** Chappe's socket seen as the SPI's peer. */
    private record Peer(WebSocket ws) implements WebSocketPeer {
        @Override public void sendText(String text) throws IOException { ws.sendText(text); }
        @Override public void sendBinary(ByteBuffer data) throws IOException { ws.sendBinary(data); }
        @Override public void sendPing(ByteBuffer data) throws IOException { ws.sendPing(data); }
        @Override public void sendPong(ByteBuffer data) throws IOException { ws.sendPong(data); }
        @Override public void close(int code, String reason) throws IOException { ws.close(code, reason); }
        @Override public boolean isOpen() { return ws.isOpen(); }
        @Override public InetSocketAddress remoteAddress() { return ws.remoteAddress(); }
        @Override public boolean isSecure() { return ws.isSecure(); }
    }
}
