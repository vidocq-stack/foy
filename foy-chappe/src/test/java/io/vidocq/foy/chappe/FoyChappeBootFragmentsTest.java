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
public class FoyChappeBootFragmentsTest {

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

    /**
     * A jar {@code name} holding initializer {@code pkg.Sci} (constructor {@code ctorBody},
     * {@code onStartup} body {@code startBody}, {@code ctx} in scope) and, when {@code fragmentName}
     * is not null, an empty fragment of that name.
     */
    private static Path sciJar(Path dir, String name, String pkg, String ctorBody, String startBody,
                               String fragmentName) throws Exception {
        Path src = dir.resolve("src-" + pkg).resolve(pkg);
        Files.createDirectories(src);
        Files.writeString(src.resolve("Sci.java"), """
                package %s;
                public class Sci implements jakarta.servlet.ServletContainerInitializer {
                    public Sci() { %s }
                    public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) { %s }
                }""".formatted(pkg, ctorBody, startBody));
        Path classes = dir.resolve("classes-" + pkg);
        String servletApi = Path.of(HttpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        int rc = java.util.spi.ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-cp", servletApi + java.io.File.pathSeparator + testClasses(), "-d", classes.toString(),
                src.resolve("Sci.java").toString());
        assertEquals(0, rc, "test initializer must compile");
        Path jar = dir.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(pkg + "/Sci.class"));
            out.write(Files.readAllBytes(classes.resolve(pkg).resolve("Sci.class")));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/jakarta.servlet.ServletContainerInitializer"));
            out.write((pkg + ".Sci\n").getBytes());
            out.closeEntry();
            if (fragmentName != null) {
                out.putNextEntry(new JarEntry("META-INF/web-fragment.xml"));
                out.write(("<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><name>"
                        + fragmentName + "</name></web-fragment>").getBytes());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String testClasses() throws Exception {
        return Path.of(AppServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static URLClassLoader loader(Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new URLClassLoader(urls, FoyChappeBootFragmentsTest.class.getClassLoader());
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
        // boom.jar's fragment is excluded too: its initializer must not even be constructed.
        Path boom = sciJar(dir, "boom.jar", "boompkg", "throw new IllegalStateException(\"boom\");", "", "Boom");
        try (var l = loader(fragmentJar(dir), boom)) {
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
            assertTrue(FoyChappeBoot.builder().classLoader(l).discoverPluggability(false).build().isEmpty());
        }
    }

    @Test
    void absoluteOrderingWithoutOthersSkipsLibraryInitializersButRunsTheApplicationOnes(@TempDir Path dir)
            throws Exception {
        String start = "ctx.addServlet(\"own\", new " + AppServlet.class.getCanonicalName() + "()).addMapping(\"/own\");";
        Path app = sciJar(dir, "app.jar", "apppkg", "", start, null);
        String xml = webXml("<absolute-ordering><name>Other</name></absolute-ordering>");
        try (var l = loader(app)) {
            var library = FoyChappeBoot.builder().classLoader(l)
                    .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
            assertArrayEquals(new String[] {"200:app", "404"}, get(library, "/app", "/own"),
                    "a fragment-less jar is an unnamed fragment, excluded without <others/>");
            var own = FoyChappeBoot.builder().classLoader(l).applicationRoot(app.toUri().toURL())
                    .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
            assertArrayEquals(new String[] {"200:app", "200:app"}, get(own, "/app", "/own"),
                    "the application's own initializer always runs");
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

    /** The type the application initializer of {@link #excludedJarContributesNoResourceNorHandledClass} handles. */
    public interface Marker {}

    /**
     * Compiles {@code sources} (binary name to source) against the Servlet API and the test
     * classes into jar {@code name}, adding the {@code resources} (entry name to content).
     */
    private static Path compiledJar(Path dir, String name, java.util.Map<String, String> sources,
                                    java.util.Map<String, String> resources) throws Exception {
        Path src = dir.resolve("src-" + name);
        Path classes = dir.resolve("classes-" + name);
        var files = new java.util.ArrayList<String>();
        for (var e : sources.entrySet()) {
            Path file = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file.toString());
        }
        String servletApi = Path.of(HttpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        var args = new java.util.ArrayList<>(java.util.List.of(
                "-cp", servletApi + java.io.File.pathSeparator + testClasses(), "-d", classes.toString()));
        args.addAll(files);
        int rc = java.util.spi.ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                args.toArray(String[]::new));
        assertEquals(0, rc, "test classes must compile");
        Path jar = dir.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
            for (var e : resources.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes());
                out.closeEntry();
            }
        }
        return jar;
    }

    /** A BeanManager whose only Servlet bean is {@code servletClass} (no CdiWebComponents index). */
    private static jakarta.enterprise.inject.spi.BeanManager servletBeans(Class<?> servletClass) {
        var bean = (jakarta.enterprise.inject.spi.Bean<?>) java.lang.reflect.Proxy.newProxyInstance(
                jakarta.enterprise.inject.spi.Bean.class.getClassLoader(),
                new Class<?>[]{jakarta.enterprise.inject.spi.Bean.class}, (p, m, a) -> switch (m.getName()) {
                    case "getBeanClass" -> servletClass;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "Bean[" + servletClass.getName() + "]";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (jakarta.enterprise.inject.spi.BeanManager) java.lang.reflect.Proxy.newProxyInstance(
                jakarta.enterprise.inject.spi.BeanManager.class.getClassLoader(),
                new Class<?>[]{jakarta.enterprise.inject.spi.BeanManager.class}, (p, m, a) -> switch (m.getName()) {
                    case "getBeans" -> a[0] == jakarta.servlet.Servlet.class ? java.util.Set.of(bean) : java.util.Set.of();
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "FakeBeanManager";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void excludedJarContributesNoResourceNorHandledClass(@TempDir Path dir) throws Exception {
        String marker = Marker.class.getCanonicalName();
        String fragment = "<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><name>%s</name>"
                + "</web-fragment>";
        // ex.jar (no class index, so only a class-bytes scan could see it) and exidx.jar (a class
        // index): both have a fragment the absolute ordering excludes.
        Path ex = compiledJar(dir, "ex.jar", java.util.Map.of(
                "expkg.ExServlet", """
                        package expkg;
                        @jakarta.servlet.annotation.WebServlet("/ex")
                        public class ExServlet extends jakarta.servlet.http.HttpServlet {}""",
                "expkg.ExMarker", "package expkg; public class ExMarker implements " + marker + " {}"),
                java.util.Map.of("META-INF/web-fragment.xml", fragment.formatted("Ex"),
                        "META-INF/resources/ex.txt", "excluded"));
        Path exIdx = compiledJar(dir, "exidx.jar", java.util.Map.of(
                "exidxpkg.IdxMarker", "package exidxpkg; public class IdxMarker implements " + marker + " {}"),
                java.util.Map.of("META-INF/web-fragment.xml", fragment.formatted("ExIdx"),
                        "META-INF/foy/class-index.list", "exidxpkg.IdxMarker|" + Marker.class.getName() + "|\n",
                        "META-INF/resources/exidx.txt", "excluded"));
        // app.jar: the application, whose initializer records the classes it is handed.
        Path app = compiledJar(dir, "app.jar", java.util.Map.of(
                "apppkg.AppMarker", "package apppkg; public class AppMarker implements " + marker + " {}",
                "apppkg.Sci", """
                        package apppkg;
                        @jakarta.servlet.annotation.HandlesTypes(%s.class)
                        public class Sci implements jakarta.servlet.ServletContainerInitializer {
                            public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {
                                var names = new java.util.TreeSet<String>();
                                if (c != null) for (Class<?> k : c) names.add(k.getName());
                                ctx.setAttribute("handled", names.toString());
                            }
                        }""".formatted(marker)),
                java.util.Map.of("META-INF/services/jakarta.servlet.ServletContainerInitializer", "apppkg.Sci\n",
                        "META-INF/resources/app.txt", "app"));
        try (var l = loader(ex, exIdx, app)) {
            String xml = webXml("<absolute-ordering><name>Other</name></absolute-ordering>");
            var mounted = FoyChappeBoot.builder().classLoader(l).applicationRoot(app.toUri().toURL())
                    .beanManager(servletBeans(l.loadClass("expkg.ExServlet")))
                    .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
            var ctx = mounted.servletContext();
            assertEquals("[apppkg.AppMarker]", ctx.getAttribute("handled"),
                    "an excluded jar's classes are not handed to @HandlesTypes");
            assertNotNull(ctx.getResource("/app.txt"));
            assertNull(ctx.getResource("/ex.txt"), "an excluded jar's META-INF/resources are not served");
            assertNull(ctx.getResource("/exidx.txt"));
            var paths = ctx.getResourcePaths("/");
            assertTrue(paths.contains("/app.txt") && !paths.contains("/ex.txt") && !paths.contains("/exidx.txt"),
                    paths::toString);
            assertNull(ctx.getServletRegistration("expkg.ExServlet"));
            assertArrayEquals(new String[] {"200:app", "404"}, get(mounted, "/app", "/ex"));
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
