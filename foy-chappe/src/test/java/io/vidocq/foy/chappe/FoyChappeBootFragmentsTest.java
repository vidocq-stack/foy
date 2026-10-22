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
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
                </web-fragment>""".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/jakarta.servlet.ServletContainerInitializer"));
            out.write("fragpkg.FragSci\n".getBytes(StandardCharsets.UTF_8));
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
            out.write((pkg + ".Sci\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (fragmentName != null) {
                out.putNextEntry(new JarEntry("META-INF/web-fragment.xml"));
                out.write(("<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><name>"
                        + fragmentName + "</name></web-fragment>").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String testClasses() throws Exception {
        return Path.of(FragmentAppServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
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
            </web-app>""".formatted(extra, FragmentAppServlet.class.getName());
    }

    /** Serves {@code mounted} and returns the status of each path, as {@code status:body}. */
    private static String[] get(FoyChappeBoot.Mounted mounted, String... paths) throws Exception {
        Server server = Server.builder().host("127.0.0.1").port(0).handler(mounted.handler()).build();
        server.start();
        int port = server.port();
        try (mounted; server) {
            var client = HttpClient.newHttpClient();
            String[] out = new String[paths.length];
            for (int i = 0; i < paths.length; i++) {
                var resp = client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + mounted.mountPrefix() + paths[i])).build(),
                        HttpResponse.BodyHandlers.ofString());
                out[i] = resp.statusCode() == 200 ? "200:" + resp.body() : String.valueOf(resp.statusCode());
            }
            return out;
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
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).build().orElseThrow();
            assertArrayEquals(new String[] {"200:app", "404", "404"}, get(mounted, "/app", "/frag", "/sci"));
        }
    }

    @Test
    void metadataCompleteWebXmlIgnoresFragmentsButKeepsTheirInitializers(@TempDir Path dir) throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            String xml = webXml("").replace("version=\"6.1\"", "version=\"6.1\" metadata-complete=\"true\"");
            var mounted = FoyChappeBoot.builder().classLoader(l)
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).build().orElseThrow();
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
        String start = "ctx.addServlet(\"own\", new " + FragmentAppServlet.class.getCanonicalName() + "()).addMapping(\"/own\");";
        Path app = sciJar(dir, "app.jar", "apppkg", "", start, null);
        String xml = webXml("<absolute-ordering><name>Other</name></absolute-ordering>");
        try (var l = loader(app)) {
            var library = FoyChappeBoot.builder().classLoader(l)
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).build().orElseThrow();
            assertArrayEquals(new String[] {"200:app", "404"}, get(library, "/app", "/own"),
                    "a fragment-less jar is an unnamed fragment, excluded without <others/>");
            var own = FoyChappeBoot.builder().classLoader(l).applicationRoot(app.toUri().toURL())
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).build().orElseThrow();
            assertArrayEquals(new String[] {"200:app", "200:app"}, get(own, "/app", "/own"),
                    "the application's own initializer always runs");
        }
    }

    @Test
    void contextPathDefaultsToTheDescriptorDefaultContextPath(@TempDir Path dir) throws Exception {
        try (var l = loader(fragmentJar(dir))) {
            String xml = webXml("<default-context-path>/shop</default-context-path>");
            var mounted = FoyChappeBoot.builder().classLoader(l)
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).build().orElseThrow();
            assertEquals("/shop", mounted.mountPrefix());
            assertEquals("/shop", mounted.servletContext().getContextPath());
            assertArrayEquals(new String[] {"200:app", "200:frag"}, get(mounted, "/app", "/frag"));
        }
    }

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
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** A BeanManager whose only Servlet bean is {@code servletClass} (no CdiWebComponents index). */
    private static jakarta.enterprise.inject.spi.BeanManager servletBeans(Class<?> servletClass) {
        return beans(servletClass);
    }

    /**
     * A BeanManager whose beans are {@code classes} (no CdiWebComponents index): a lookup by a
     * type returns the beans of the classes assignable to it, so servlets, filters and listeners
     * are all found.
     */
    private static jakarta.enterprise.inject.spi.BeanManager beans(Class<?>... classes) {
        var all = new java.util.LinkedHashMap<Class<?>, jakarta.enterprise.inject.spi.Bean<?>>();
        for (Class<?> c : classes) {
            all.put(c, (jakarta.enterprise.inject.spi.Bean<?>) java.lang.reflect.Proxy.newProxyInstance(
                    jakarta.enterprise.inject.spi.Bean.class.getClassLoader(),
                    new Class<?>[]{jakarta.enterprise.inject.spi.Bean.class}, (p, m, a) -> switch (m.getName()) {
                        case "getBeanClass" -> c;
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> System.identityHashCode(p) == System.identityHashCode(a[0]);
                        case "toString" -> "Bean[" + c.getName() + "]";
                        default -> throw new UnsupportedOperationException(m.getName());
                    }));
        }
        return (jakarta.enterprise.inject.spi.BeanManager) java.lang.reflect.Proxy.newProxyInstance(
                jakarta.enterprise.inject.spi.BeanManager.class.getClassLoader(),
                new Class<?>[]{jakarta.enterprise.inject.spi.BeanManager.class}, (p, m, a) -> switch (m.getName()) {
                    case "getBeans" -> {
                        var found = new java.util.LinkedHashSet<jakarta.enterprise.inject.spi.Bean<?>>();
                        all.forEach((c, b) -> { if (((Class<?>) a[0]).isAssignableFrom(c)) found.add(b); });
                        yield found;
                    }
                    // Components that stay deployed are instantiated: a plain constructor call.
                    case "createCreationalContext" -> null;
                    case "getReference" -> ((jakarta.enterprise.inject.spi.Bean<?>) a[0]).getBeanClass()
                            .getDeclaredConstructor().newInstance();
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> System.identityHashCode(p) == System.identityHashCode(a[0]);
                    case "toString" -> "FakeBeanManager";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** The classes of the vidocq#228 harness: a servlet, a filter, a listener and an initializer. */
    private static final java.util.Map<String, String> SHARED_LOADER_SOURCES = java.util.Map.of(
            "expkg.ExServlet", """
                    package expkg;
                    @jakarta.servlet.annotation.WebServlet("/ex")
                    public class ExServlet extends jakarta.servlet.http.HttpServlet {
                        protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                                             jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                            r.getWriter().write("ex");
                        }
                    }""",
            "expkg.ExFilter", """
                    package expkg;
                    @jakarta.servlet.annotation.WebFilter("/*")
                    public class ExFilter implements jakarta.servlet.Filter {
                        public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                             jakarta.servlet.FilterChain c)
                                throws java.io.IOException, jakarta.servlet.ServletException {
                            ((jakarta.servlet.http.HttpServletResponse) r).setHeader("X-Ex", "1");
                            c.doFilter(q, r);
                        }
                    }""",
            "expkg.ExListener", """
                    package expkg;
                    @jakarta.servlet.annotation.WebListener
                    public class ExListener implements jakarta.servlet.ServletRequestListener {
                        public void requestInitialized(jakarta.servlet.ServletRequestEvent e) {
                            e.getServletRequest().setAttribute("exl", "1");
                        }
                    }""",
            "expkg.ExSci", """
                    package expkg;
                    public class ExSci implements jakarta.servlet.ServletContainerInitializer {
                        public ExSci() { System.setProperty("expkg.exsci", "constructed"); }
                        public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {
                            ctx.addServlet("exsci", new jakarta.servlet.http.HttpServlet() {
                                protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                                                     jakarta.servlet.http.HttpServletResponse r)
                                        throws java.io.IOException {
                                    r.getWriter().write("exsci");
                                }
                            }).addMapping("/exsci");
                        }
                    }""");

    private static final String SERVICES = "META-INF/services/jakarta.servlet.ServletContainerInitializer";

    /** What {@link #sharedLoaderHarness} built: the two jars and the class loaders that see them. */
    private record Harness(Path ex, Path shared, URLClassLoader parent, URLClassLoader loader)
            implements AutoCloseable {
        URL exUrl() throws Exception { return ex.toUri().toURL(); }
        URL sharedUrl() throws Exception { return shared.toUri().toURL(); }
        @Override public void close() throws IOException { loader.close(); parent.close(); }
    }

    /**
     * Reproduces the layout of vidocq#228: {@code ex.jar} holds the fragment descriptor {@code Ex}
     * and the classes, {@code shared.jar} the same classes without descriptor. The loader that
     * sees {@code ex.jar} has {@code shared.jar} for a parent, so the classes load parent-first
     * from {@code shared.jar} while {@code META-INF/web-fragment.xml} is found in {@code ex.jar}.
     */
    private static Harness sharedLoaderHarness(Path dir) throws Exception {
        Path ex = compiledJar(dir, "ex.jar", SHARED_LOADER_SOURCES, java.util.Map.of(
                "META-INF/web-fragment.xml", "<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" "
                        + "version=\"6.1\"><name>Ex</name></web-fragment>",
                SERVICES, "expkg.ExSci\n"));
        Path shared = compiledJar(dir, "shared.jar", SHARED_LOADER_SOURCES,
                java.util.Map.of(SERVICES, "expkg.ExSci\n"));
        var parent = new URLClassLoader(new URL[] {shared.toUri().toURL()},
                FoyChappeBootFragmentsTest.class.getClassLoader());
        var loader = new URLClassLoader(new URL[] {ex.toUri().toURL()}, parent);
        var harness = new Harness(ex, shared, parent, loader);
        // The point of the layout: the code source of the classes is NOT the jar of the fragment.
        URL source = loader.loadClass("expkg.ExServlet").getProtectionDomain().getCodeSource().getLocation();
        assertEquals(harness.sharedUrl(), source, "the classes must come from shared.jar");
        assertNotEquals(harness.exUrl(), source);
        assertTrue(loader.getResource("META-INF/web-fragment.xml").toString().contains("ex.jar"),
                "the fragment descriptor must come from ex.jar");
        return harness;
    }

    private static FoyChappeBoot.Builder sharedLoaderBoot(Harness h) throws Exception {
        return FoyChappeBoot.builder().classLoader(h.loader())
                .beanManager(beans(h.loader().loadClass("expkg.ExServlet"), h.loader().loadClass("expkg.ExFilter"),
                        h.loader().loadClass("expkg.ExListener")))
                .webXml(new ByteArrayInputStream(webXml("<absolute-ordering/>").getBytes(StandardCharsets.UTF_8)));
    }

    /** Serves {@code mounted} and returns the response of each path. */
    private static java.util.List<HttpResponse<String>> fetch(FoyChappeBoot.Mounted mounted, String... paths)
            throws Exception {
        Server server = Server.builder().host("127.0.0.1").port(0).handler(mounted.handler()).build();
        server.start();
        int port = server.port();
        try (mounted; server) {
            var client = HttpClient.newHttpClient();
            var out = new java.util.ArrayList<HttpResponse<String>>();
            for (String path : paths) {
                out.add(client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + mounted.mountPrefix() + path)).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            return out;
        }
    }

    @Test
    void aSharedLoaderHarnessExcludesTheFragmentThroughComponentSource(@TempDir Path dir) throws Exception {
        try (var h = sharedLoaderHarness(dir)) {
            System.clearProperty("expkg.exsci");
            // Without the hook the bug is reproduced (vidocq#228): the code source names shared.jar,
            // which is no excluded fragment, so the classes of the excluded fragment stay deployed.
            var before = fetch(sharedLoaderBoot(h).build().orElseThrow(), "/ex", "/exsci");
            assertEquals(200, before.get(0).statusCode(), "documents today's behaviour without the hook");
            assertEquals(200, before.get(1).statusCode());
            assertEquals("constructed", System.getProperty("expkg.exsci"));

            System.clearProperty("expkg.exsci");
            URL exUrl = h.exUrl();
            var mounted = sharedLoaderBoot(h)
                    .componentSource(type -> type.getName().startsWith("expkg.") ? exUrl : null)
                    .build().orElseThrow();
            var responses = fetch(mounted, "/ex", "/exsci", "/app");
            assertEquals(404, responses.get(0).statusCode());
            assertEquals(404, responses.get(1).statusCode());
            assertEquals(200, responses.get(2).statusCode());
            assertEquals("app", responses.get(2).body(), "ExListener must not have run");
            assertTrue(responses.get(2).headers().firstValue("X-Ex").isEmpty(), "ExFilter must not have run");
            assertNull(System.getProperty("expkg.exsci"), "the constructor of ExSci never ran");
        } finally {
            System.clearProperty("expkg.exsci");
        }
    }

    @Test
    void aMetadataCompleteFragmentIsDroppedThroughComponentSource(@TempDir Path dir) throws Exception {
        try (var h = sharedLoaderHarness(dir)) {
            // The descriptor itself is not metadata-complete: only the fragment is, so the merge of the
            // annotated classes of that fragment (mergeMerged) is what must drop them.
            String xml = webXml("");
            Path completeJar = compiledJar(dir, "complete.jar", SHARED_LOADER_SOURCES, java.util.Map.of(
                    "META-INF/web-fragment.xml", "<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" "
                            + "version=\"6.1\" metadata-complete=\"true\"><name>Ex</name></web-fragment>"));
            var parent = new URLClassLoader(new URL[] {h.shared().toUri().toURL()},
                    FoyChappeBootFragmentsTest.class.getClassLoader());
            try (var loader = new URLClassLoader(new URL[] {completeJar.toUri().toURL()}, parent)) {
                URL fragmentJar = completeJar.toUri().toURL();
                var boot = FoyChappeBoot.builder().classLoader(loader)
                        .beanManager(beans(loader.loadClass("expkg.ExServlet"), loader.loadClass("expkg.ExFilter"),
                                loader.loadClass("expkg.ExListener")))
                        .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                System.clearProperty("expkg.exsci");
                var before = fetch(boot.build().orElseThrow(), "/ex");
                assertEquals(200, before.get(0).statusCode(), "without the hook the shared jar keeps the class");

                var mounted = FoyChappeBoot.builder().classLoader(loader)
                        .beanManager(beans(loader.loadClass("expkg.ExServlet"), loader.loadClass("expkg.ExFilter"),
                                loader.loadClass("expkg.ExListener")))
                        .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                        .componentSource(type -> type.getName().startsWith("expkg.") ? fragmentJar : null)
                        .build().orElseThrow();
                var responses = fetch(mounted, "/ex", "/app");
                assertEquals(404, responses.get(0).statusCode());
                assertEquals("app", responses.get(1).body(), "ExListener must not have run");
                assertTrue(responses.get(1).headers().firstValue("X-Ex").isEmpty(), "ExFilter must not have run");
            } finally {
                parent.close();
            }
        } finally {
            System.clearProperty("expkg.exsci");
        }
    }

    @Test
    void aNullSourceKeepsTheComponent(@TempDir Path dir) throws Exception {
        try (var h = sharedLoaderHarness(dir)) {
            var responses = fetch(sharedLoaderBoot(h).componentSource(type -> null).build().orElseThrow(),
                    "/ex", "/app", "/exsci");
            assertEquals(200, responses.get(0).statusCode());
            assertEquals(200, responses.get(2).statusCode(), "the initializer is kept too");
            assertEquals("app:1", responses.get(1).body(), "the listener of the kept component ran");
            assertEquals("1", responses.get(1).headers().firstValue("X-Ex").orElse(null));
        } finally {
            System.clearProperty("expkg.exsci");
        }
    }

    @Test
    void aThrowingSourceFailsTheBuild(@TempDir Path dir) throws Exception {
        try (var h = sharedLoaderHarness(dir)) {
            var boot = sharedLoaderBoot(h).componentSource(type -> { throw new IllegalStateException("boom"); });
            var e = assertThrows(jakarta.servlet.ServletException.class, boot::build);
            assertTrue(e.getMessage().contains("componentSource failed for expkg."), e.getMessage());
            assertEquals("boom", e.getCause().getCause().getMessage());
        } finally {
            System.clearProperty("expkg.exsci");
        }
    }

    @Test
    void componentSourceRejectsNull() {
        var builder = FoyChappeBoot.builder();
        assertThrows(NullPointerException.class, () -> builder.componentSource(null));
    }

    @Test
    void excludedJarContributesNoResourceNorHandledClass(@TempDir Path dir) throws Exception {
        String marker = FragmentMarker.class.getCanonicalName();
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
                        "META-INF/foy/class-index.list", "exidxpkg.IdxMarker|" + FragmentMarker.class.getName() + "|\n",
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
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).build().orElseThrow();
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
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).build().orElseThrow();
            assertEquals("", mounted.mountPrefix());
            mounted.close();
        }
    }
}
