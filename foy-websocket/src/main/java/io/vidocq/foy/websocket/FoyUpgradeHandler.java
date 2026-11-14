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

import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketUpgradeHandler;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Takes the HTTP exchange over after the handshake: {@code HttpServletRequest.upgrade} creates it
 * through Foy's component factory ({@link FoyUpgradeHandlerComponent}), the handshake
 * {@linkplain #prepare prepares} it with its session, and the transport then answers the 101 with
 * the session's subprotocol and delivers the connection's events to the session.
 */
public final class FoyUpgradeHandler implements WebSocketUpgradeHandler {

    /** Set on the request thread, read on the connection's thread. */
    private final AtomicReference<FoySession> session = new AtomicReference<>();

    /** Hands the session of the handshake to this handler, before the request returns. */
    void prepare(FoySession prepared) {
        session.set(prepared);
    }

    /** The negotiated subprotocol, {@code null} for none. */
    @Override
    public String subprotocol() {
        String negotiated = session().getNegotiatedSubprotocol();
        return negotiated.isEmpty() ? null : negotiated;
    }

    @Override
    public WebSocketListener listener() {
        return session();
    }

    /** Nothing to release: the session ended with the connection's close event, which precedes this call. */
    @Override
    public void destroy() {
        // the session holds nothing beyond the connection
    }

    private FoySession session() {
        FoySession prepared = session.get();
        if (prepared == null) {
            throw new IllegalStateException("the WebSocket upgrade handler was not prepared by a handshake");
        }
        return prepared;
    }
}
