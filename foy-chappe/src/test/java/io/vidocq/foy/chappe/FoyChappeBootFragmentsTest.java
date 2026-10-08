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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Native discovery of web fragments and container initializers on the class path. */
class FoyChappeBootFragmentsTest {

    /** Servlet of the application itself (test classes), declared by an explicit web.xml. */
    public static class AppServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("app");
        }
    }

    /**
     * A jar whose fragment {@code F} declares {@code fragpkg.FragServlet} on {@code /frag} and
     * whose initializer {@code fragpkg.FragSci} adds {@code fragpkg.SciServlet} on {@code /sci}.
     */
    private static Path fragmentJar(Path dir) throws Exception {
        Path src = dir.resolve("src/fragpkg");
        Files.createDirectories(src);
        Files.writeString(src.resolve("FragServlet.java"), """
                package fragpkg;
                public class FragServlet extends jakarta.servlet.http.HttpServlet {
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                                         jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write("frag");
                    }
                }""");
        Files.writeString(src.resolve("SciServlet.java"), """
                package fragpkg;
                public class SciServlet extends jakarta.servlet.http.HttpServlet {
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                                         jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write("sci");
                    }
                }""");
        Files.writeString(src.resolve("FragSci.java"), """
                package fragpkg;
                public class FragSci implements jakarta.servlet.ServletContainerInitializer {
                    public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {
                        ctx.addServlet("sci", new SciServlet()).addMapping("/sci");
                    }
                }""");
        Path classes = dir.resolve("classes");
        String servletApi = Path.of(HttpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        int rc = java.util.spi.ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-cp", servletApi, "-d", classes.toString(), src.resolve("FragServlet.java").toString(),
                src.resolve("SciServlet.java").toString(), src.resolve("FragSci.java").toString());
        assertEquals(0, rc, "test classes must compile");
        Path jar = dir.resolve("frag.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/web-fragment.xml"));
            out.write("""
                <web-fragment xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <name>F</name>
                  <servlet><servlet-name>frag</servlet-name><servlet-class>fragpkg.FragServlet</servlet-class></servlet>
                  <servlet-mapping><servlet-name>frag</servlet-name><url-pattern>/frag</url-pattern></servlet-mapping>
                </web-fragment>""".getBytes());
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/jakarta.servlet.ServletContainerInitializer"));
            out.write("fragpkg.FragSci\n".getBytes());
            out.closeEntry();
        }
        return jar;
    }

    private static URLClassLoader loader(Path jar) throws Exception {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, FoyChappeBootFragmentsTest.class.getClassLoader());
    }

    private static String webXml(String extra) {
        return """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              %s
              <servlet><servlet-name>app</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>app</servlet-name><url-pattern>/app</url-pattern></servlet-mapping>
            </web-app>""".formatted(extra, AppServlet.class.getName());
    }

    /** Serves {@code mounted} and returns the status of each path, as {@code status:body}. */
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
                out[i] = resp.statusCode() == 200 ? "200:" + resp.body() : String.valueOf(resp.statusCode());
            }
            return out;
        } finally {
            server.stop();
            mounted.close();
        }
    }

    @Test
    void fragmentServletAndInitializerServletAreServed(@TempDir Path dir) throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            var mounted = FoyChappeBoot.builder().classLoader(l).build().orElseThrow();
            assertArrayEquals(new String[] {"200:frag", "200:sci"}, get(mounted, "/frag", "/sci"));
        }
    }

    @Test
    void absoluteOrderingExcludingTheFragmentDropsItsServletAndItsInitializer(@TempDir Path dir)
            throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            String xml = webXml("<absolute-ordering><name>Other</name></absolute-ordering>");
            var mounted = FoyChappeBoot.builder().classLoader(l)
                    .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
            assertArrayEquals(new String[] {"200:app", "404", "404"}, get(mounted, "/app", "/frag", "/sci"));
        }
    }

    @Test
    void metadataCompleteWebXmlIgnoresFragmentsButKeepsTheirInitializers(@TempDir Path dir) throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            String xml = webXml("").replace("version=\"6.1\"", "version=\"6.1\" metadata-complete=\"true\"");
            var mounted = FoyChappeBoot.builder().classLoader(l)
                    .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
            assertArrayEquals(new String[] {"200:app", "404", "200:sci"}, get(mounted, "/app", "/frag", "/sci"));
        }
    }

    @Test
    void discoveryCanBeTurnedOff(@TempDir Path dir) throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            assertTrue(FoyChappeBoot.builder().classLoader(l).discoverFragments(false).build().isEmpty());
        }
    }

    @Test
    void contextPathDefaultsToTheDescriptorDefaultContextPath(@TempDir Path dir) throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            String xml = webXml("<default-context-path>/shop</default-context-path>");
            var mounted = FoyChappeBoot.builder().classLoader(l)
                    .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
            assertEquals("/shop", mounted.mountPrefix());
            assertEquals("/shop", mounted.servletContext().getContextPath());
            assertArrayEquals(new String[] {"200:app", "200:frag"}, get(mounted, "/app", "/frag"));
        }
    }

    @Test
    void explicitContextPathWinsOverTheDefaultContextPath(@TempDir Path dir) throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            String xml = webXml("<default-context-path>/shop</default-context-path>");
            var mounted = FoyChappeBoot.builder().classLoader(l).contextPath("/")
                    .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
            assertEquals("", mounted.mountPrefix());
            mounted.close();
        }
    }
}
