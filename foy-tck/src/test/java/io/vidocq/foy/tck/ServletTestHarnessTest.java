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
package io.vidocq.foy.tck;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServletTestHarnessTest {

    @Test
    void servletRegisteredOnTwoPatternsIsInitialisedOnceWithItsParams() throws Exception {
        var inits = new AtomicInteger();
        HttpServlet s = new HttpServlet() {
            @Override public void init() { inits.incrementAndGet(); }
            @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
                r.getWriter().write(getInitParameter("k"));
            }
        };
        try (var h = ServletTestHarness.builder()
                .servlet("/a", s, "S", Map.of("k", "v"))
                .servlet("/b", s, "S", Map.of("k", "v"))
                .contextPath("/app").start()) {
            // get() resolves against baseUrl(), which already carries the context path.
            assertEquals("v", h.get("/a").body());
            assertEquals("v", h.get("/b").body());
            assertEquals(1, inits.get());
        }
    }

    @Test
    void unnamedServletsOfTheSameClassStayDistinct() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("/x", writing("x"))
                .servlet("/y", writing("y"))
                .start()) {
            assertEquals("x", h.get("/x").body());
            assertEquals("y", h.get("/y").body());
        }
    }

    private static HttpServlet writing(String body) {
        return new HttpServlet() {
            @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
                r.getWriter().write(body);
            }
        };
    }
}
