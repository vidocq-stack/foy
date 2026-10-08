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
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.boot.ApplicationSources.Initializer;
import io.vidocq.foy.internal.webxml.Fragment;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationSourcesTest {

    private static final String SCI_SERVICE = "META-INF/services/jakarta.servlet.ServletContainerInitializer";

    private static String fragment(String name) {
        return """
            <web-fragment xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">%s</web-fragment>"""
                .formatted(name == null ? "" : "<name>" + name + "</name>");
    }

    /** Compiles {@code pkg.Sci} (an empty initializer) and returns its class files. */
    private static Map<String, byte[]> compileSci(Path dir, String pkg) throws Exception {
        return compileSci(dir, pkg, "");
    }

    /** As {@link #compileSci(Path, String)}, with {@code ctorBody} as the constructor body. */
    private static Map<String, byte[]> compileSci(Path dir, String pkg, String ctorBody) throws Exception {
        Path src = dir.resolve("src-" + pkg).resolve(pkg);
        Files.createDirectories(src);
        Files.writeString(src.resolve("Sci.java"), """
                package %s;
                public class Sci implements jakarta.servlet.ServletContainerInitializer {
                    public Sci() { %s }
                    public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {}
                }""".formatted(pkg, ctorBody));
        Path classes = dir.resolve("classes-" + pkg);
        String servletApi = Path.of(HttpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        int rc = java.util.spi.ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-cp", servletApi, "-d", classes.toString(), src.resolve("Sci.java").toString());
        assertEquals(0, rc, "test initializer must compile");
        var out = new LinkedHashMap<String, byte[]>();
        out.put(pkg + "/Sci.class", Files.readAllBytes(classes.resolve(pkg).resolve("Sci.class")));
        out.put(SCI_SERVICE, (pkg + ".Sci\n").getBytes());
        return out;
    }

    private static Path jar(Path dir, String name, Map<String, byte[]> entries) throws Exception {
        Path jar = dir.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Map<String, byte[]> entries(String... nameAndContent) {
        var m = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < nameAndContent.length; i += 2) m.put(nameAndContent[i], nameAndContent[i + 1].getBytes());
        return m;
    }

    private static URLClassLoader loader(Path... roots) throws Exception {
        return loader(ApplicationSourcesTest.class.getClassLoader(), roots);
    }

    private static URLClassLoader loader(ClassLoader parent, Path... roots) throws Exception {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) urls[i] = roots[i].toUri().toURL();
        return new URLClassLoader(urls, parent);
    }

    private static String key(Path p) throws Exception {
        return Fragment.sourceKey(p.toUri().toURL());
    }

    // ---- fragments ------------------------------------------------------------------------------

    @Test
    void duplicateJarMergedOnce(@TempDir Path dir) throws Exception {
        var e = compileSci(dir, "apkg");
        e.put("META-INF/web-fragment.xml", fragment("A").getBytes());
        Path a = jar(dir, "a.jar", e);
        // The same jar on a parent and a child loader: getResources reports it twice.
        try (var parent = loader(a); var l = loader(parent, a)) {
            assertEquals(2, java.util.Collections.list(l.getResources("META-INF/web-fragment.xml")).size(),
                    "precondition: the loader reports the fragment twice");
            List<Fragment> fragments = ApplicationSources.fragments(l);
            assertEquals(1, fragments.size(), fragments::toString);
            assertEquals("A", fragments.getFirst().id());
            assertEquals("A", fragments.getFirst().descriptor().fragmentName());
            assertEquals(key(a), Fragment.sourceKey(fragments.getFirst().jar()));
        }
    }

    @Test
    void malformedFragmentNamesTheJar(@TempDir Path dir) throws Exception {
        Path b = jar(dir, "b.jar", entries("META-INF/web-fragment.xml", "<web-fragment><oops></web-fragment>"));
        try (var l = loader(b)) {
            var ex = assertThrows(ServletException.class, () -> ApplicationSources.fragments(l));
            assertTrue(ex.getMessage().contains("b.jar"), ex::getMessage);
        }
    }

    @Test
    void unnamedFragmentIsIdentifiedByItsJarFileNameAndFragmentsAreSortedByJar(@TempDir Path dir)
            throws Exception {
        Path z = jar(dir, "z.jar", entries("META-INF/web-fragment.xml", fragment("Z")));
        Path c = jar(dir, "c.jar", entries("META-INF/web-fragment.xml", fragment(null)));
        try (var l = loader(z, c)) {
            var fragments = ApplicationSources.fragments(l);
            assertEquals(List.of("c.jar", "Z"), fragments.stream().map(Fragment::id).toList());
            assertNull(fragments.getFirst().descriptor().fragmentName());
        }
    }

    @Test
    void explodedDirectoryIsAFragmentSourceToo(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("exploded");
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/web-fragment.xml"), fragment("D"));
        try (var l = loader(root)) {
            var fragments = ApplicationSources.fragments(l);
            assertEquals(1, fragments.size());
            assertEquals(key(root), Fragment.sourceKey(fragments.getFirst().jar()));
        }
    }

    // ---- initializers ---------------------------------------------------------------------------

    @Test
    void initializersComeFromServiceLoaderWithTheirJar(@TempDir Path dir) throws Exception {
        Path a = jar(dir, "a.jar", compileSci(dir, "apkg"));
        Path b = jar(dir, "b.jar", compileSci(dir, "bpkg"));
        try (var l = loader(b, a)) {
            var found = ApplicationSources.initializers(l).stream()
                    .filter(i -> i.jar() != null && (key(i.jar()).equals(keyQuiet(a)) || key(i.jar()).equals(keyQuiet(b))))
                    .toList();
            assertEquals(List.of("bpkg.Sci", "apkg.Sci"),
                    found.stream().map(i -> i.sci().getClass().getName()).toList(),
                    "ServiceLoader discovery order (class path order), not jar URL order");
            assertEquals(keyQuiet(b), key(found.getFirst().jar()));
            assertSame(l, found.getFirst().sci().getClass().getClassLoader());
        }
    }

    @Test
    void rejectedInitializersAreNeverInstantiated(@TempDir Path dir) throws Exception {
        Path boom = jar(dir, "boom.jar", compileSci(dir, "boompkg", "throw new IllegalStateException(\"boom\");"));
        Path ok = jar(dir, "ok.jar", compileSci(dir, "okpkg"));
        try (var l = loader(boom, ok)) {
            String boomKey = key(boom);
            var found = ApplicationSources.initializers(l, root -> root == null || !key(root).equals(boomKey));
            assertTrue(found.stream().anyMatch(i -> i.sci().getClass().getName().equals("okpkg.Sci")));
            assertTrue(found.stream().noneMatch(i -> i.sci().getClass().getName().equals("boompkg.Sci")));
            assertThrows(java.util.ServiceConfigurationError.class, () -> ApplicationSources.initializers(l),
                    "precondition: instantiating the rejected one fails");
        }
    }

    public static class OwnServlet extends HttpServlet {}

    @Test
    void codeSourcesAreTheRootsOfTheAnnotatedComponentClasses() throws Exception {
        var ann = new DescriptorMerger.AnnotatedComponents(
                List.of(new WebAppModel.ServletDecl("own", OwnServlet.class, OwnServlet::new, List.of("/own"),
                        Map.of(), Integer.MIN_VALUE, false)), List.of(), List.of(), List.of());
        URL testClasses = OwnServlet.class.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(List.of(key(testClasses)),
                ApplicationSources.codeSources(ann).stream().map(Fragment::sourceKey).toList());
    }

    private static String key(URL u) { return Fragment.sourceKey(u); }

    private static String keyQuiet(Path p) {
        try { return key(p); } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test
    void applicationRootsHoldAWebXmlButNoFragment(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("app");
        Files.createDirectories(app.resolve("META-INF"));
        Files.writeString(app.resolve("META-INF/web.xml"), "<web-app/>");
        Path both = jar(dir, "both.jar", entries("META-INF/web.xml", "<web-app/>",
                "META-INF/web-fragment.xml", fragment("B")));
        try (var l = loader(app, both)) {
            var fragmentJars = Set.of(ApplicationSources.fragments(l).getFirst().jar());
            var roots = ApplicationSources.applicationRoots(l, fragmentJars).stream().map(Fragment::sourceKey).toList();
            assertTrue(roots.contains(key(app)), roots::toString);
            assertFalse(roots.contains(key(both)), roots::toString);
        }
    }

    // ---- §8.2.4 ordering filter -----------------------------------------------------------------

    private static final ServletContainerInitializer NOOP = (c, ctx) -> {};

    private static URL url(String s) throws Exception { return URI.create(s).toURL(); }

    private static Fragment frag(String name, String jar) throws Exception {
        return new Fragment(name, url(jar), WebAppDescriptor.empty()
                .withKind(WebAppDescriptor.Kind.WEB_FRAGMENT).withFragmentName(name));
    }

    private record Fixture(List<Initializer> all, Fragment a, Fragment b, Set<URL> fragmentJars,
                           Set<URL> app) {}

    private static Fixture fixture() throws Exception {
        Fragment a = frag("A", "jar:file:/l/a.jar!/");
        Fragment b = frag("B", "jar:file:/l/b.jar!/");
        var all = new ArrayList<Initializer>();
        all.add(new Initializer(NOOP, url("file:/l/a.jar")));     // fragment A
        all.add(new Initializer(NOOP, url("file:/l/b.jar")));     // fragment B
        all.add(new Initializer(NOOP, url("file:/l/plain.jar"))); // no fragment
        all.add(new Initializer(NOOP, url("file:/app/")));        // the application itself
        all.add(new Initializer(NOOP, null));                    // no code source
        return new Fixture(List.copyOf(all), a, b, Set.of(a.jar(), b.jar()), Set.of(url("file:/app")));
    }

    private static List<String> keys(String... urls) throws Exception {
        var out = new ArrayList<String>();
        for (String u : urls) out.add(u == null ? "null" : Fragment.sourceKey(url(u)));
        return out;
    }

    private static List<String> jars(List<Initializer> kept) {
        return kept.stream().map(i -> i.jar() == null ? "null" : Fragment.sourceKey(i.jar())).toList();
    }

    @Test
    void withoutAbsoluteOrderingEveryInitializerRuns() throws Exception {
        var f = fixture();
        var kept = ApplicationSources.retainOrdered(f.all(), null, List.of(f.a(), f.b()), f.fragmentJars(), f.app());
        assertEquals(5, kept.size(), () -> jars(kept).toString());
    }

    @Test
    void retainOrderedDropsTheInitializerOfAnExcludedFragmentJar() throws Exception {
        var f = fixture();
        var kept = ApplicationSources.retainOrdered(f.all(), List.of("A", WebAppDescriptor.OTHERS),
                List.of(f.a()), f.fragmentJars(), f.app());
        assertEquals(keys("file:/l/a.jar", "file:/l/plain.jar", "file:/app", null), jars(kept));
    }

    @Test
    void absoluteOrderingWithoutOthersAlsoExcludesJarsWithoutFragment() throws Exception {
        var f = fixture();
        var kept = ApplicationSources.retainOrdered(f.all(), List.of("A"), List.of(f.a()), f.fragmentJars(), f.app());
        assertEquals(keys("file:/l/a.jar", "file:/app", null), jars(kept),
                "the application's own initializers and those without code source always run");
    }

    @Test
    void excludedJarsAreTheDiscoveredFragmentsMissingFromTheOrdering() throws Exception {
        var f = fixture();
        assertEquals(Set.of(f.b().jar()), ApplicationSources.excludedJars(List.of(f.a(), f.b()), List.of(f.a())));
        assertEquals(Set.of(), ApplicationSources.excludedJars(List.of(f.a(), f.b()), List.of(f.b(), f.a())));
    }
}
