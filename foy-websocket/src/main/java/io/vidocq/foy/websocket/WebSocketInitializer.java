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
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;

import java.util.EnumSet;
import java.util.Set;

/**
 * Publishes the web application's WebSocket container when it starts (WEBSOCKET.md section 5.3
 * step 1): the {@code jakarta.websocket.server.ServerContainer} attribute, which Atmosphere looks
 * for, and the handshake filter on {@code /*}, after the application's own filters.
 */
public final class WebSocketInitializer implements ServletContainerInitializer {

    /** The name of the handshake filter. */
    private static final String FILTER_NAME = "io.vidocq.foy.websocket";

    /**
     * Creates the container and registers the filter.
     *
     * @throws IllegalArgumentException when the init parameter
     *         {@code io.vidocq.foy.websocket.allowed-origins} is malformed: it escapes on purpose, so
     *         the deployment stops (Foy only logs a {@code ServletException} from an initializer)
     */
    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext context) {
        OriginPolicy origins = OriginPolicy.parse(context.getInitParameter(OriginPolicy.INIT_PARAMETER));
        var instantiator = context.getAttribute(ComponentInstantiator.ATTRIBUTE) instanceof ComponentInstantiator i
                ? i : null;
        var container = new FoyServerContainer(origins, instantiator);
        context.setAttribute(ServerContainer.class.getName(), container);
        FilterRegistration.Dynamic filter = context.addFilter(FILTER_NAME, new HandshakeFilter(container));
        filter.setAsyncSupported(true);
        filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    }
}
