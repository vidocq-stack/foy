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
package io.vidocq.foy.internal.boot;

import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Stands in for a servlet whose init() failed (Servlet 6.1 §2.3.2.1, §2.3.3.2). */
final class InitFailureServlet extends GenericServlet {
    private final ServletException failure;

    InitFailureServlet(ServletException failure) { this.failure = failure; }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        // Special case §2.3.3.2: permanent UnavailableException -> 404, temporary -> 503.
        if (failure instanceof UnavailableException ue) {
            ((HttpServletResponse) res).sendError(ue.isPermanent() ? 404 : 503, ue.getMessage());
            return;
        }
        // Re-throw so an <error-page> mapped on ServletException is dispatched
        // (the TCK GenericServletTests expect that dispatch).
        throw failure;
    }
}
