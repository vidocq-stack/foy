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
package io.vidocq.foy.internal.container;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Welcome files (Servlet 6.1 section 10.10), end to end over HTTP. */
class WelcomeFilesTest {

    /** Reports the paths and the mapping it was reached through. */
    public static class Who extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var m = req.getHttpServletMapping();
            resp.getWriter().write("WHO SP=" + req.getServletPath() + "|PI=" + req.getPathInfo()
                    + "|URI=" + req.getRequestURI() + "|match=" + m.getMatchValue() + "|" + m.getMappingMatch()
                    + "|type=" + req.getDispatcherType());
        }
    }

    /** A directory-aware provider. */
    record Provider(Path root) implements VidocqServletContext.ResourceProvider {
        @Override public Set<String> listPaths(String path) {
            Path p = root.resolve(path.substring(1));
            if (!Files.isDirectory(p)) return null;
            try (Stream<Path> s = Files.list(p)) {
                var out = new TreeSet<String>();
                s.forEach(c -> out.add(path + c.getFileName() + (Files.isDirectory(c) ? "/" : "")));
                return out;
            } catch (IOException e) {
                return null;
            }
        }
        @Override public InputStream openStream(String path) {
            Path p = root.resolve(path.substring(1));
            try { return Files.isRegularFile(p) ? Files.newInputStream(p) : null; }
            catch (IOException e) { return null; }
        }
        @Override public URL toUrl(String path) {
            Path p = root.resolve(path.substring(1));
            try { return Files.exists(p) ? p.toUri().toURL() : null; }
            catch (IOException e) { return null; }
        }
    }

    @TempDir Path root;
    private Server server;
    private Deployment deployment;
    private int port;

    @BeforeEach
    void files() throws IOException {
        Files.createDirectories(root.resolve("foo"));
        Files.writeString(root.resolve("foo/index.html"), "INDEX from foo/index.html");
        Files.createDirectories(root.resolve("both"));
        Files.writeString(root.resolve("both/first.html"), "FIRST");
        Files.writeString(root.resolve("both/index.html"), "INDEX");
        Files.createDirectories(root.resolve("empty"));
        Files.writeString(root.resolve("empty/readme.txt"), "x");
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/index.html"), "STATIC app");
        Files.writeString(root.resolve("plain.txt"), "plain");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
    }

    private static ServletDecl who(String name, String... patterns) {
        return new ServletDecl(name, Who.class, Who::new, List.of(patterns), Map.of(), -1, true);
    }

    private void deploy(List<String> welcome, ServletDecl... servlets) {
        var b = WebAppModel.builder("/ctx").welcomeFiles(welcome);
        for (var s : servlets) b.servlet(s);
        deployment = WebAppDeployer.deploy(b.build(),
                DeployOptions.defaults(getClass().getClassLoader()).withResourceProvider(new Provider(root)));
        var r = TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String location(HttpResponse<String> r) {
        return r.headers().firstValue("Location").orElse(null);
    }

    @Test
    void directoryWithoutSlashRedirectsKeepingTheQuery() throws Exception {
        deploy(List.of("index.html"));
        var r = get("/ctx/foo?a=b&c=d");
        assertEquals(302, r.statusCode());
        assertEquals("http://127.0.0.1:" + port + "/ctx/foo/?a=b&c=d", location(r));
    }

    @Test
    void contextRootWithoutSlashRedirects() throws Exception {
        deploy(List.of("index.html"));
        var r = get("/ctx");
        assertEquals(302, r.statusCode());
        assertEquals("http://127.0.0.1:" + port + "/ctx/", location(r));
    }

    @Test
    void aFileWithoutSlashIsNotRedirected() throws Exception {
        deploy(List.of("index.html"));
        assertEquals("plain", get("/ctx/plain.txt").body());
        assertEquals(404, get("/ctx/nothing").statusCode());
    }

    @Test
    void staticWelcomeFileIsServedForTheDirectory() throws Exception {
        deploy(List.of("index.html"));
        var r = get("/ctx/foo/");
        assertEquals(200, r.statusCode());
        assertEquals("INDEX from foo/index.html", r.body());
    }

    @Test
    void welcomeFileMapsToAServletAndKeepsTheClientUri() throws Exception {
        deploy(List.of("TestServlet4"), who("ts4", "/TestServlet4"));
        var r = get("/ctx/");
        assertEquals(200, r.statusCode());
        assertEquals("WHO SP=/TestServlet4|PI=null|URI=/ctx/|match=TestServlet4|EXACT|type=REQUEST", r.body());
    }

    @Test
    void extensionMappingMatchesTheWelcomeFile() throws Exception {
        deploy(List.of("index.ts"), who("ext", "*.ts"));
        var r = get("/ctx/foo/");
        assertEquals(200, r.statusCode());
        assertEquals("WHO SP=/foo/index.ts|PI=null|URI=/ctx/foo/|match=foo/index|EXTENSION|type=REQUEST", r.body());
    }

    @Test
    void declarationOrderDecidesBetweenStaticAndServlet() throws Exception {
        deploy(List.of("missing.html", "first.html", "index.html"));
        assertEquals("FIRST", get("/ctx/both/").body());
        deploy2(List.of("index.ts", "index.html"));
        assertTrue(get("/ctx/both/").body().startsWith("WHO SP=/both/index.ts"));
    }

    private void deploy2(List<String> welcome) {
        tearDown();
        deploy(welcome, who("ext", "*.ts"));
    }

    @Test
    void noWelcomeHitIs404WithoutListing() throws Exception {
        deploy(List.of("index.html"));
        var r = get("/ctx/empty/");
        assertEquals(404, r.statusCode());
        assertFalse(r.body().contains("readme"));
    }

    @Test
    void aServletMappedToThePrefixHandlesTheDirectoryItself() throws Exception {
        deploy(List.of("index.html"), who("pfx", "/app/*"));
        var r = get("/ctx/app/");
        assertTrue(r.body().startsWith("WHO SP=/app|PI=/|"), r.body());
    }

    @Test
    void anApplicationDefaultServletDisablesContainerWelcomeHandling() throws Exception {
        deploy(List.of("index.html"), who("dflt", "/"));
        var r = get("/ctx/foo/");
        assertTrue(r.body().startsWith("WHO SP=/foo/|"), r.body());
        assertEquals(200, get("/ctx/foo").statusCode(), "no redirect either");
    }

    @Test
    void optionsOnTheDirectoryUsesTheRegularFlow() throws Exception {
        deploy(List.of("index.html"));
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ctx/foo/"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertNotNull(r.headers().firstValue("Allow").orElse(null));
    }
}
