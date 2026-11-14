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

import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

/**
 * An {@link HttpUpgradeHandler} that tells the transport to take the connection over as a
 * WebSocket. When a servlet calls {@code HttpServletRequest.upgrade} with such a handler, the
 * transport bridge answers the handshake (with {@link #subprotocol()}), then runs the WebSocket
 * framing itself and delivers the events to {@link #listener()}; the handler never sees raw
 * bytes.
 */
public interface WebSocketUpgradeHandler extends HttpUpgradeHandler {

    /**
     * The sub-protocol to answer in {@code Sec-WebSocket-Protocol}.
     *
     * @return the negotiated sub-protocol, or {@code null} for none
     */
    String subprotocol();

    /**
     * The listener that receives the connection's events.
     *
     * @return the listener, never {@code null}
     */
    WebSocketListener listener();

    /**
     * Not used: the transport takes the connection over without handing a raw connection.
     *
     * @param connection ignored
     * @throws IllegalStateException always
     */
    @Override
    default void init(WebConnection connection) {
        throw new IllegalStateException("a WebSocket upgrade handler gets no raw connection");
    }
}
