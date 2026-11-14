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

import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** The RFC 6455 checks of a WebSocket upgrade request (the origin is checked by {@link OriginPolicy}). */
final class Handshake {

    private static final int KEY_BYTES = 16;
    private static final int BAD_REQUEST = 400;
    private static final int UPGRADE_REQUIRED = 426;

    private Handshake() {
    }

    /**
     * A refused handshake.
     *
     * @param status the HTTP status
     * @param header a header to send with the refusal, {@code null} but for the 426's {@code Sec-WebSocket-Version}
     * @param value  the value of {@code header}
     */
    record Refusal(int status, String header, String value) {
    }

    /** The refusal of {@code request}, or {@code null} when it is a valid WebSocket upgrade. */
    static Refusal validate(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) || !"HTTP/1.1".equals(request.getProtocol())
                || !hasToken(request.getHeaders("Upgrade"), "websocket")
                || !hasToken(request.getHeaders("Connection"), "upgrade")
                || !isNonce(request.getHeader("Sec-WebSocket-Key"))) {
            return new Refusal(BAD_REQUEST, null, null);
        }
        if (!"13".equals(request.getHeader("Sec-WebSocket-Version"))) {
            return new Refusal(UPGRADE_REQUIRED, "Sec-WebSocket-Version", "13");
        }
        return null;
    }

    /** Every comma-separated token of every header line, trimmed. */
    static List<String> tokens(Enumeration<String> values) {
        var tokens = new ArrayList<String>();
        while (values != null && values.hasMoreElements()) {
            for (String part : values.nextElement().split(",")) {
                String token = part.trim();
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private static boolean hasToken(Enumeration<String> values, String expected) {
        return tokens(values).stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).equals(expected));
    }

    /** Same rule as Chappe's router: base64 decoding to exactly 16 bytes. */
    private static boolean isNonce(String key) {
        if (key == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key).length == KEY_BYTES;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}
