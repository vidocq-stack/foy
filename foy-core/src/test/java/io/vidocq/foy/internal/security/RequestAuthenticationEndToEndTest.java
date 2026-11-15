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
import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.RequestAuthentication;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hook a Jakarta Authentication runtime installs around every request (Jakarta Security 4.0, section 2.5):
 * it is asked for each request, mandatory or not, and what it answers decides the request. The refusals come first.
 */
class RequestAuthenticationEndToEndTest {

    public static class Who extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("user=" + req.getRemoteUser() + " type=" + req.getAuthType()
                    + " admin=" + req.isUserInRole("admin"));
        }
    }

    public static class Ops extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            try {
                switch (req.getServletPath()) {
                    case "/out" -> req.logout();
                    case "/in" -> req.login("alice", "a-secret");
                    default -> resp.getWriter().write("authenticate=" + req.authenticate(resp));
                }
            } catch (jakarta.servlet.ServletException e) {
                resp.sendError(409, e.getMessage());
            }
        }
    }

    /** Answers {@code outcome} and remembers whether each request was mandatory. */
    private static final class Scripted implements RequestAuthentication {
        final List<Boolean> mandatory = new ArrayList<>();
        final List<String> cleaned = new ArrayList<>();
        private final Outcome outcome;

        Scripted(Outcome outcome) { this.outcome = outcome; }

        @Override public Outcome validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                 boolean mandatory) throws IOException {
            this.mandatory.add(mandatory);
            if (outcome instanceof Outcome.ResponseSent) response.sendError(418);
            return outcome;
        }

        @Override public void cleanSubject(HttpServletRequest request) { cleaned.add(request.getRequestURI()); }
    }

    private static WebAppModel model() {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("who", Who.class, Who::new, List.of("/open", "/admin/*"), Map.of(), -1, false))
                .servlet(new ServletDecl("ops", Ops.class, Ops::new, List.of("/out", "/in", "/ask"), Map.of(), -1, false))
                .securityConstraints(List.of(constraint(List.of("/admin/*"), List.of(), List.of(), List.of("admin"), null)))
                .loginConfig(new LoginConfigDef("BASIC", "Corp", null, null))
                .build();
    }

    private static SecuritySettings with(RequestAuthentication hook) {
        return SecuritySettings.DEFAULTS.withIdentityStore(SecureApp.USERS).withRequestAuthentication(hook);
    }

    @Test
    void aProtectedResourceThatNobodyIdentifiedIsRefusedNotChallenged() throws Exception {
        var hook = new Scripted(RequestAuthentication.anonymous());
        try (var app = new SecureApp(model(), with(hook))) {
            var response = app.get("/admin/x");

            assertEquals(403, response.statusCode());
            assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
            assertEquals(List.of(true), hook.mandatory);
        }
    }

    @Test
    void aResponseTheHookProducedStopsTheRequest() throws Exception {
        try (var app = new SecureApp(model(), with(new Scripted(RequestAuthentication.responseSent())))) {
            assertEquals(418, app.get("/admin/x").statusCode());
            assertEquals(418, app.get("/open").statusCode(), "an unprotected resource is asked too");
        }
    }

    @Test
    void anIdentityWithoutTheRoleIsForbidden() throws Exception {
        var hook = new Scripted(RequestAuthentication.authenticated(Identity.of("bob", Set.of("user")), "HTTP"));
        try (var app = new SecureApp(model(), with(hook))) {
            assertEquals(403, app.get("/admin/x").statusCode());
        }
    }

    @Test
    void aHookThatThrowsFailsClosed() throws Exception {
        RequestAuthentication broken = (request, response, mandatory) -> {
            throw new IllegalStateException("boom");
        };
        try (var app = new SecureApp(model(), with(broken))) {
            assertEquals(500, app.get("/open").statusCode());
            assertEquals(500, app.get("/admin/x").statusCode());
        }
    }

    @Test
    void theIdentityTheHookEstablishedReachesTheServlet() throws Exception {
        var hook = new Scripted(RequestAuthentication.authenticated(Identity.of("alice", Set.of("admin")), "HTTP"));
        try (var app = new SecureApp(model(), with(hook))) {
            var response = app.get("/admin/x");

            assertEquals(200, response.statusCode());
            assertEquals("user=alice type=HTTP admin=true", response.body());
            assertEquals("user=alice type=HTTP admin=true", app.get("/open").body());
            assertEquals(List.of(true, false), hook.mandatory);
        }
    }

    @Test
    void aLoginByPasswordIsRefusedWhileTheRuntimeOwnsAuthentication() throws Exception {
        try (var app = new SecureApp(model(), with(new Scripted(RequestAuthentication.anonymous())))) {
            assertEquals(409, app.get("/in").statusCode());
        }
    }

    @Test
    void authenticateAsksTheHookAndReportsAnonymousAsNotAuthenticated() throws Exception {
        var hook = new Scripted(RequestAuthentication.anonymous());
        try (var app = new SecureApp(model(), with(hook))) {
            assertEquals("authenticate=false", app.get("/ask").body());
            assertEquals(List.of(false, true), hook.mandatory, "the request, then authenticate() itself");
        }
    }

    @Test
    void aLogoutLetsTheHookForgetTheCaller() throws Exception {
        var hook = new Scripted(RequestAuthentication.authenticated(Identity.of("alice", Set.of("admin")), "HTTP"));
        try (var app = new SecureApp(model(), with(hook))) {
            app.get("/out");

            assertEquals(List.of("/out"), hook.cleaned);
        }
    }
}
