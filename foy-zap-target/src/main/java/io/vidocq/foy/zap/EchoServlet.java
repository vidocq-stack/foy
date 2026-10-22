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

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Echoes the {@code q} request parameter, HTML-escaped, under a form that posts it back. The form carries
 * an anti-CSRF token kept in the HTTP session, which a POST must return.
 */
@WebServlet("/echo")
public class EchoServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static final String TOKEN_ATTRIBUTE = "io.vidocq.foy.zap.csrf";

    static final String TOKEN_PARAMETER = "_csrf";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        render(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Object expected = session == null ? null : session.getAttribute(TOKEN_ATTRIBUTE);
        if (expected == null || !expected.equals(req.getParameter(TOKEN_PARAMETER))) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        render(req, resp);
    }

    private static void render(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String q = req.getParameter("q");
        String said = q == null ? "" : "<p>You said: <strong>" + Html.escape(q) + "</strong></p>\n";
        String form = """
                <form method="post" action="echo">
                <input type="hidden" name="%s" value="%s">
                <label>Say something: <input type="text" name="q"></label>
                <button type="submit">Send</button>
                </form>
                """.formatted(TOKEN_PARAMETER, Html.escape(token(req.getSession())));
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().write(Html.page("Echo", said + form));
    }

    private static String token(HttpSession session) {
        Object token = session.getAttribute(TOKEN_ATTRIBUTE);
        if (token instanceof String s) return s;
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(TOKEN_ATTRIBUTE, fresh);
        return fresh;
    }
}
