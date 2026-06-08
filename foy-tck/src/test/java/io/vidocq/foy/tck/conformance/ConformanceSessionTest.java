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
package io.vidocq.foy.tck.conformance;

import io.vidocq.foy.tck.ServletTestHarness;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 §7 conformance coverage (HttpSession). */
class ConformanceSessionTest {

    @Test
    void newSessionPropagatesViaJsessionidCookie() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                HttpSession session = req.getSession();
                resp.getWriter().write(session.getId());
            }
        };
        try (var h = ServletTestHarness.builder().servlet("/s", s).start()) {
            HttpResponse<String> r = h.get("/s");
            String cookie = r.headers().firstValue("set-cookie").orElse("");
            assertTrue(cookie.startsWith("JSESSIONID=" + r.body() + ";"));
            assertTrue(cookie.contains("HttpOnly"));
        }
    }

    @Test
    void sessionAttributePersistsAcrossRequestsWithSameCookie() throws Exception {
        HttpServlet counter = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                HttpSession session = req.getSession();
                Integer n = (Integer) session.getAttribute("n");
                n = n == null ? 1 : n + 1;
                session.setAttribute("n", n);
                resp.getWriter().write(Integer.toString(n));
            }
        };
        try (var h = ServletTestHarness.builder().servlet("/c", counter).start()) {
            HttpResponse<String> r1 = h.get("/c");
            assertEquals("1", r1.body());
            String sid = extractSid(r1);

            HttpResponse<String> r2 = h.send(h.request("/c")
                    .header("Cookie", "JSESSIONID=" + sid).GET().build());
            assertEquals("2", r2.body());
        }
    }

    @Test
    void invalidatedSessionDoesNotExposeAttributes() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                HttpSession session = req.getSession();
                if ("kill".equals(req.getParameter("op"))) {
                    session.invalidate();
                    resp.getWriter().write("gone");
                    return;
                }
                session.setAttribute("k", "v");
                resp.getWriter().write("ok:" + session.getId());
            }
        };
        try (var h = ServletTestHarness.builder().servlet("/s", s).start()) {
            HttpResponse<String> first = h.get("/s");
            String sid = extractSid(first);
            h.send(h.request("/s?op=kill").header("Cookie", "JSESSIONID=" + sid).GET().build());

            HttpResponse<String> next = h.send(h.request("/s")
                    .header("Cookie", "JSESSIONID=" + sid).GET().build());
            assertNotEquals(sid, extractSid(next), "new session id expected after invalidate");
        }
    }

    private static String extractSid(HttpResponse<String> r) {
        String cookie = r.headers().firstValue("set-cookie").orElse("");
        int eq = cookie.indexOf('=');
        int semi = cookie.indexOf(';', eq);
        return semi < 0 ? cookie.substring(eq + 1) : cookie.substring(eq + 1, semi);
    }
}
