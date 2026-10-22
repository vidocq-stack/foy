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
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WebSecurityEndToEndTest {

    public static class Who extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if ("/boom".equals(req.getServletPath())) throw new IllegalStateException("boom");
            resp.getWriter().write("user=" + req.getRemoteUser() + " type=" + req.getAuthType());
        }
    }

    public static class Forwarder extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.getRequestDispatcher("/admin/x").forward(req, resp);
        }
    }

    /** An error page; it is itself under the "/admin/*" constraint, which an ERROR dispatch never checks. */
    public static class ErrorPage extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("page status=" + req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)
                    + " type=" + req.getDispatcherType());
        }
    }

    /** Counts the request listener events of the denial tests. */
    public static class Counting implements ServletRequestListener {
        static final AtomicInteger INITIALIZED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();
        @Override public void requestInitialized(ServletRequestEvent sre) { INITIALIZED.incrementAndGet(); }
        @Override public void requestDestroyed(ServletRequestEvent sre) { DESTROYED.incrementAndGet(); }
    }

    private static ServletDecl who(String... patterns) {
        return new ServletDecl("who", Who.class, Who::new, List.of(patterns), Map.of(), -1, false);
    }

    private static WebAppModel.Builder adminApp() {
        return WebAppModel.builder("/")
                .servlet(who("/admin/*", "/open", "/form", "/h", "/boom"))
                .servlet(new ServletDecl("fwd", Forwarder.class, Forwarder::new, List.of("/fwd"), Map.of(), -1, false))
                .securityConstraints(List.of(
                        constraint(List.of("/admin/*", "/static/*", "/boom"), List.of(), List.of(), List.of("admin"), null),
                        constraint(List.of("/form"), List.of(), List.of("GET"), List.of("admin"), null),
                        constraint(List.of("/h"), List.of("GET"), List.of(), List.of("admin"), null)))
                .loginConfig(new LoginConfigDef("BASIC", "Corp", null, null));
    }

    @Test
    void descriptorConstraintChallengesForbidsAndAdmits() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            var anon = app.get("/admin/x");
            assertEquals(401, anon.statusCode());
            assertEquals("Basic realm=\"Corp\", charset=\"UTF-8\"", anon.headers().firstValue("WWW-Authenticate").orElse(""));
            assertEquals(403, app.send("GET", "/admin/x", "bob:b-secret").statusCode());
            var ok = app.send("GET", "/admin/x", "alice:a-secret");
            assertEquals(200, ok.statusCode());
            assertEquals("user=alice type=BASIC", ok.body());
        }
    }

    @Test
    void theDefaultRealmIsNeutral() throws Exception {
        try (var app = new SecureApp(adminApp().loginConfig(null).build())) {
            assertEquals("Basic realm=\"Restricted\", charset=\"UTF-8\"",
                    app.get("/admin/x").headers().firstValue("WWW-Authenticate").orElse(""));
        }
    }

    @Test
    void constraintAppliesBeforeTheDefaultServlet() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            assertEquals(401, app.get("/static/missing.txt").statusCode(), "not 404: checked before the target");
        }
    }

    @Test
    void canonicalPathDrivesTheConstraint() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            assertEquals(401, app.get("/%61dmin/x").statusCode());
            assertEquals(401, app.get("/admin;p=1/x").statusCode());
            assertEquals(401, app.get("/admin;jsessionid=abc/x").statusCode());
            // An encoded slash or semicolon never turns into a separator that escapes the constraint.
            var slash = app.get("/admin%2Fx");
            assertEquals(400, slash.statusCode(), "%2F: " + slash.body());
            // %3B decodes to a literal ";" inside the segment "admin;p=1": another name than "admin", so no
            // servlet of "/admin/*" is reached and the default servlet answers 404.
            var semi = app.get("/admin%3Bp=1/x");
            assertEquals(404, semi.statusCode(), "%3B: " + semi.body());
            assertFalse(semi.body().contains("user="));
            var dots = app.get("/open/../admin/x");
            assertTrue(List.of(400, 401, 404).contains(dots.statusCode()), String.valueOf(dots.statusCode()));
            assertFalse(dots.body().contains("user="));
        }
    }

    @Test
    void headIsCheckedAsItsOwnMethod() throws Exception {
        try (var app = new SecureApp(adminApp().denyUncoveredHttpMethods(true).build())) {
            assertEquals(401, app.send("GET", "/h", null).statusCode());
            assertEquals(403, app.send("HEAD", "/h", "alice:a-secret").statusCode(), "HEAD is uncovered, not GET");
        }
    }

    @Test
    void omissionsAndUncoveredMethods() throws Exception {
        try (var log = LogCapture.of(WebAppDeployer.class.getName()); var app = new SecureApp(adminApp().build())) {
            assertEquals(401, app.send("POST", "/form", null).statusCode());
            assertEquals(200, app.send("GET", "/form", null).statusCode(), "GET is uncovered and permitted");
            assertTrue(log.warnings().stream().anyMatch(w -> w.contains("'/form'") && w.contains("[GET]")), log.warnings().toString());
        }
        try (var app = new SecureApp(adminApp().denyUncoveredHttpMethods(true).build())) {
            assertEquals(403, app.send("GET", "/form", "alice:a-secret").statusCode());
            assertEquals(403, app.send("PUT", "/h", "alice:a-secret").statusCode());
        }
    }

    @Test
    void emptyAuthConstraintExcludesEveryone() throws Exception {
        var model = WebAppModel.builder("/").servlet(who("/x"))
                .securityConstraints(List.of(constraint(List.of("/x"), List.of(), List.of(), List.of(), null))).build();
        try (var app = new SecureApp(model)) {
            assertEquals(403, app.send("GET", "/x", "alice:a-secret").statusCode());
            assertEquals(403, app.get("/x").statusCode());
        }
    }

    @Test
    void theDescriptorBeatsTheAnnotationOnAnExactPattern() throws Exception {
        var annotated = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "admin"));
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("who", Who.class, Who::new, List.of("/a", "/b"), Map.of(), -1, false, annotated))
                .securityConstraints(List.of(constraint(List.of("/a"), List.of("GET"), List.of(), null, null))).build();
        try (var app = new SecureApp(model)) {
            assertEquals(200, app.get("/a").statusCode());
            assertEquals(401, app.get("/b").statusCode());
        }
    }

    @Test
    void aForwardIsNotChecked() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            var r = app.get("/fwd");
            assertEquals(200, r.statusCode());
            assertEquals("user=null type=null", r.body());
        }
    }

    @Test
    void malformedBasicHeaderIs401Not500() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            // "A" * 4000 decodes to NUL bytes (control characters). It stays under chappe's default 8 KiB
            // header limit, which would answer before Foy; the oversize case is covered by BasicAuthenticatorTest.
            for (String h : List.of("Basic %%%%", "Basic", "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8)),
                    "Basic " + "A".repeat(4000), "Digest x")) {
                var r = app.send(app.request("/admin/x").header("Authorization", h).GET());
                assertEquals(401, r.statusCode(), h);
            }
        }
    }

    @Test
    void credentialsNeverLogged() throws Exception {
        String token = Base64.getEncoder().encodeToString("alice:a-secret".getBytes(StandardCharsets.UTF_8));
        // "io.vidocq.foy" receives the records of every Foy logger (the bridge logs the failure at
        // ERROR with the exception attached; LogCapture.warnings() alone would see nothing).
        try (var log = LogCapture.of("io.vidocq.foy"); var app = new SecureApp(adminApp().build())) {
            assertEquals(500, app.send("GET", "/boom", "alice:a-secret").statusCode());
            assertFalse(log.thrown().isEmpty(), "the failure was logged");
            List<String> logged = new java.util.ArrayList<>(log.warnings());
            for (Throwable t : log.thrown()) logged.add(String.valueOf(t));
            for (String w : logged) {
                assertFalse(w.contains("a-secret"), w);
                assertFalse(w.contains(token), w);
            }
        }
    }

    // ---- Welcome files (section 10.10): the redirect and the welcome target are both checked.

    @TempDir Path root;

    private void files() throws IOException {
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve("secret/index.html"), "secret index");
        Files.createDirectories(root.resolve("pub"));
        Files.writeString(root.resolve("pub/index.html"), "pub index");
        Files.writeString(root.resolve("pub/other.txt"), "other");
    }

    @Test
    void theDirectoryRedirectIsChecked() throws Exception {
        files();
        var model = WebAppModel.builder("/").welcomeFiles(List.of("index.html"))
                .securityConstraints(List.of(constraint(List.of("/secret/*"), List.of(), List.of(), List.of("admin"), null)))
                .build();
        try (var app = new SecureApp(model, root)) {
            var r = app.get("/secret");
            assertEquals(401, r.statusCode(), "not the 302 to /secret/");
            assertEquals("Basic realm=\"Restricted\", charset=\"UTF-8\"", r.headers().firstValue("WWW-Authenticate").orElse(""));
            assertTrue(r.headers().firstValue("Location").isEmpty());
            assertEquals(302, app.send("GET", "/secret", "alice:a-secret").statusCode());
        }
    }

    @Test
    void aResourceRequestedWithAnotherCaseIsNotServedAroundAConstraint() throws Exception {
        Files.createDirectories(root.resolve("static"));
        Files.writeString(root.resolve("static/x.txt"), "static x");
        assumeTrue(Files.exists(root.resolve("STATIC/X.TXT")), "only meaningful on a case-insensitive file system");
        var model = WebAppModel.builder("/")
                .securityConstraints(List.of(constraint(List.of("/static/*"), List.of(), List.of(), List.of("admin"), null)))
                .build();
        try (var app = new SecureApp(model, root)) {
            assertEquals(401, app.get("/static/x.txt").statusCode());
            var r = app.get("/STATIC/x.txt");
            assertEquals(404, r.statusCode(), r.body());
            assertFalse(r.body().contains("static x"));
        }
    }

    @Test
    void aWelcomeFileRequestedWithAnotherCaseIsNotServedAroundAConstraint() throws Exception {
        Files.createDirectories(root.resolve("static"));
        Files.writeString(root.resolve("static/index.html"), "static index");
        assumeTrue(Files.exists(root.resolve("STATIC/INDEX.HTML")), "only meaningful on a case-insensitive file system");
        var model = WebAppModel.builder("/").welcomeFiles(List.of("index.html"))
                .securityConstraints(List.of(constraint(List.of("/static/*"), List.of(), List.of(), List.of("admin"), null)))
                .build();
        try (var app = new SecureApp(model, root)) {
            assertEquals(401, app.get("/static/").statusCode());
            var r = app.get("/STATIC/");
            assertNotEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains("static index"));
            assertEquals(404, r.statusCode());
        }
    }

    @Test
    void theWelcomeTargetIsChecked() throws Exception {
        files();
        var model = WebAppModel.builder("/").welcomeFiles(List.of("index.html"))
                .securityConstraints(List.of(constraint(List.of("/pub/index.html"), List.of(), List.of(), List.of("admin"), null)))
                .build();
        try (var app = new SecureApp(model, root)) {
            var r = app.get("/pub/");
            assertEquals(401, r.statusCode(), r.body());
            assertFalse(r.body().contains("pub index"));
            assertEquals("other", app.get("/pub/other.txt").body(), "the directory itself is not constrained");
            var ok = app.send("GET", "/pub/", "alice:a-secret");
            assertEquals(200, ok.statusCode());
            assertEquals("pub index", ok.body());
        }
    }

    // ---- Controller ruling S-9: a denial goes through the error-page handling, as sendError does.

    private static WebAppModel.Builder appWithErrorPages() {
        return adminApp()
                .servlet(new ServletDecl("page", ErrorPage.class, ErrorPage::new, List.of("/admin/denied"),
                        Map.of(), -1, false))
                .errorPages(new ErrorPageRegistry().register(403, "/admin/denied").register(401, "/admin/denied"));
    }

    @Test
    void aForbiddenRequestShowsThe403ErrorPage() throws Exception {
        try (var app = new SecureApp(appWithErrorPages().build())) {
            var r = app.send("GET", "/admin/x", "bob:b-secret");
            assertEquals(403, r.statusCode());
            assertEquals("page status=403 type=ERROR", r.body());
        }
    }

    @Test
    void aChallengeShowsThe401ErrorPageAndKeepsTheChallenge() throws Exception {
        try (var app = new SecureApp(appWithErrorPages().build())) {
            var r = app.get("/admin/x");
            assertEquals(401, r.statusCode());
            assertEquals("Basic realm=\"Corp\", charset=\"UTF-8\"", r.headers().firstValue("WWW-Authenticate").orElse(""));
            assertEquals("page status=401 type=ERROR", r.body());
        }
    }

    @Test
    void aDeniedRequestEndsItsListenersExactlyOnce() throws Exception {
        Counting.INITIALIZED.set(0);
        Counting.DESTROYED.set(0);
        var model = appWithErrorPages().listener(new ListenerDecl(Counting.class, Counting::new)).build();
        try (var app = new SecureApp(model)) {
            assertEquals(403, app.send("GET", "/admin/x", "bob:b-secret").statusCode());
            assertEquals(401, app.get("/static/missing.txt").statusCode());
        }
        assertEquals(2, Counting.INITIALIZED.get());
        assertEquals(2, Counting.DESTROYED.get());
    }

    // ---- 6.4 review: "*" means every declared role, @DeclareRoles included (they land in the model's roles).

    @Test
    void starMeansEveryDeclaredRole() throws Exception {
        var model = WebAppModel.builder("/").servlet(who("/x"))
                .securityRoles(List.of("admin"))
                .securityConstraints(List.of(constraint(List.of("/x"), List.of(), List.of(), List.of("*"), null))).build();
        try (var app = new SecureApp(model)) {
            assertEquals(200, app.send("GET", "/x", "alice:a-secret").statusCode());
            assertEquals(403, app.send("GET", "/x", "bob:b-secret").statusCode(), "'user' is not a declared role");
            assertEquals(401, app.get("/x").statusCode());
        }
    }

    @Test
    void anUnsupportedAuthMethodFailsClosed() throws Exception {
        try (var app = new SecureApp(adminApp().loginConfig(new LoginConfigDef("DIGEST", null, null, null)).build())) {
            var r = app.send("GET", "/admin/x", "alice:a-secret");
            assertEquals(403, r.statusCode());
            assertTrue(r.headers().firstValue("WWW-Authenticate").isEmpty());
            assertEquals(200, app.get("/open").statusCode());
        }
    }
}
