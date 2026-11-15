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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * The sending side of one open WebSocket connection, handed to
 * {@link WebSocketListener#onOpen(WebSocketPeer)}.
 *
 * <p>A peer is <strong>thread-safe</strong>: any thread may send, and frames of concurrent
 * calls are never interleaved. Each send <strong>blocks until its frame is written</strong> to
 * the connection (a virtual thread parks cheaply), so a returning call means the bytes left
 * the transport, and an {@link IOException} means the connection is no longer usable.
 */
public interface WebSocketPeer {

    /**
     * Sends a complete text message.
     *
     * @param text the message
     * @throws IOException when the frame cannot be written
     */
    void sendText(String text) throws IOException;

    /**
     * Sends a complete binary message.
     *
     * @param data the message; its remaining bytes are written
     * @throws IOException when the frame cannot be written
     */
    void sendBinary(ByteBuffer data) throws IOException;

    /**
     * Sends a ping control frame.
     *
     * @param data the application data, at most 125 bytes
     * @throws IOException when the frame cannot be written
     */
    void sendPing(ByteBuffer data) throws IOException;

    /**
     * Sends an unsolicited pong control frame, for instance as a heartbeat (RFC 6455 section
     * 5.5.3). Pings are answered by the transport itself: do not call this in reply to
     * {@link WebSocketListener#onPing(ByteBuffer)}.
     *
     * @param data the application data, at most 125 bytes
     * @throws IOException when the frame cannot be written
     */
    void sendPong(ByteBuffer data) throws IOException;

    /**
     * Sends a close frame and starts the closing handshake, unless the closing handshake already began: a
     * connection sends one close frame. The answer tells which side closed first, whose code and reason
     * Jakarta WebSocket 2.2 section 2.1.5 gives to {@code onClose}.
     *
     * <p>Once {@link #isOpen()} answered {@code false} (a close frame went out or came in, or the connection was
     * cut), a call that has not answered yet writes nothing more and answers without waiting for the peer, once
     * the write already in progress, if any, ends. With Chappe, once a close frame went out, that write can only be
     * the automatic pong of a ping the peer sent before its close frame; it lasts at most the write timeout, when
     * one is set, as one that began before does. A caller may wait for that answer in
     * {@link WebSocketListener#onClose(int, String)}, for a bounded time: Foy's session waits at most 250 ms.
     *
     * @param code   the close code
     * @param reason the close reason, at most 123 bytes in UTF-8
     * @return {@code true} when this call sent the close frame; {@code false} when another close frame went out
     *         before it (an earlier close, the transport's own), the peer's close frame arrived before it, or the
     *         connection was cut
     * @throws IOException when the frame cannot be written; a {@link java.io.InterruptedIOException} other than a
     *                     {@link java.net.SocketTimeoutException} when the calling thread was interrupted before
     *                     anything was written, its interrupt status set again: that close did not start
     */
    boolean close(int code, String reason) throws IOException;

    /**
     * Whether the connection is still open.
     *
     * @return {@code false} once a close frame was sent or received, or the connection broke
     */
    boolean isOpen();

    /**
     * The address of the remote end.
     *
     * @return the remote address
     */
    InetSocketAddress remoteAddress();

    /**
     * Whether the connection is secured by TLS.
     *
     * @return {@code true} for {@code wss}
     */
    boolean isSecure();
}
