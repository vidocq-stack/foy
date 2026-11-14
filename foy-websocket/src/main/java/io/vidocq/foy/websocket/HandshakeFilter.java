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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * The filter on {@code /*} that answers the WebSocket handshakes of the registered endpoints
 * (WEBSOCKET.md section 5.3 step 3). It runs after the application's filters and is
 * async-supported, so a servlet behind it (Atmosphere's long polling) can still start async. Any
 * other request goes down the chain untouched.
 */
final class HandshakeFilter implements Filter {

    private final FoyServerContainer container;

    HandshakeFilter(FoyServerContainer container) {
        this.container = container;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse httpResponse
                && Handshake.asksForWebSocket(http)) {
            EndpointMatch match = container.match(contextRelativePath(http));
            if (match != null) {
                container.handshake(http, httpResponse, match);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The decoded path within the context: the servlet path, then the path info. */
    private static String contextRelativePath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }
}
