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

import jakarta.websocket.HandshakeResponse;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The headers {@code modifyHandshake} adds to the 101, by name ignoring case. It starts empty:
 * the headers the transport writes itself ({@code Upgrade}, {@code Sec-WebSocket-Accept}, ...)
 * and those the application set on the servlet response are not in it. Used on the handshake's
 * thread only.
 */
final class HandshakeResponseImpl implements HandshakeResponse {

    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    @Override
    public Map<String, List<String>> getHeaders() {
        return headers;
    }
}
