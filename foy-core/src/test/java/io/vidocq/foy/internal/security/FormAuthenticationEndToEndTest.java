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

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class FormAuthenticationEndToEndTest {

    public static class Page extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String p = req.getServletPath();
            var w = resp.getWriter();
            switch (p) {
                case "/login" -> w.write("LOGIN PAGE");
                case "/error" -> w.write("LOGIN ERROR");
                case "/put" -> { req.getSession().setAttribute("cart", "1"); w.write("put"); }
                default -> w.write("user=" + req.getRemoteUser() + " type=" + req.getAuthType()
                        + " cart=" + (req.getSession(false) == null ? null : req.getSession(false).getAttribute("cart")));
            }
        }
    }

    private static WebAppModel formModel(List<SecurityConstraintDef> constraints) {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("page", Page.class, Page::new,
                        List.of("/login", "/error", "/put", "/open", "/secure/*"), Map.of(), -1, false))
                .securityConstraints(constraints)
                .loginConfig(new LoginConfigDef("FORM", null, "/login", "/error"))
                .build();
    }

    private static SecureApp formApp() {
        return new SecureApp(formModel(List.of(
                constraint(List.of("/secure/*"), List.of(), List.of(), List.of("admin"), null))));
    }

    @Test
    void protectedGetForwardsToTheLoginPage() throws Exception {
        try (var app = formApp()) {
            var r = app.get("/secure/page?x=1");
            assertEquals(200, r.statusCode());
            assertEquals("LOGIN PAGE", r.body());
            assertTrue(r.headers().firstValue("Location").isEmpty());
            assertNotNull(app.sessionId(), "the saved target lives in a session");
        }
    }

    @Test
    void loginRotatesTheSessionAndKeepsItsAttributes() throws Exception {
        try (var app = formApp()) {
            app.get("/put");
            app.get("/secure/page?x=1");
            String before = app.sessionId();
            var login = app.postForm("/j_security_check", "j_username=alice&j_password=a-secret");
            assertEquals(302, login.statusCode());
            URI location = URI.create(login.headers().firstValue("Location").orElseThrow());
            // S-2: the session id travels in a cookie, so the redirect does not carry it.
            assertEquals("/secure/page", location.getPath());
            assertEquals("x=1", location.getQuery());
            String after = app.sessionId();
            assertNotEquals(before, after, "session id rotated");
            var page = app.get("/secure/page?x=1");
            assertEquals("user=alice type=FORM cart=1", page.body());
            assertEquals("LOGIN PAGE", app.getWithSession("/secure/page", before).body(),
                    "the old id no longer authenticates");
        }
    }

    @Test
    void aClientTrackedByUrlGetsTheRotatedIdInTheRedirect() throws Exception {
        try (var app = formApp()) {
            HttpClient noCookies = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            var challenge = noCookies.send(app.request("/secure/page?x=1").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("LOGIN PAGE", challenge.body());
            String before = challenge.headers().allValues("Set-Cookie").stream()
                    .filter(c -> c.startsWith(SessionManager.COOKIE_NAME + "="))
                    .map(c -> c.substring(SessionManager.COOKIE_NAME.length() + 1).split(";", 2)[0])
                    .findFirst().orElseThrow();
            var login = noCookies.send(app.request("/j_security_check;jsessionid=" + before)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("j_username=alice&j_password=a-secret")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(302, login.statusCode());
            URI location = URI.create(login.headers().firstValue("Location").orElseThrow());
            assertTrue(location.getPath().startsWith("/secure/page;jsessionid="), location.toString());
            String after = location.getPath().substring("/secure/page;jsessionid=".length());
            assertNotEquals(before, after, "session id rotated");
            assertEquals("x=1", location.getQuery());
            var page = noCookies.send(app.request(location.getRawPath() + "?" + location.getRawQuery()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("user=alice type=FORM cart=null", page.body());
        }
    }

    @Test
    void theEmbedderMayKeepTheSessionIdOnLogin() throws Exception {
        var model = formModel(List.of(constraint(List.of("/secure/*"), List.of(), List.of(), List.of("admin"), null)));
        var settings = SecuritySettings.DEFAULTS.withIdentityStore(SecureApp.USERS).withRotateSessionIdOnLogin(false);
        try (var app = new SecureApp(model, settings)) {
            app.get("/secure/page");
            String before = app.sessionId();
            assertEquals(302, app.postForm("/j_security_check", "j_username=alice&j_password=a-secret").statusCode());
            assertEquals(before, app.sessionId());
            assertEquals("user=alice type=FORM cart=null", app.get("/secure/page").body());
        }
    }

    @Test
    void wrongPasswordForwardsToTheErrorPage() throws Exception {
        try (var app = formApp()) {
            app.get("/secure/page");
            var r = app.postForm("/j_security_check", "j_username=alice&j_password=nope");
            assertEquals("LOGIN ERROR", r.body());
            assertEquals("LOGIN PAGE", app.get("/secure/page").body());
        }
    }

    @Test
    void theLoginActionIsPostOnly() throws Exception {
        try (var app = formApp()) {
            var r = app.get("/j_security_check?j_username=alice&j_password=a-secret");
            assertEquals(405, r.statusCode());
            assertEquals("POST", r.headers().firstValue("Allow").orElse(""));
            assertEquals("LOGIN PAGE", app.get("/secure/page").body());
        }
    }

    @Test
    void noSavedTargetRedirectsToTheContextRoot() throws Exception {
        try (var app = formApp()) {
            var r = app.send(app.request("/j_security_check")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Referer", "http://evil.example/")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "j_username=alice&j_password=a-secret&redirect=http%3A%2F%2Fevil.example%2F")));
            assertEquals(302, r.statusCode());
            URI location = URI.create(r.headers().firstValue("Location").orElseThrow());
            assertEquals("127.0.0.1", location.getHost());
            assertEquals("/", location.getPath());
        }
    }

    @Test
    void protectedPostIsNotReplayed() throws Exception {
        try (var app = formApp()) {
            assertEquals("LOGIN PAGE", app.postForm("/secure/order", "qty=1").body());
            var login = app.postForm("/j_security_check", "j_username=alice&j_password=a-secret");
            assertEquals("/", URI.create(login.headers().firstValue("Location").orElseThrow()).getPath());
        }
    }

    @Test
    void theIdentityFollowsTheSessionToUnprotectedPages() throws Exception {
        try (var app = formApp()) {
            app.get("/secure/page");
            app.postForm("/j_security_check", "j_username=alice&j_password=a-secret");
            assertEquals("user=alice type=FORM cart=null", app.get("/open").body());
        }
    }

    @Test
    void aUserWithoutTheRoleGets403AfterLogin() throws Exception {
        try (var app = formApp()) {
            app.get("/secure/page");
            app.postForm("/j_security_check", "j_username=bob&j_password=b-secret");
            assertEquals(403, app.get("/secure/page").statusCode());
        }
    }

    @Test
    void aBasicHeaderDoesNotAuthenticateAFormApplication() throws Exception {
        try (var app = formApp()) {
            assertEquals("LOGIN PAGE", app.send("GET", "/secure/page", "alice:a-secret").body());
        }
    }

    @Test
    void aConfidentialApplicationRefusesCredentialsOverPlainHttp() throws Exception {
        // S-6: the transport guarantee is checked before the login action; the store is never asked.
        var calls = new AtomicInteger();
        IdentityStore counting = (user, password) -> {
            calls.incrementAndGet();
            return SecureApp.USERS.validate(user, password);
        };
        var model = formModel(List.of(
                constraint(List.of("/*"), List.of(), List.of(), null, "CONFIDENTIAL")));
        try (var app = new SecureApp(model, SecuritySettings.DEFAULTS.withIdentityStore(counting))) {
            var r = app.postForm("/j_security_check", "j_username=alice&j_password=a-secret");
            assertEquals(403, r.statusCode());
            assertTrue(r.headers().firstValue("Location").isEmpty());
            assertEquals(0, calls.get(), "credentials sent over plain HTTP are never validated");
        }
    }

    @Test
    void passwordNeverLogged() throws Exception {
        // Every Foy logger (bridge, deployer, CredentialCheck, WebSecurity), every level's attached exception.
        try (var log = LogCapture.of("io.vidocq.foy"); var app = formApp()) {
            app.get("/secure/page");
            assertEquals(302, app.postForm("/j_security_check", "j_username=alice&j_password=a-secret").statusCode());
            assertEquals("LOGIN ERROR",
                    app.postForm("/j_security_check", "j_username=alice&j_password=wrong-secret").body());
            List<String> logged = new ArrayList<>(log.warnings());
            for (Throwable t : log.thrown()) logged.add(String.valueOf(t));
            for (String w : logged) {
                assertFalse(w.contains("a-secret") || w.contains("wrong-secret"), w);
            }
        }
    }
}
