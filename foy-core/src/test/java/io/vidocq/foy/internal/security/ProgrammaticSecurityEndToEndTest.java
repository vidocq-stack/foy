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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProgrammaticSecurityEndToEndTest {

    public static class Ops extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            var w = new StringBuilder();
            switch (req.getServletPath()) {
                case "/login" -> {
                    req.getSession().setAttribute("cart", "1");
                    String before = req.getSession().getId();
                    req.login("alice", "a-secret");
                    w.append("user=").append(req.getRemoteUser()).append(" type=").append(req.getAuthType())
                            .append(" rotated=").append(!before.equals(req.getSession().getId()));
                }
                case "/twice" -> {
                    req.login("alice", "a-secret");
                    try { req.login("alice", "a-secret"); w.append("no exception"); }
                    catch (ServletException _) { w.append("second login refused"); }
                }
                case "/session" -> { req.getSession(); w.append("session"); }
                case "/bad" -> {
                    try { req.login("alice", "wrong-secret"); w.append("no exception"); }
                    catch (ServletException e) {
                        w.append("refused:").append(e.getMessage().contains("wrong-secret")).append(':').append(e.getMessage());
                    }
                }
                case "/logout" -> {
                    // FORM restores the identity cached by the previous /login request.
                    if (req.getRemoteUser() == null) req.login("alice", "a-secret");
                    String id = req.getSession().getId();
                    req.logout();
                    w.append("user=").append(req.getRemoteUser()).append(" type=").append(req.getAuthType())
                            .append(" principal=").append(req.getUserPrincipal())
                            .append(" sameSession=").append(id.equals(req.getSession(false).getId()))
                            .append(" cart=").append(req.getSession(false).getAttribute("cart"));
                }
                case "/authenticate" -> {
                    if (!req.authenticate(resp)) return;
                    w.append("authenticated=").append(req.getRemoteUser());
                }
                case "/after-login" -> {
                    req.login("alice", "a-secret");
                    w.append("authenticate=").append(req.authenticate(resp));
                }
                case "/committed" -> {
                    resp.getWriter().write("x");
                    resp.flushBuffer();
                    try { req.authenticate(resp); } catch (IllegalStateException _) { resp.getWriter().write(" ISE"); }
                    return;
                }
                default -> w.append("user=").append(req.getRemoteUser());
            }
            resp.getWriter().write(w.toString());
        }
    }

    private static SecureApp app(String authMethod) {
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("ops", Ops.class, Ops::new,
                        List.of("/login", "/session", "/twice", "/bad", "/logout", "/authenticate", "/after-login",
                                "/committed", "/whoami", "/loginpage"), Map.of(), -1, false))
                .loginConfig(new LoginConfigDef(authMethod, null, "/loginpage", "/loginpage"))
                .build();
        return new SecureApp(model);
    }

    @Test
    void loginUsesTheConfiguredMechanismAndRotatesAnExistingSession() throws Exception {
        try (var app = app("FORM")) {
            assertEquals("user=alice type=FORM rotated=true", app.get("/login").body());
            assertEquals("user=alice", app.get("/whoami").body(), "FORM: the identity is kept in the session");
        }
        try (var app = app("BASIC")) {
            assertEquals("user=alice type=BASIC rotated=true", app.get("/login").body());
            assertEquals("user=null", app.get("/whoami").body(), "BASIC: request-scoped");
        }
    }

    @Test
    void loginRotatesAnExistingSession() throws Exception {
        try (var app = app("FORM")) {
            app.get("/session");
            String before = app.sessionId();
            assertNotNull(before);
            app.get("/login");
            String after = app.sessionId();
            assertNotEquals(before, after, "the client received the new id");
            assertEquals("user=alice", app.get("/whoami").body());
            assertEquals("user=null", app.getWithSession("/whoami", before).body(), "the old id is dead");
        }
    }

    @Test
    void loginTwiceAndWrongPasswordThrowWithoutEchoingThePassword() throws Exception {
        try (var app = app("BASIC")) {
            assertEquals("second login refused", app.get("/twice").body());
            assertEquals("refused:false:login failed", app.get("/bad").body());
        }
    }

    @Test
    void logoutClearsTheIdentityButKeepsTheSession() throws Exception {
        try (var app = app("FORM")) {
            app.get("/login");
            assertEquals("user=null type=null principal=null sameSession=true cart=1", app.get("/logout").body());
            assertEquals("user=null", app.get("/whoami").body());
        }
    }

    @Test
    void authenticateFollowsTheMechanism() throws Exception {
        try (var app = app("BASIC")) {
            var challenge = app.get("/authenticate");
            assertEquals(401, challenge.statusCode());
            assertTrue(challenge.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic realm="));
            assertEquals("authenticated=alice", app.send("GET", "/authenticate", "alice:a-secret").body());
            assertEquals("authenticate=true", app.get("/after-login").body());
            assertEquals("x ISE", app.get("/committed").body());
        }
        try (var app = app("FORM")) {
            assertEquals("user=null", app.get("/authenticate").body(), "forwarded to the login page (/loginpage)");
        }
    }

    @Test
    void loginUnderAnUnsupportedMechanismThrows() throws Exception {
        try (var app = app("DIGEST")) {
            assertEquals("refused:false:the configured authentication mechanism does not support username/password login",
                    app.get("/bad").body());
        }
    }
}
