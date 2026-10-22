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

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.WebResourceCollectionDef;
import io.vidocq.foy.spi.security.IdentityStore;

import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

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
    private final String scheme;

    SecureApp(WebAppModel model) { this(model, SecuritySettings.DEFAULTS.withIdentityStore(USERS)); }

    /** Static resources served from {@code resourceRoot} (a directory-aware provider), default settings. */
    SecureApp(WebAppModel model, Path resourceRoot) {
        this(model, DeployOptions.defaults(SecureApp.class.getClassLoader())
                .withSecurity(SecuritySettings.DEFAULTS.withIdentityStore(USERS))
                .withResourceProvider(new DirectoryResources(resourceRoot)));
    }

    SecureApp(WebAppModel model, SecuritySettings settings) {
        this(model, DeployOptions.defaults(SecureApp.class.getClassLoader()).withSecurity(settings));
    }

    private SecureApp(WebAppModel model, DeployOptions options) {
        deployment = WebAppDeployer.deploy(model, options);
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        port = started.port();
        scheme = "http";
        client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Over TLS: the listener asks for a client certificate without requiring one
     * ({@code ClientAuth.WANT}); the client presents what {@code clientContext} holds.
     */
    SecureApp(WebAppModel model, SecuritySettings settings, SSLContext serverContext, SSLContext clientContext,
              HttpClient.Version version) {
        deployment = WebAppDeployer.deploy(model,
                DeployOptions.defaults(SecureApp.class.getClassLoader()).withSecurity(settings));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.startTls(deployment.handler(), serverContext,
                ClientAuth.WANT);
        server = started.server();
        port = started.port();
        scheme = "https";
        client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(clientContext).version(version).build();
    }

    static SecurityConstraintDef constraint(List<String> patterns, List<String> methods, List<String> omissions,
                                            List<String> roles, String guarantee) {
        return new SecurityConstraintDef(List.of(new WebResourceCollectionDef("c", patterns, methods, omissions)),
                roles, guarantee);
    }

    static String basic(String userColonPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userColonPassword.getBytes(StandardCharsets.UTF_8));
    }

    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create(scheme + "://127.0.0.1:" + port + path)); }

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

    /** Serves the files under {@code root}; lists directories (welcome files need it). */
    record DirectoryResources(Path root) implements VidocqServletContext.ResourceProvider {
        @Override public Set<String> listPaths(String path) {
            Path p = root.resolve(path.substring(1));
            if (!Files.isDirectory(p)) return null;
            try (Stream<Path> s = Files.list(p)) {
                var out = new TreeSet<String>();
                s.forEach(c -> out.add(path + c.getFileName() + (Files.isDirectory(c) ? "/" : "")));
                return out;
            } catch (IOException _) {
                return null;
            }
        }
        @Override public InputStream openStream(String path) {
            Path p = root.resolve(path.substring(1));
            try { return Files.isRegularFile(p) ? Files.newInputStream(p) : null; }
            catch (IOException _) { return null; }
        }
        @Override public URL toUrl(String path) {
            Path p = root.resolve(path.substring(1));
            try { return Files.exists(p) ? p.toUri().toURL() : null; }
            catch (IOException _) { return null; }
        }
    }

    @Override
    public void close() {
        server.stop();
        deployment.close();
    }
}
