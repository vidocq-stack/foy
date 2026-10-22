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
package io.vidocq.foy.zap;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * The error paths the container answers: {@code /missing} calls {@code sendError(404)}, with a message that
 * names the {@code item} request parameter when there is one (the container escapes the message in its error
 * page), {@code /broken} calls {@code sendError(500)}, and {@code /boom} throws, so that ZAP sees the
 * container's error pages.
 */
@WebServlet({"/missing", "/broken", "/boom"})
public class ErrorServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        switch (req.getServletPath()) {
            case "/missing" -> {
                String item = req.getParameter("item");
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, item == null ? null : "No such item: " + item);
            }
            case "/broken" -> resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            default -> throw new ServletException("The ZAP target fails on purpose",
                    new IllegalStateException("a failure the error page must not reveal"));
        }
    }
}
