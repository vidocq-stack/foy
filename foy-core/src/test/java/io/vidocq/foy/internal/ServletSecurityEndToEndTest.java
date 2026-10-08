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
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.spi.security.SecurityProvider;
import io.vidocq.foy.internal.gen.ClassFileDescriptorReader;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServletSecurityEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    private static final SecurityProvider USERS = (user, pass) -> {
        Map<String, String> db = Map.of("alice", "a-secret", "bob", "b-secret");
        Map<String, Set<String>> roles = Map.of(
                "alice", Set.of("admin"),
                "bob", Set.of("user")
        );
        return db.getOrDefault(user, "").equals(pass)
                ? Optional.of(new AuthenticatedUser(user, roles.get(user)))
                : Optional.empty();
    };

    @ServletSecurity(@HttpConstraint(rolesAllowed = "admin"))
    public static final class AdminOnlyServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain");
            resp.getWriter().write("admin:" + req.getRemoteUser());
        }
    }

    @ServletSecurity(@HttpConstraint(
            value = ServletSecurity.EmptyRoleSemantic.DENY))
    public static final class DenyAllServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("should not reach");
        }
    }

    public static final class PublicServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain");
            resp.getWriter().write("public:" + req.getRemoteUser());
        }
    }

    @Test
    void adminRequiresBasicAuthAndCorrectRole() throws Exception {
        startWith(new AdminOnlyServlet(), "/admin");

        // Sans credentials → 401
        HttpResponse<String> anon = get("/admin", null);
        assertEquals(401, anon.statusCode());
        assertTrue(anon.headers().firstValue("www-authenticate").orElse("").startsWith("Basic"));

        // Mauvais credentials → 401
        HttpResponse<String> bad = get("/admin", "alice:wrong");
        assertEquals(401, bad.statusCode());

        // Utilisateur sans le rôle admin → 403
        HttpResponse<String> forbidden = get("/admin", "bob:b-secret");
        assertEquals(403, forbidden.statusCode());

        // Admin autorisé → 200
        HttpResponse<String> ok = get("/admin", "alice:a-secret");
        assertEquals(200, ok.statusCode());
        assertEquals("admin:alice", ok.body());
    }

    @Test
    void denyAllRefusesEvenAuthenticatedUsers() throws Exception {
        startWith(new DenyAllServlet(), "/nope");
        HttpResponse<String> r = get("/nope", "alice:a-secret");
        assertEquals(403, r.statusCode());
    }

    @Test
    void servletWithoutSecurityAnnotationAllowsAnonymous() throws Exception {
        startWith(new PublicServlet(), "/pub");
        HttpResponse<String> r = get("/pub", null);
        assertEquals(200, r.statusCode());
        assertEquals("public:null", r.body());
    }

    @Test
    void authenticateMethodPropagatesUser() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, jakarta.servlet.ServletException {
                if (!req.authenticate(resp)) return;
                resp.setContentType("text/plain");
                resp.getWriter().write("u=" + req.getRemoteUser()
                        + " role=" + req.isUserInRole("admin"));
            }
        };
        startWith(s, "/me");

        assertEquals(401, get("/me", null).statusCode());
        HttpResponse<String> ok = get("/me", "alice:a-secret");
        assertEquals(200, ok.statusCode());
        assertEquals("u=alice role=true", ok.body());
    }

    private void startWith(HttpServlet servlet, String pattern) {
        var ctx = new VidocqServletContext("/");
        ctx.setSecurityProvider(USERS);
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(
                        UrlPatternMatcher.of(pattern), servlet, "S", true,
                        ClassFileDescriptorReader.read(servlet.getClass())
                                .map(WebComponentDescriptor::servletSecurity).orElse(null)))),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;
    }

    private HttpResponse<String> get(String p, String userPass) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p))
                .timeout(Duration.ofSeconds(3)).GET();
        if (userPass != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(userPass.getBytes(StandardCharsets.UTF_8)));
        }
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
