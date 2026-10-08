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
    private URLClassLoader loader(Path root) throws IOException {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, getClass().getClassLoader()) {
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
        var resolver = new IndexedHandlesTypesResolver(WebComponentRegistry.forClassLoader(cl), cl, List.of(root));
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
}
