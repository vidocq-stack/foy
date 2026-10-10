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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.WebResourceCollectionDef;
import io.vidocq.foy.spi.security.IdentityStore;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** A deployed application with users alice (admin) and bob (user); cookies kept, redirects not followed. */
final class SecureApp implements AutoCloseable {

    static final IdentityStore USERS = InMemoryIdentityStore.builder()
            .user("alice", "a-secret".toCharArray(), "admin")
            .user("bob", "b-secret".toCharArray(), "user")
            .build();

    final Deployment deployment;
    final Server server;
    final int port;
    final CookieManager cookies = new CookieManager();
    final HttpClient client;

    SecureApp(WebAppModel model) { this(model, SecuritySettings.DEFAULTS.withIdentityStore(USERS)); }

    SecureApp(WebAppModel model, SecuritySettings settings) {
        deployment = WebAppDeployer.deploy(model,
                DeployOptions.defaults(SecureApp.class.getClassLoader()).withSecurity(settings));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        port = started.port();
        client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static SecurityConstraintDef constraint(List<String> patterns, List<String> methods, List<String> omissions,
                                            List<String> roles, String guarantee) {
        return new SecurityConstraintDef(List.of(new WebResourceCollectionDef("c", patterns, methods, omissions)),
                roles, guarantee);
    }

    static String basic(String userColonPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userColonPassword.getBytes(StandardCharsets.UTF_8));
    }

    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }

    HttpResponse<String> get(String path) throws Exception { return send(request(path).GET()); }

    HttpResponse<String> send(String method, String path, String userColonPassword) throws Exception {
        var b = request(path).method(method, HttpRequest.BodyPublishers.noBody());
        if (userColonPassword != null) b.header("Authorization", basic(userColonPassword));
        return send(b);
    }

    HttpResponse<String> postForm(String path, String urlEncodedForm) throws Exception {
        return send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncodedForm)));
    }

    HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A GET from a separate client that presents only {@code sessionId} (a stale or planted session id). */
    HttpResponse<String> getWithSession(String path, String sessionId) throws Exception {
        var jar = new CookieManager();
        var cookie = new HttpCookie(SessionManager.COOKIE_NAME, sessionId);
        cookie.setPath("/");
        cookie.setVersion(0);
        jar.getCookieStore().add(URI.create("http://127.0.0.1:" + port + "/"), cookie);
        return HttpClient.newBuilder().cookieHandler(jar).followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> SessionManager.COOKIE_NAME.equals(c.getName()))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    @Override
    public void close() {
        server.stop();
        deployment.close();
    }
}
