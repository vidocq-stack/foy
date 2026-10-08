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

import jakarta.servlet.ServletContainerInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileHandlesTypesScannerTest {

    @TempDir Path tmp;

    private static final String BASE = "package p; public abstract class Base implements Runnable {}";
    private static final String IMPL = "package p; public class Impl extends Base { public void run() {} }";
    private static final String MARKER =
            "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Marker {}";
    private static final String M = "package p; @Marker public class M {}";
    private static final String MEMBER = "package p; public class Member { @Marker public void m() {} }";
    private static final String UNRELATED = "package p; public class Unrelated {}";
    private static final String SCI_BODY = " implements jakarta.servlet.ServletContainerInitializer {"
            + " public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext x) {} }";
    private static final String SCI =
            "package p; @jakarta.servlet.annotation.HandlesTypes({Runnable.class, p.Marker.class}) public class Sci"
                    + SCI_BODY;
    private static final String SCI_RUN =
            "package p; @jakarta.servlet.annotation.HandlesTypes({Runnable.class}) public class RunSci" + SCI_BODY;
    private static final String OTHER_SCI =
            "package p; @jakarta.servlet.annotation.HandlesTypes({java.io.Closeable.class}) public class OtherSci"
                    + SCI_BODY;
    private static final String BASE_SCI =
            "package p; @jakarta.servlet.annotation.HandlesTypes({p.Base.class}) public class BaseSci" + SCI_BODY;

    private Path compileToDir(String... sources) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src" + System.nanoTime()));
        Path out = Files.createDirectories(tmp.resolve("out" + System.nanoTime()));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp", classpath()));
        for (String s : sources) {
            String name = s.replaceAll("(?s).*?(class|@interface)\\s+(\\w+).*", "$2");
            Path file = src.resolve(name + ".java");
            Files.writeString(file, s);
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "compilation failed");
        return out;
    }

    private static String classpath() {
        String api = ServletContainerInitializer.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return System.getProperty("java.class.path") + System.getProperty("path.separator") + api;
    }

    private Path jar(Path dir, String name) throws IOException {
        Path jar = tmp.resolve(name);
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os);
             Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(dir.relativize(f).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(f));
                jos.closeEntry();
            }
        }
        return jar;
    }

    /** The root's loader; the test classpath's own class index stays out of sight. */
    private URLClassLoader loader(Path... roots) throws IOException {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.startsWith("META-INF/foy/") ? findResources(name) : super.getResources(name);
            }
        };
    }

    private static Set<String> names(Set<Class<?>> classes) {
        return classes == null ? null : classes.stream().map(Class::getName).collect(Collectors.toSet());
    }

    private Set<Class<?>> resolve(Path root, URLClassLoader cl, String sci) throws Exception {
        return resolve(List.of(root), cl, sci);
    }

    private Set<Class<?>> resolve(List<Path> roots, URLClassLoader cl, String sci) throws Exception {
        var resolver = new IndexedHandlesTypesResolver(WebComponentRegistry.forClassLoader(cl), cl, roots);
        var instance = (ServletContainerInitializer) cl.loadClass(sci).getDeclaredConstructor().newInstance();
        return resolver.resolve(instance);
    }

    @Test
    void jarWithoutClassIndexIsScanned() throws Exception {
        Path dir = compileToDir(BASE, IMPL, MARKER, M, MEMBER, UNRELATED, SCI, OTHER_SCI, BASE_SCI);
        Path jar = jar(dir, "app.jar");
        try (URLClassLoader cl = loader(jar)) {
            // Runnable itself is excluded; Base/Impl by supertype, M by type annotation, Member by method annotation.
            assertEquals(Set.of("p.Base", "p.Impl", "p.M", "p.Member"), names(resolve(jar, cl, "p.Sci")));
            assertNull(resolve(jar, cl, "p.OtherSci"));
            // The handled type itself is excluded.
            assertEquals(Set.of("p.Impl"), names(resolve(jar, cl, "p.BaseSci")));
        }
    }

    @Test
    void directoryRootIsScanned() throws Exception {
        Path dir = compileToDir(BASE, IMPL, MARKER, M, SCI);
        try (URLClassLoader cl = loader(dir)) {
            assertEquals(Set.of("p.Base", "p.Impl", "p.M"), names(resolve(dir, cl, "p.Sci")));
        }
    }

    @Test
    void rootWithClassIndexIsSkippedByTheScanner() throws Exception {
        Path dir = compileToDir(BASE, IMPL, MARKER, M, SCI);
        Path idx = Files.createDirectories(dir.resolve("META-INF/foy"));
        Files.writeString(idx.resolve("class-index.list"), "");
        assertTrue(ClassFileHandlesTypesScanner.scan(List.of(dir), getClass().getClassLoader()).isEmpty());
    }

    @Test
    void supertypesComeFromScannedRootsAndTheLoader() throws Exception {
        Path dir = compileToDir(BASE, IMPL);
        var entries = ClassFileHandlesTypesScanner.scan(List.of(dir), getClass().getClassLoader());
        var impl = entries.stream().filter(e -> e.name().equals("p.Impl")).findFirst().orElseThrow();
        assertTrue(impl.supertypes().containsAll(Set.of("p.Base", "java.lang.Runnable", "java.lang.Object")));
    }

    @Test
    void jarRootWithClassIndexIsSkippedByTheScanner() throws Exception {
        Path dir = compileToDir(BASE, IMPL);
        Path idx = Files.createDirectories(dir.resolve("META-INF/foy"));
        Files.writeString(idx.resolve("class-index.list"), "# foy class index v1\n");
        Path jar = jar(dir, "indexed.jar");
        assertTrue(ClassFileHandlesTypesScanner.scan(List.of(jar), getClass().getClassLoader()).isEmpty());
    }

    @Test
    void mixedDeploymentMergesTheIndexedRootAndTheScannedRoot() throws Exception {
        Path indexed = compileToDir("package p; public class A implements Runnable { public void run() {} }");
        Path idx = Files.createDirectories(indexed.resolve("META-INF/foy"));
        Files.writeString(idx.resolve("class-index.list"),
                "# foy class index v1\np.A|java.lang.Runnable,java.lang.Object|\n");
        Path scanned = compileToDir("package p; public class B implements Runnable { public void run() {} }",
                "package p; @jakarta.servlet.annotation.HandlesTypes({Runnable.class}) public class RunSci"
                        + SCI_BODY);
        try (URLClassLoader cl = loader(indexed, scanned)) {
            assertEquals(Set.of("p.A", "p.B"), names(resolve(List.of(indexed, scanned), cl, "p.RunSci")));
        }
    }

    @Test
    void scanNamedReadsTheNamedClassesThroughTheLoader() throws Exception {
        Path dir = compileToDir(BASE, IMPL, UNRELATED);
        try (URLClassLoader cl = loader(dir)) {
            var entries = ClassFileHandlesTypesScanner.scanNamed(List.of("p.Impl", "p.Absent"), cl);
            assertEquals(List.of("p.Impl"), entries.stream().map(ClassFileHandlesTypesScanner.Entry::name).toList());
            assertTrue(entries.getFirst().supertypes().containsAll(Set.of("p.Base", "java.lang.Runnable")));
        }
    }

    @Test
    void unreadableSupertypeEndsItsBranchWithoutFailing() throws Exception {
        Path dir = compileToDir("package q; public class Missing implements Runnable { public void run() {} }",
                "package p; public class Child extends q.Missing {}");
        Files.delete(dir.resolve("q/Missing.class"));
        var entries = ClassFileHandlesTypesScanner.scan(List.of(dir), getClass().getClassLoader());
        var child = entries.stream().filter(e -> e.name().equals("p.Child")).findFirst().orElseThrow();
        assertEquals(Set.of("q.Missing"), child.supertypes());
    }

    @Test
    void scanOnlyResolverIgnoresLoaderWideClassIndexes() throws Exception {
        Path dir = compileToDir(IMPL.replace("extends Base ", "implements Runnable "), SCI_RUN);
        try (URLClassLoader cl = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            // The test classpath ships a class index whose Runnable implementors must stay out of sight.
            var resolver = IndexedHandlesTypesResolver.scanOnly(WebComponentRegistry.forClassLoader(cl), cl,
                    () -> ClassFileHandlesTypesScanner.scanNamed(List.of("p.Impl"), cl));
            var sci = (ServletContainerInitializer) cl.loadClass("p.RunSci").getDeclaredConstructor().newInstance();
            assertEquals(Set.of("p.Impl"), names(resolver.resolve(sci)));
        }
    }

    /** Same sources and expectations as the processor's {@code ClassIndexTest}: both sides agree. */
    @Test
    void annotationColumnParity() throws Exception {
        Path dir = compileToDir(
                "package a; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                        + "public @interface Ann {}",
                "package a; public @interface Cls {}",
                "package a; @Ann @Cls public class OnType {}",
                "package a; public class OnField { @Ann int f; }",
                "package a; public class OnMethod { @Ann void m() {} }",
                "package a; public class OnCtor { @Ann OnCtor() {} }",
                "package a; @Cls public class ClassOnly { @Cls void m() {} }");
        var entries = ClassFileHandlesTypesScanner.scan(List.of(dir), getClass().getClassLoader());
        for (String name : new String[] {"a.OnType", "a.OnField", "a.OnMethod", "a.OnCtor"}) {
            assertEquals(Set.of("a.Ann"), entry(entries, name).annotations(), name);
        }
        assertEquals(Set.of(), entry(entries, "a.ClassOnly").annotations());
    }

    private static ClassFileHandlesTypesScanner.Entry entry(List<ClassFileHandlesTypesScanner.Entry> entries,
                                                           String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }
}
