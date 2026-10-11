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

import io.vidocq.foy.internal.TlsTestCertificates;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** CLIENT-CERT end to end, over real TLS handshakes, on HTTP/1.1 and HTTP/2. */
class ClientCertEndToEndTest {

    static final String X509 = "jakarta.servlet.request.X509Certificate";

    static final IdentityStore STORE = InMemoryIdentityStore.builder()
            .certificateUser(TlsTestCertificates.ALICE_DN, "admin")
            .user("carol", "c-secret".toCharArray(), "admin")
            .build();

    public static class Who extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("user=" + req.getRemoteUser() + " type=" + req.getAuthType()
                    + " admin=" + req.isUserInRole("ADM"));
        }
    }

    public static class Login extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws IOException, ServletException {
            req.login("carol", "c-secret");
            resp.getWriter().write(req.getRemoteUser() + " " + req.getAuthType());
        }
    }

    public static class Auth extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws IOException, ServletException {
            boolean ok = req.authenticate(resp);
            resp.getWriter().write("authenticate=" + ok + " user=" + req.getRemoteUser());
        }
    }

    /** Reports the X509 attribute of the request it serves. */
    public static class Attr extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var v = (X509Certificate[]) req.getAttribute(X509);
            resp.getWriter().write("user=" + req.getRemoteUser() + " attributeLength=" + (v == null ? -1 : v.length));
        }
    }

    /** Replaces the X509 attribute with an empty chain before the chain continues. */
    public static class Wipe implements Filter {
        @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            req.setAttribute(X509, new X509Certificate[0]);
            chain.doFilter(req, res);
        }
    }

    private static WebAppModel.Builder base() {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("who", Who.class, Who::new, List.of("/secure/*"), Map.of(), -1, false))
                .servlet(new ServletDecl("login", Login.class, Login::new, List.of("/login"), Map.of(), -1, false))
                .servlet(new ServletDecl("auth", Auth.class, Auth::new, List.of("/auth"), Map.of(), -1, false))
                .servlet(new ServletDecl("attr", Attr.class, Attr::new, List.of("/attr/*", "/secure-attr/*"), Map.of(), -1, false))
                .securityRoleRefs("who", Map.of("ADM", "admin"))
                .securityRoles(List.of("admin"))
                .securityConstraints(List.of(SecureApp.constraint(List.of("/secure/*", "/secure-attr/*"), List.of(), List.of(),
                        List.of("admin"), "CONFIDENTIAL")))
                .loginConfig(new LoginConfigDef("CLIENT-CERT", null, null, null));
    }

    private static SecureApp tls(WebAppModel model, IdentityStore store, String client, HttpClient.Version v) {
        var certs = TlsTestCertificates.get();
        return new SecureApp(model, SecuritySettings.DEFAULTS.withIdentityStore(store), certs.serverContext(),
                client == null ? certs.clientContextWithoutCertificate() : certs.clientContext(client), v);
    }

    @ParameterizedTest @EnumSource(value = HttpClient.Version.class, names = {"HTTP_1_1", "HTTP_2"})
    void aMappedCertificateIsAuthenticated(HttpClient.Version version) throws Exception {
        try (var app = tls(base().build(), STORE, "alice", version)) {
            var r = app.get("/secure/x");
            assertEquals(200, r.statusCode());
            assertEquals("user=" + TlsTestCertificates.ALICE_DN + " type=CLIENT_CERT admin=true", r.body());
        }
    }

    @ParameterizedTest @EnumSource(value = HttpClient.Version.class, names = {"HTTP_1_1", "HTTP_2"})
    void noCertificateIsForbidden(HttpClient.Version version) throws Exception {
        try (var app = tls(base().build(), STORE, null, version)) {
            var r = app.get("/secure/x");
            assertEquals(403, r.statusCode());
            assertTrue(r.headers().firstValue("WWW-Authenticate").isEmpty());
        }
    }

    @ParameterizedTest @EnumSource(value = HttpClient.Version.class, names = {"HTTP_1_1", "HTTP_2"})
    void aTrustedButUnmappedCertificateIsForbidden(HttpClient.Version version) throws Exception {
        try (var app = tls(base().build(), STORE, "bob", version)) {
            assertEquals(403, app.get("/secure/x").statusCode());
        }
    }

    @Test
    void aThrowingStoreIsForbidden() throws Exception {
        IdentityStore throwing = new IdentityStore() {
            @Override public Optional<Identity> validate(String username, char[] password) { return Optional.empty(); }
            @Override public Optional<Identity> validate(X509Certificate[] chain) { throw new IllegalStateException("boom"); }
        };
        try (var app = tls(base().build(), throwing, "alice", HttpClient.Version.HTTP_1_1)) {
            assertEquals(403, app.get("/secure/x").statusCode());
        }
    }

    @Test
    void plainHttpIsRefusedByTheTransportGuarantee() throws Exception {
        // With an HTTPS port the refusal is a redirect, which only the transport guarantee produces.
        var settings = SecuritySettings.DEFAULTS.withIdentityStore(STORE).withConfidentialPort(8443);
        try (var app = new SecureApp(base().build(), settings)) {
            var r = app.get("/secure/x");
            assertEquals(302, r.statusCode());
            assertEquals("https://127.0.0.1:8443/secure/x", r.headers().firstValue("Location").orElse(""));
        }
        try (var app = new SecureApp(base().build(), SecuritySettings.DEFAULTS.withIdentityStore(STORE))) {
            assertEquals(403, app.get("/secure/x").statusCode());
        }
    }

    @Test
    void loginWithAPasswordIsAllowed() throws Exception {
        try (var app = tls(base().build(), STORE, null, HttpClient.Version.HTTP_1_1)) {
            var r = app.get("/login");
            assertEquals(200, r.statusCode());
            assertEquals("carol CLIENT_CERT", r.body());
        }
    }

    @Test
    void authenticationReadsTheConnectionNotTheAttribute() throws Exception {
        var model = base()
                .filter(new FilterDecl("wipe", Wipe.class, Wipe::new, Map.of(), false))
                .filterMapping(new FilterMappingDecl("wipe", "/*", null, Set.of()))
                .build();
        try (var app = tls(model, STORE, "alice", HttpClient.Version.HTTP_1_1)) {
            var r = app.get("/secure/x");
            assertEquals(200, r.statusCode());
            assertEquals("user=" + TlsTestCertificates.ALICE_DN + " type=CLIENT_CERT admin=true", r.body());
            // The application owns the attribute once it is seeded: the filter's value is what the servlet reads,
            // and authentication through the servlet API still follows the connection.
            var a = app.get("/auth");
            assertEquals("authenticate=true user=" + TlsTestCertificates.ALICE_DN, a.body());
            assertEquals("user=" + TlsTestCertificates.ALICE_DN + " attributeLength=0", app.get("/secure-attr/x").body());
        }
    }

    @ParameterizedTest @EnumSource(value = HttpClient.Version.class, names = {"HTTP_1_1", "HTTP_2"})
    void httpServletRequestAuthenticateUsesTheCertificate(HttpClient.Version version) throws Exception {
        try (var app = tls(base().build(), STORE, "alice", version)) {
            assertEquals("authenticate=true user=" + TlsTestCertificates.ALICE_DN, app.get("/auth").body());
        }
    }

    @Test
    void authenticateWithoutACertificateReturnsFalseAndForbids() throws Exception {
        try (var app = tls(base().build(), STORE, null, HttpClient.Version.HTTP_1_1)) {
            var r = app.get("/auth");
            assertEquals(403, r.statusCode());
            assertFalse(r.body().contains("authenticate=true"));
        }
    }
}
