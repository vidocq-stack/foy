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

import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.Extension;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The configuration of one connection: the registered {@link ServerEndpointConfig} for everything
 * but the user properties, which are this connection's own copy, taken when its handshake starts.
 * The Jakarta WebSocket API requires it: {@code modifyHandshake} sees a per-connection copy, and
 * that copy, with what {@code modifyHandshake} put in it, is the session's user properties. This
 * view is what {@code modifyHandshake}, {@code Endpoint.onOpen} and the session see, so a value one
 * handshake stores (an {@code HttpSession}) never reaches another connection, and a handshake never
 * writes the registered configuration.
 */
final class ConnectionConfig implements ServerEndpointConfig {

    private final ServerEndpointConfig registered;
    private final Map<String, Object> userProperties;

    ConnectionConfig(ServerEndpointConfig registered) {
        this.registered = registered;
        this.userProperties = Collections.synchronizedMap(new HashMap<>(registered.getUserProperties()));
    }

    /** The configuration the endpoint was registered with: the scope of {@code getOpenSessions}. */
    ServerEndpointConfig registered() {
        return registered;
    }

    /** This connection's user properties: mutable, safe to use from any thread. */
    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public Class<?> getEndpointClass() {
        return registered.getEndpointClass();
    }

    @Override
    public String getPath() {
        return registered.getPath();
    }

    @Override
    public List<String> getSubprotocols() {
        return registered.getSubprotocols();
    }

    @Override
    public List<Extension> getExtensions() {
        return registered.getExtensions();
    }

    @Override
    public Configurator getConfigurator() {
        return registered.getConfigurator();
    }

    @Override
    public List<Class<? extends Encoder>> getEncoders() {
        return registered.getEncoders();
    }

    @Override
    public List<Class<? extends Decoder>> getDecoders() {
        return registered.getDecoders();
    }
}
