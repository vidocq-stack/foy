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
package io.vidocq.foy.it.session;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * {@code /cart/add?item=x}, {@code /cart/show}, {@code /cart/invalidate} and {@code /cart/events}.
 * The answer to the first two is {@code <cart id>|<items, comma-separated>}. Beans are reached
 * through {@code CDI.current()}, which every container under test supports, so the servlet needs
 * no injection.
 */
public class CartServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        String what = request.getPathInfo() == null ? "" : request.getPathInfo().substring(1);
        switch (what) {
            case "add" -> {
                Cart cart = CDI.current().select(Cart.class).get();
                cart.add(request.getParameter("item"));
                response.getWriter().write(cart.id() + "|" + String.join(",", cart.items()));
            }
            case "show" -> {
                Cart cart = CDI.current().select(Cart.class).get();
                response.getWriter().write(cart.id() + "|" + String.join(",", cart.items()));
            }
            case "invalidate" -> {
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.getWriter().write("invalidated");
            }
            case "events" -> response.getWriter().write(CDI.current().select(SessionEvents.class).get().summary());
            default -> response.sendError(404);
        }
    }
}
