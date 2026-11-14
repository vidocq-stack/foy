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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.websocket.Extension;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HandshakeRequestImplTest {

    /** A handshake request stub answering only the methods {@link HandshakeRequestImpl} reads. */
    private static HttpServletRequest request(Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(HandshakeRequestImplTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getHeader" -> headers.get((String) args[0]);
                    case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                    case "getHeaders" -> {
                        String v = headers.get((String) args[0]);
                        yield Collections.enumeration(v == null ? List.of() : List.of(v));
                    }
                    case "getRequestURI" -> "/chat";
                    case "isSecure" -> false;
                    case "getQueryString", "getLocalAddr" -> null;
                    case "getParameterMap" -> Map.of();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static List<String> offeredNames(String extensions) {
        var request = new HandshakeRequestImpl(request(Map.of(
                "Host", "localhost:8080", "Sec-WebSocket-Extensions", extensions)));
        return request.requestedExtensions().stream().map(Extension::getName).toList();
    }

    @Test
    void offeredExtensionsKeepTheirNameAndParameters() {
        var request = new HandshakeRequestImpl(request(Map.of("Host", "localhost:8080",
                "Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits, x-webkit")));

        List<Extension> offers = request.requestedExtensions();

        assertEquals(List.of("permessage-deflate", "x-webkit"), offers.stream().map(Extension::getName).toList());
        assertEquals("client_max_window_bits", offers.get(0).getParameters().get(0).getName());
    }

    @Test
    void anOfferWithoutANameIsIgnored() {
        assertEquals(List.of(), offeredNames(";"));
        assertEquals(List.of(), offeredNames("; a=b"));
        assertEquals(List.of("x-webkit"), offeredNames(";, x-webkit"));
    }
}
