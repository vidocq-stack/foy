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
import io.vidocq.foy.spi.security.AccessControl;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The access decision of a Jakarta Authorization runtime (Servlet 6.1 section 13, Jakarta Authorization 3.0): it is
 * asked for each request, once the caller is known, and may agree with the constraints of the application or not.
 */
class AccessControlEndToEndTest {

    public static class Who extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("user=" + req.getRemoteUser());
        }
    }

    private record Asked(String path, String method, String caller, boolean constraintsPermit) { }

    /** Answers {@code verdict} to every request and notes what it was asked. */
    private static final class Scripted implements AccessControl {
        final List<Asked> asked = new ArrayList<>();
        private final boolean verdict;

        Scripted(boolean verdict) { this.verdict = verdict; }

        @Override public boolean permits(String path, String method, Identity caller, Constraints constraints) {
            asked.add(new Asked(path, method, caller == null ? null : caller.name(),
                    constraints.permits(path, method, caller)));
            return verdict;
        }
    }

    private static WebAppModel model() {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("who", Who.class, Who::new, List.of("/open", "/admin/*"), Map.of(), -1, false))
                .securityConstraints(List.of(constraint(List.of("/admin/*"), List.of(), List.of(), List.of("admin"), null)))
                .loginConfig(new LoginConfigDef("BASIC", "Corp", null, null))
                .build();
    }

    private static RequestAuthentication hook(RequestAuthentication.Outcome outcome) {
        return (request, response, mandatory) -> outcome;
    }

    private static SecuritySettings with(AccessControl control, RequestAuthentication hook) {
        return SecuritySettings.DEFAULTS.withIdentityStore(SecureApp.USERS)
                .withRequestAuthentication(hook).withAccessControl(control);
    }

    @Test
    void aPolicyThatGrantsAProtectedResourceToNobodyKnownLetsAnonymousThrough() throws Exception {
        var control = new Scripted(true);
        try (var app = new SecureApp(model(), with(control, hook(RequestAuthentication.anonymous())))) {
            var response = app.get("/admin/x");

            assertEquals(200, response.statusCode());
            assertEquals("user=null", response.body());
            assertEquals(new Asked("/admin/x", "GET", null, false), control.asked.getFirst(),
                    "the constraints alone would have refused");
        }
    }

    @Test
    void aPolicyThatRefusesAnUnprotectedResourceForbidsIt() throws Exception {
        var control = new Scripted(false);
        try (var app = new SecureApp(model(), with(control, hook(RequestAuthentication.anonymous())))) {
            assertEquals(403, app.get("/open").statusCode());
            assertEquals(new Asked("/open", "GET", null, true), control.asked.getFirst());
        }
    }

    @Test
    void theCallerTheHookIdentifiedIsTheOneThePolicyIsAskedAbout() throws Exception {
        var control = new Scripted(true);
        var hookOutcome = RequestAuthentication.authenticated(Identity.of("alice", Set.of("admin")), "HTTP");
        try (var app = new SecureApp(model(), with(control, hook(hookOutcome)))) {
            assertEquals(200, app.get("/admin/x").statusCode());
            assertEquals(new Asked("/admin/x", "GET", "alice", true), control.asked.getFirst());
        }
    }

    @Test
    void aPolicyThatFailsClosesTheRequest() throws Exception {
        AccessControl broken = (path, method, caller, constraints) -> {
            throw new IllegalStateException("boom");
        };
        try (var app = new SecureApp(model(), with(broken, hook(RequestAuthentication.anonymous())))) {
            assertEquals(500, app.get("/open").statusCode());
        }
    }

    @Test
    void withoutAPolicyTheConstraintsDecide() throws Exception {
        var settings = SecuritySettings.DEFAULTS.withIdentityStore(SecureApp.USERS)
                .withRequestAuthentication(hook(RequestAuthentication.anonymous()));
        assertNull(settings.accessControl());
        try (var app = new SecureApp(model(), settings)) {
            assertEquals(403, app.get("/admin/x").statusCode());
            assertEquals(200, app.get("/open").statusCode());
        }
    }
}
