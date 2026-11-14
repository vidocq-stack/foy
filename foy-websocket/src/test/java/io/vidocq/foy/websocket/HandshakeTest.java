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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HandshakeTest {

    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    /** A request stub answering only the methods the handshake reads. */
    private static HttpServletRequest request(String method, String protocol, Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(HandshakeTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getProtocol" -> protocol;
                    case "getHeader" -> headers.get((String) args[0]);
                    case "getHeaders" -> {
                        String v = headers.get((String) args[0]);
                        yield Collections.enumeration(v == null ? List.of() : List.of(v));
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static Map<String, String> headers(String connection, String upgrade, String key, String version) {
        var h = new HashMap<String, String>();
        h.put("Connection", connection);
        h.put("Upgrade", upgrade);
        h.put("Sec-WebSocket-Key", key);
        h.put("Sec-WebSocket-Version", version);
        return h;
    }

    private static Map<String, String> valid() {
        return headers("Upgrade", "websocket", KEY, "13");
    }

    @Test
    void aValidHandshakePasses() {
        assertNull(Handshake.validate(request("GET", "HTTP/1.1", valid())));
    }

    @Test
    void acceptsAConnectionHeaderListingSeveralTokens() {
        var h = headers("keep-alive, Upgrade", "WebSocket", KEY, "13");

        assertNull(Handshake.validate(request("GET", "HTTP/1.1", h)));
    }

    @Test
    void notGetIs400() {
        assertEquals(400, Handshake.validate(request("POST", "HTTP/1.1", valid())).status());
    }

    @Test
    void http10Is400() {
        assertEquals(400, Handshake.validate(request("GET", "HTTP/1.0", valid())).status());
    }

    @Test
    void noUpgradeTokenIs400() {
        var h = headers("keep-alive", "websocket", KEY, "13");

        assertEquals(400, Handshake.validate(request("GET", "HTTP/1.1", h)).status());
    }

    @Test
    void aKeyThatIsNot16BytesIs400() {
        var refusal = Handshake.validate(request("GET", "HTTP/1.1", headers("Upgrade", "websocket", "c2hvcnQ=", "13")));

        assertEquals(400, refusal.status());
        assertNull(refusal.header());
    }

    @Test
    void anotherVersionIs426WithVersion13() {
        var refusal = Handshake.validate(request("GET", "HTTP/1.1", headers("Upgrade", "websocket", KEY, "8")));

        assertEquals(426, refusal.status());
        assertEquals("Sec-WebSocket-Version", refusal.header());
        assertEquals("13", refusal.value());
    }
}
