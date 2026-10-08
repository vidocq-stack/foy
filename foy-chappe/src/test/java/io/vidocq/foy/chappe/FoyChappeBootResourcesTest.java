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
package io.vidocq.foy.chappe;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** {@code META-INF/resources} of the jars reaches the servlet context (§4.6). */
public class FoyChappeBootResourcesTest {

    /** Stand-in for the default servlet (Phase 4): streams {@code getResourceAsStream} with {@code getMimeType}. */
    public static class StaticServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            ServletContext ctx = getServletContext();
            String path = q.getServletPath() + (q.getPathInfo() == null ? "" : q.getPathInfo());
            try (InputStream in = ctx.getResourceAsStream(path)) {
                if (in == null) { r.sendError(404); return; }
                r.setContentType(ctx.getMimeType(path));
                in.transferTo(r.getOutputStream());
            }
        }
    }

    private static Path jar(Path dir, String name, String fragment, String... pathAndContent) throws Exception {
        Path jar = dir.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/resources/"));
            out.closeEntry();
            if (fragment != null) {
                out.putNextEntry(new JarEntry("META-INF/web-fragment.xml"));
                out.write(("<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><name>"
                        + fragment + "</name></web-fragment>").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (int i = 0; i < pathAndContent.length; i += 2) {
                out.putNextEntry(new JarEntry("META-INF/resources/" + pathAndContent[i]));
                out.write(pathAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static URLClassLoader loader(Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new URLClassLoader(urls, FoyChappeBootResourcesTest.class.getClassLoader());
    }

    private static InputStream webXml() {
        return new ByteArrayInputStream(("""
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>static</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>static</servlet-name><url-pattern>/css/*</url-pattern></servlet-mapping>
              <servlet-mapping><servlet-name>static</servlet-name><url-pattern>/index.html</url-pattern></servlet-mapping>
            </web-app>""").formatted(StaticServlet.class.getName()).getBytes(StandardCharsets.UTF_8));
    }

    /** Status, content type and body of each path, as {@code status:type:body}. */
    private static String[] get(FoyChappeBoot.Mounted mounted, String... paths) throws Exception {
        int port;
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            String[] out = new String[paths.length];
            for (int i = 0; i < paths.length; i++) {
                var resp = client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + mounted.mountPrefix() + paths[i])).build(),
                        HttpResponse.BodyHandlers.ofString());
                out[i] = resp.statusCode() + ":" + resp.headers().firstValue("content-type").orElse("") + ":"
                        + resp.body();
            }
            return out;
        } finally {
            server.stop();
            mounted.close();
        }
    }

    @Test
    void resourcesOfJarsAreServedWithTheirMimeType(@TempDir Path dir) throws Exception {
        Path a = jar(dir, "a.jar", null, "index.html", "A", "css/a.css", "a{}");
        Path b = jar(dir, "b.jar", null, "index.html", "B", "css/b.css", "b{}");
        try (var l = loader(a, b)) {
            var mounted = FoyChappeBoot.builder().classLoader(l).webXml(webXml()).build().orElseThrow();
            Set<String> paths = mounted.servletContext().getResourcePaths("/css/");
            assertEquals(Set.of("/css/a.css", "/css/b.css"), paths);
            String[] got = get(mounted, "/css/b.css", "/index.html", "/css/none.css");
            assertTrue(got[0].startsWith("200:text/css"), got[0]);
            assertTrue(got[0].endsWith(":b{}"), got[0]);
            assertTrue(got[1].endsWith(":A"), "class path order for roots outside the ordered list: " + got[1]);
            assertTrue(got[2].startsWith("404"), got[2]);
        }
    }

    @Test
    void applicationRootsWinOverFragments(@TempDir Path dir) throws Exception {
        Path frag = jar(dir, "frag.jar", "F", "index.html", "FRAG");
        Path app = jar(dir, "app.jar", null, "index.html", "APP");
        try (var l = loader(frag, app)) {
            var mounted = FoyChappeBoot.builder().classLoader(l).applicationRoot(app.toUri().toURL())
                    .webXml(webXml()).build().orElseThrow();
            assertTrue(get(mounted, "/index.html")[0].endsWith(":APP"));
        }
    }

    @Test
    void anEmbedderProviderIsKept(@TempDir Path dir) throws Exception {
        Path a = jar(dir, "a.jar", null, "index.html", "A");
        var own = new VidocqServletContext.ResourceProvider() {
            @Override public Set<String> listPaths(String path) { return null; }
            @Override public InputStream openStream(String path) {
                return new ByteArrayInputStream("OWN".getBytes(StandardCharsets.UTF_8));
            }
        };
        try (var l = loader(a)) {
            var mounted = FoyChappeBoot.builder().classLoader(l).resourceProvider(own)
                    .webXml(webXml()).build().orElseThrow();
            assertTrue(get(mounted, "/index.html")[0].endsWith(":OWN"));
        }
    }
}
