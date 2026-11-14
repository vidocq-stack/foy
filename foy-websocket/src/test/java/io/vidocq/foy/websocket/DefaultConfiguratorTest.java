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

import io.vidocq.foy.spi.gen.ComponentInstantiator;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The container default configurator: the rules it applies on its own and through the handshake scope. */
class DefaultConfiguratorTest {

    private final DefaultConfigurator configurator = new DefaultConfigurator();

    /** A request stub with the given {@code Host} lines, answering only {@code getHeaders}. */
    private static HttpServletRequest withHostLines(List<String> lines) {
        return (HttpServletRequest) Proxy.newProxyInstance(DefaultConfiguratorTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> {
                    if ("getHeaders".equals(m.getName()) && "Host".equals(args[0])) {
                        return Collections.enumeration(lines);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private boolean allows(List<String> hostLines, String origin) {
        var scope = HandshakeScope.of(withHostLines(hostLines), null, OriginPolicy.parse(null));
        return ScopedValue.where(HandshakeScope.CURRENT, scope).call(() -> configurator.checkOrigin(origin));
    }

    @Test
    void theSubprotocolIsTheFirstRequestedOneTheServerSupports() {
        assertEquals("json", configurator.getNegotiatedSubprotocol(List.of("chat", "json"),
                List.of("other", "json", "chat")));
        assertEquals("", configurator.getNegotiatedSubprotocol(List.of("chat"), List.of("other")));
    }

    @Test
    void outsideAHandshakeNoOriginIsAllowedAndNoEndpointIsCreated() {
        assertFalse(configurator.checkOrigin(null));
        assertThrows(InstantiationException.class, () -> configurator.getEndpointInstance(Object.class));
    }

    @Test
    void theServerOriginIsTheOnlyHostLine() {
        assertTrue(allows(List.of("app.example.com"), "https://app.example.com"));
        assertTrue(allows(List.of(), null), "no Origin: not a browser");
    }

    @Test
    void anAbsentOrRepeatedHostFailsClosed() {
        assertFalse(allows(List.of(), "https://app.example.com"));
        assertFalse(allows(List.of("app.example.com", "app.example.com"), "https://app.example.com"));
    }

    @Test
    void aFailedInstantiationIsAnInstantiationException() {
        var failing = new ComponentInstantiator() {
            @Override
            public <T> T newInstance(Class<T> type) throws ServletException {
                throw new ServletException("no instance of " + type.getName());
            }
        };
        var scope = new HandshakeScope(failing, OriginPolicy.parse(null), null);
        var inHandshake = ScopedValue.where(HandshakeScope.CURRENT, scope);

        var failure = assertThrows(InstantiationException.class,
                () -> inHandshake.call(() -> configurator.getEndpointInstance(Object.class)));

        assertInstanceOf(ServletException.class, failure.getCause());
    }
}
