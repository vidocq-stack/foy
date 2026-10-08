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
package io.vidocq.foy.internal.gen;

import io.vidocq.foy.internal.gen.WebComponentRegistry.Tier;
import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.spi.ToolProvider;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebComponentRegistryTest {

    /** Simulates an APT-generated companion (tier 2). */
    public static class Pre extends HttpServlet {}
    public static final class Pre$$FoyComponent implements WebComponent {
        public Pre$$FoyComponent() {}
        @Override public Class<?> type() { return Pre.class; }
        @Override public WebComponentDescriptor descriptor() {
            return WebComponentDescriptor.plain().withKind(WebComponentDescriptor.Kind.SERVLET).withName("pre");
        }
        @Override public Object newInstance() { return new Pre(); }
    }

    @WebServlet("/u") public static class Unprocessed extends HttpServlet {}

    /** A companion claiming another type: must be rejected. */
    @WebServlet("/liar") public static class Liar extends HttpServlet {}
    public static final class Liar$$FoyComponent implements WebComponent {
        public Liar$$FoyComponent() {}
        @Override public Class<?> type() { return Pre.class; }
        @Override public WebComponentDescriptor descriptor() { return WebComponentDescriptor.plain(); }
        @Override public Object newInstance() { return new Pre(); }
    }

    /** Not a web component at all. */
    static class Helper { private Helper() {} }

    /** {@code @WebServlet} on a class that is not a servlet: spec-forbidden. */
    @WebServlet("/bad") public static class Misused {}

    @WebServlet("/pc") static class PrivateCtor extends HttpServlet { private PrivateCtor() {} }

    private final WebComponentRegistry registry = WebComponentRegistry.forClassLoader(getClass().getClassLoader());

    @Test
    void generatedCompanionIsPreferred() {
        var c = registry.lookup(Pre.class);
        assertEquals("pre", c.descriptor().name());
        assertEquals(Tier.GENERATED_CLASS, registry.tierOf(Pre.class));
    }

    @Test
    void companionDeclaringAnotherTypeIsSkipped() {
        registry.lookup(Liar.class);
        assertEquals(Tier.CLASS_FILE, registry.tierOf(Liar.class));
    }

    @Test
    void unprocessedClassUsesClassFileTier() {
        var c = registry.lookup(Unprocessed.class);
        assertEquals(List.of("/u"), c.descriptor().urlPatterns());
        assertInstanceOf(Unprocessed.class, c.newInstance());
        assertEquals(Tier.CLASS_FILE, registry.tierOf(Unprocessed.class));
        assertEquals(0, registry.stats().reflection());
    }

    @Test
    void lookupIsCached() {
        assertSame(registry.lookup(Unprocessed.class), registry.lookup(Unprocessed.class));
        assertEquals(1, registry.stats().classFile());
    }

    @Test
    void classWithoutReachableNoArgConstructorStaysInClassFileTierAndFailsOnInstantiation() {
        // Reflection cannot instantiate it either: the reflective tier would only add a WARNING.
        var c = registry.lookup(Helper.class);
        assertNotNull(c);
        assertEquals(WebComponentDescriptor.Kind.PLAIN, c.descriptor().kind());
        assertEquals(Tier.CLASS_FILE, registry.tierOf(Helper.class));
        assertEquals(0, registry.stats().reflection());
        var e = assertThrows(IllegalStateException.class, c::newInstance);
        assertTrue(e.getMessage().contains(Helper.class.getName()), e.getMessage());
    }

    public static class Async implements jakarta.servlet.AsyncListener {
        public void onComplete(jakarta.servlet.AsyncEvent e) {}
        public void onTimeout(jakarta.servlet.AsyncEvent e) {}
        public void onError(jakarta.servlet.AsyncEvent e) {}
        public void onStartAsync(jakarta.servlet.AsyncEvent e) {}
    }

    public static class Bare extends HttpServlet {}

    @Test
    void readableNonWebClassResolvesInClassFileTierAsPlain() {
        var c = registry.lookup(Async.class);
        assertEquals(WebComponentDescriptor.Kind.PLAIN, c.descriptor().kind());
        assertInstanceOf(Async.class, c.newInstance());
        assertEquals(Tier.CLASS_FILE, registry.tierOf(Async.class));
        assertEquals(0, registry.stats().reflection());
    }

    @Test
    void unannotatedServletIsPlain() {
        assertEquals(WebComponentDescriptor.Kind.PLAIN, registry.lookup(Bare.class).descriptor().kind());
    }

    @Test
    void annotationMisusePropagatesFromLookup() {
        assertThrows(IllegalArgumentException.class, () -> registry.lookup(Misused.class));
    }

    @Test
    void annotationMisuseBecomesServletExceptionInFactory() {
        var f = new RegistryComponentFactory(registry, getClass().getClassLoader());
        assertThrows(ServletException.class, () -> f.newInstance(Misused.class));
    }

    @Test
    void unreachableConstructorFailsWithServletException() {
        var f = new RegistryComponentFactory(registry, getClass().getClassLoader());
        var e = assertThrows(ServletException.class, () -> f.newInstance(PrivateCtor.class));
        String name = PrivateCtor.class.getName();
        assertEquals("cannot instantiate " + name + ": " + name + " has no non-private no-arg constructor",
                e.getMessage(), "the class is named once");
    }

    @Test
    void factoryDelegatesToRegistryAndHonoursVisibility() throws Exception {
        var f = new RegistryComponentFactory(registry, getClass().getClassLoader(),
                Set.of(Unprocessed.class.getName()));
        assertSame(registry, f.registry());
        assertSame(Unprocessed.class, f.load(Unprocessed.class.getName()));
        assertInstanceOf(Unprocessed.class, f.newInstance(Unprocessed.class));
        assertTrue(f.isVisible(Unprocessed.class));
        assertFalse(f.isVisible(Pre.class));
        assertTrue(new RegistryComponentFactory(registry, getClass().getClassLoader()).isVisible(Pre.class));
        assertThrows(ClassNotFoundException.class, () -> f.load("no.such.Type"));
    }

    @Test
    void sameNameDifferentLoaders() throws Exception {
        // Child-first for the test class only: the servlet API must stay the one the container sees,
        // otherwise the Class-File tier rightly reports @WebServlet on a non-Servlet.
        String name = Unprocessed.class.getName();
        try (var isolated = new URLClassLoader(new URL[]{root(Unprocessed.class)}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (!n.equals(name)) {
                    return super.loadClass(n, resolve);
                }
                synchronized (getClassLoadingLock(n)) {
                    Class<?> c = findLoadedClass(n);
                    return c != null ? c : findClass(n);
                }
            }
        }) {
            Class<?> other = isolated.loadClass(Unprocessed.class.getName());
            assertNotSame(Unprocessed.class, other);
            Object a = registry.lookup(Unprocessed.class).newInstance();
            Object b = registry.lookup(other).newInstance();
            assertSame(Unprocessed.class, a.getClass());
            assertSame(other, b.getClass());
        }
    }

    @Test
    void closedPackageFailsWithServletExceptionNamingTheOpens(@TempDir Path tmp) throws Exception {
        Class<?> closed = closedModuleServlet(tmp);
        var f = new RegistryComponentFactory(registry, closed.getClassLoader());
        var e = assertThrows(ServletException.class, () -> f.newInstance(closed));
        assertTrue(e.getMessage().contains("m.closed.Closed"), e.getMessage());
        assertTrue(e.getMessage().contains("opens m.closed"), e.getMessage());
    }

    @Test
    void closedPackageReachesTheReflectiveTierWithOneWarning(@TempDir Path tmp) throws Exception {
        Class<?> closed = closedModuleServlet(tmp);
        try (var log = io.vidocq.foy.internal.LogCapture.of(WebComponentRegistry.class.getName())) {
            registry.lookup(closed);
            registry.lookup(closed);
            assertEquals(Tier.REFLECTION, registry.tierOf(closed));
            assertEquals(1, registry.stats().reflection());
            List<String> warnings = log.warnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("m.closed.Closed resolved by reflection"), warnings.getFirst());
        }
    }

    /** A servlet in package {@code m.closed} of a named module that opens nothing to foy-core. */
    private static Class<?> closedModuleServlet(Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("m/closed"));
        Files.writeString(src.resolve("module-info.java"), "module m.closed { requires jakarta.servlet; }");
        Files.writeString(src.resolve("m/closed/Closed.java"),
                "package m.closed; public class Closed extends jakarta.servlet.http.HttpServlet { public Closed() {} }");
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        Path servletJar = Path.of(root(HttpServlet.class).toURI());
        int rc = ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "--module-path", servletJar.toString(), "-d", out.toString(),
                src.resolve("module-info.java").toString(), src.resolve("m/closed/Closed.java").toString());
        assertEquals(0, rc, "test module must compile");

        Configuration cfg = ModuleLayer.boot().configuration()
                .resolve(ModuleFinder.of(out, servletJar), ModuleFinder.of(), Set.of("m.closed"));
        ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(cfg, List.of(ModuleLayer.boot()),
                ClassLoader.getSystemClassLoader()).layer();
        Class<?> closed = layer.findLoader("m.closed").loadClass("m.closed.Closed");
        assertTrue(closed.getModule().isNamed());
        return closed;
    }

    @Test
    void linkageErrorWhileLoadingBecomesServletException() {
        var f = new RegistryComponentFactory(registry, getClass().getClassLoader());
        var e = assertThrows(ServletException.class, () -> f.load(BadInit.class.getName()));
        assertInstanceOf(ExceptionInInitializerError.class, e.getCause());
    }

    static void fail() { throw new IllegalStateException("static init failed"); }

    public static class BadInit extends HttpServlet {
        static { fail(); }
    }

    /** The class path root (directory or jar) a class was loaded from. */
    private static URL root(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        String url = type.getResource("/" + resource).toString();
        String base = url.substring(0, url.length() - resource.length());
        if (base.startsWith("jar:")) {
            base = base.substring("jar:".length(), base.length() - "!/".length());
        }
        return URI.create(base).toURL();
    }
}
