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
package io.vidocq.foy.tck;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletTestHostTest {

    private static HttpResponse<String> get(ServletTestHost host, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void routesByContextPathBoundary() throws Exception {
        try (var host = new ServletTestHost()) {
            host.mount("/ctx", r -> Response.ok("ctx"));
            assertEquals("ctx", get(host, "/ctx").body());
            assertEquals("ctx", get(host, "/ctx/x").body());
            assertEquals("ctx", get(host, "/ctx;jsessionid=abc/x").body());
            assertEquals(404, get(host, "/ctx2/x").statusCode());
            assertEquals(404, get(host, "/other").statusCode());
        }
    }

    @Test
    void longestPrefixWinsAndRootGetsTheRest() throws Exception {
        try (var host = new ServletTestHost()) {
            host.mount("/", r -> Response.ok("root"));
            host.mount("/ctx", r -> Response.ok("ctx"));
            assertEquals("ctx", get(host, "/ctx/a").body());
            assertEquals("root", get(host, "/ctx2/a").body());
            assertEquals("root", get(host, "/a").body());
            host.unmount("/ctx");
            assertEquals("root", get(host, "/ctx/a").body());
        }
    }

    @Test
    void trailingSlashOfAContextPathIsNormalised() throws Exception {
        try (var host = new ServletTestHost()) {
            host.mount("/ctx/", r -> Response.ok("ctx"));
            assertEquals("ctx", get(host, "/ctx/x").body());
            host.unmount("/ctx");
            assertEquals(404, get(host, "/ctx/x").statusCode());
            host.mount("/ctx", r -> Response.ok("again"));
            assertThrows(IllegalStateException.class, () -> host.mount("/ctx/", r -> Response.ok("dup")));
        }
    }

    @Test
    void aPlainHostServesHttp() {
        try (var host = new ServletTestHost()) {
            assertEquals("http", host.scheme());
        }
    }

    @Test
    void aTlsHostServesHttps(@TempDir Path dir) throws Exception {
        Path keyStore = dir.resolve("server.p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit");
        KeyStore ks = load(keyStore);

        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);

        try (var host = new ServletTestHost(serverContext, ClientAuth.WANT);
             var client = HttpClient.newBuilder().sslContext(clientContext).build()) {
            host.mount("/", r -> Response.ok("ok"));
            assertEquals("https", host.scheme());
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("https://127.0.0.1:" + host.port() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
        }
    }

    /** Runs the JDK's keytool: the JDK has no public API that generates a certificate. */
    private static void keytool(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
        assertEquals(0, process.exitValue(), "keytool failed: " + output);
    }

    private static KeyStore load(Path p12) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) { ks.load(in, "changeit".toCharArray()); }
        return ks;
    }
}
