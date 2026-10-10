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
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** A servlet that is a CDI bean of Weld: injected, and reading a request-scoped bean. */
@Dependent
@WebServlet("/greet/*")
public class GreetingServlet extends HttpServlet {

    @Inject
    Greeter greeter;

    @Inject
    RequestToken first;

    @Inject
    RequestToken second;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        String what = request.getPathInfo() == null ? "" : request.getPathInfo().substring(1);
        if (what.equals("token")) {
            response.getWriter().write(first.value() + "|" + second.value());
        } else if (what.equals("listener")) {
            response.getWriter().write(String.valueOf(getServletContext().getAttribute(StartupListener.ATTRIBUTE)));
        } else {
            response.getWriter().write(greeter.greet(what));
        }
    }
}
