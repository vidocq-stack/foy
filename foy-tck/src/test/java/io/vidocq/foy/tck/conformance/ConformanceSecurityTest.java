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

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.tck.ServletTestHarness;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 §13 (security) conformance coverage via BASIC + @ServletSecurity. */
class ConformanceSecurityTest {

    @ServletSecurity(@HttpConstraint(rolesAllowed = "admin"))
    public static final class AdminServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("hello " + req.getRemoteUser());
        }
    }

    @Test
    void adminConstraintRejectsNonAdminAndAnonymous() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("/admin", new AdminServlet())
                .securityProvider((user, pass) -> {
                    Map<String, Set<String>> db = Map.of(
                            "alice:admin", Set.of("admin"),
                            "bob:user", Set.of("user"));
                    return db.entrySet().stream()
                            .filter(e -> e.getKey().equals(user + ":" + pass))
                            .findFirst()
                            .map(e -> new AuthenticatedUser(user,
                                    e.getValue()));
                })
                .start()) {
            assertEquals(401, h.get("/admin").statusCode());
            assertEquals(401, withAuth(h, "/admin", "alice", "wrong").statusCode());
            assertEquals(403, withAuth(h, "/admin", "bob", "user").statusCode());
            var ok = withAuth(h, "/admin", "alice", "admin");
            assertEquals(200, ok.statusCode());
            assertEquals("hello alice", ok.body());
        }
    }

    private static java.net.http.HttpResponse<String> withAuth(
            ServletTestHarness h, String path, String user, String pass) throws Exception {
        return h.send(h.request(path)
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)))
                .GET().build());
    }

    @SuppressWarnings("unused")
    private static Optional<AuthenticatedUser> unused() { return Optional.empty(); }
}
