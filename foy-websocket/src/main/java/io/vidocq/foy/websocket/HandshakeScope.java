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

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * What the container default configurator needs during one handshake, bound to
 * {@link #CURRENT} around the configurator calls (WEBSOCKET.md section 5.2): the configurator
 * methods receive no context, and a {@code ThreadLocal} is not allowed in Foy.
 *
 * @param instantiator the component factory of the web application, {@code null} when the
 *                     container published none
 * @param origins      the origin policy of the web application
 * @param host         the request's {@code Host} header, {@code null} when the request has none
 *                     or several: the origin check then fails closed for any origin the policy
 *                     does not list
 */
record HandshakeScope(ComponentInstantiator instantiator, OriginPolicy origins, String host) {

    /** The scope of the handshake running on this thread; unbound outside a handshake. */
    static final ScopedValue<HandshakeScope> CURRENT = ScopedValue.newInstance();

    /** The scope of {@code request}'s handshake. */
    static HandshakeScope of(HttpServletRequest request, ComponentInstantiator instantiator, OriginPolicy origins) {
        Enumeration<String> lines = request.getHeaders("Host");
        List<String> hosts = lines == null ? List.of() : Collections.list(lines);
        return new HandshakeScope(instantiator, origins, hosts.size() == 1 ? hosts.getFirst() : null);
    }

    /** Whether the policy lets a handshake with this {@code Origin} header ({@code null}: none) open. */
    boolean allowsOrigin(String origin) {
        return origins.allows(origin, host);
    }

    /**
     * A new endpoint instance from the web application's component factory.
     *
     * @throws InstantiationException when the factory cannot create it, the cause attached
     */
    <T> T newInstance(Class<T> endpointClass) throws InstantiationException {
        if (instantiator == null) {
            throw new InstantiationException("no component instantiator in this web application for "
                    + endpointClass.getName());
        }
        try {
            return instantiator.newInstance(endpointClass);
        } catch (ServletException e) {
            var failure = new InstantiationException("cannot instantiate " + endpointClass.getName());
            failure.initCause(e);
            throw failure;
        }
    }
}
