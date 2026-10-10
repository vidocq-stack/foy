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
package io.vidocq.foy.it.weld;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Uses a session-scoped bean: {@code /session/<op>}. */
@Dependent
@WebServlet(urlPatterns = "/session/*", asyncSupported = true)
public class SessionServlet extends HttpServlet {

    @Inject
    SessionCart cart;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        String op = request.getPathInfo() == null ? "" : request.getPathInfo().substring(1);
        switch (op) {
            case "id" -> response.getWriter().write(cart.id() + "|" + request.getSession(false).getId());
            case "invalidate" -> {
                String id = cart.id();
                request.getSession(false).invalidate();
                response.getWriter().write(id + "|" + cart.id());
            }
            case "expire" -> {
                String id = cart.id();
                request.getSession(false).setMaxInactiveInterval(1);
                response.getWriter().write(id + "|" + request.getSession(false).getId());
            }
            case "same" -> response.getWriter().write(request.getAttribute(SessionRequestListener.ATTRIBUTE)
                    + "|" + request.getAttribute(SessionFilter.ATTRIBUTE) + "|" + cart.id());
            case "async" -> {
                AsyncContext async = request.startAsync();
                async.start(() -> {
                    String state;
                    try {
                        cart.id();
                        state = "active";
                    } catch (RuntimeException e) {
                        state = e.getClass().getSimpleName();
                    }
                    try {
                        async.getResponse().getWriter().write(state);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    } finally {
                        async.complete();
                    }
                });
            }
            default -> response.sendError(404);
        }
    }
}
