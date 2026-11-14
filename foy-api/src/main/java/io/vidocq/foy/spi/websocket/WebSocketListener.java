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
package io.vidocq.foy.spi.websocket;

import java.nio.ByteBuffer;

/**
 * Receives the events of one WebSocket connection, in the order they happen on the wire.
 *
 * <p>Threading contract: the transport delivers <strong>one event at a time</strong> for a
 * given connection, on a virtual thread whose thread context class loader is the application
 * class loader. A listener therefore needs no synchronization between its callbacks. A
 * callback may block (for instance on {@link WebSocketPeer} sends); the next event waits.
 *
 * <p>Every method is a no-op by default. A buffer passed to a callback is only valid for the
 * duration of that call: copy it to keep it.
 */
public interface WebSocketListener {

    /**
     * The handshake is complete and the connection is open. Always the first event.
     *
     * @param peer the sending side of the connection
     * @throws Exception any failure; it is reported through {@link #onError(Throwable)} and the
     *                   connection is closed
     */
    default void onOpen(WebSocketPeer peer) throws Exception {
        // no-op by default
    }

    /**
     * A complete text message (fragments already reassembled).
     *
     * @param text the message
     * @throws Exception any failure; see {@link #onOpen(WebSocketPeer)}
     */
    default void onText(String text) throws Exception {
        // no-op by default
    }

    /**
     * A complete binary message (fragments already reassembled).
     *
     * @param data the message, valid for this call only
     * @throws Exception any failure; see {@link #onOpen(WebSocketPeer)}
     */
    default void onBinary(ByteBuffer data) throws Exception {
        // no-op by default
    }

    /**
     * A ping control frame arrived. The transport does not answer it by itself on behalf of the
     * listener's implementation: the listener decides whether to send a pong.
     *
     * @param data the application data of the frame, valid for this call only
     * @throws Exception any failure; see {@link #onOpen(WebSocketPeer)}
     */
    default void onPing(ByteBuffer data) throws Exception {
        // no-op by default
    }

    /**
     * A pong control frame arrived.
     *
     * @param data the application data of the frame, valid for this call only
     * @throws Exception any failure; see {@link #onOpen(WebSocketPeer)}
     */
    default void onPong(ByteBuffer data) throws Exception {
        // no-op by default
    }

    /**
     * The connection is closed. Always the last event, delivered exactly once.
     *
     * @param code   the close code of the peer; {@code 1005} when its close frame carried none,
     *               {@code 1006} when the TCP connection was cut without a close frame
     * @param reason the close reason, empty when there is none
     */
    default void onClose(int code, String reason) {
        // no-op by default
    }

    /**
     * A failure on the connection: a protocol violation, an I/O error, or an exception thrown by
     * another callback. Delivered just before {@link #onClose(int, String)}.
     *
     * @param error the failure
     */
    default void onError(Throwable error) {
        // no-op by default
    }
}
