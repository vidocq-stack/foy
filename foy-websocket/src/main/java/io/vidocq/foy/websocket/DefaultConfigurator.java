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

import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.List;

/**
 * The container default configurator: the one an endpoint gets when it names none, and the one
 * the API's base {@link ServerEndpointConfig.Configurator} delegates to (found through
 * {@link java.util.ServiceLoader}). It overrides every method: an inherited one would delegate
 * to the container default, this class, and recurse.
 *
 * <p>The origin check and the endpoint instance need the web application, which a configurator
 * method does not receive: both read the {@link HandshakeScope} bound for the handshake, and
 * refuse outside one.
 */
public class DefaultConfigurator extends ServerEndpointConfig.Configurator {

    /** The first requested subprotocol the endpoint supports, {@code ""} when there is none. */
    @Override
    public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
        for (String protocol : requested) {
            if (supported.contains(protocol)) {
                return protocol;
            }
        }
        return "";
    }

    /** None: Foy installs no extension. */
    @Override
    public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
        return List.of();
    }

    /** The origin policy of the web application (WEBSOCKET.md section 5.3.1); {@code false} outside a handshake. */
    @Override
    public boolean checkOrigin(String originHeaderValue) {
        return HandshakeScope.CURRENT.isBound() && HandshakeScope.CURRENT.get().allowsOrigin(originHeaderValue);
    }

    /** Nothing to change. */
    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        // the default handshake is left as it is
    }

    /**
     * A new instance from the web application's component factory (generated companion, hidden
     * factory or CDI), never by reflection here.
     *
     * @throws InstantiationException outside a handshake, or when the factory fails (the cause attached)
     */
    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (!HandshakeScope.CURRENT.isBound()) {
            throw new InstantiationException("no WebSocket handshake in progress to instantiate "
                    + endpointClass.getName());
        }
        return HandshakeScope.CURRENT.get().newInstance(endpointClass);
    }
}
