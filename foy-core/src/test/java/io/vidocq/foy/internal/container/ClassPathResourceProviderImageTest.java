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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-20261111-01: the {@code META-INF/resources/} of the modules of a run-time image. The test links a
 * real image with {@code jlink} and reads it through its own {@code jrt} file system, as Foy reads the
 * image it runs in. The class loader finds what the class loader of an image finds: the files of the
 * modules it sees, as {@code jrt:} URLs, and never a directory.
 */
class ClassPathResourceProviderImageTest {

    @TempDir
    static Path dir;

    private static FileSystem image;
    private static Path modules;

    /** Modules {@code m} and {@code other} hold resources, {@code app} is an application root, {@code gone} an excluded jar. */
    @BeforeAll
    static void link() throws Exception {
        Path src = dir.resolve("src");
        Path out = dir.resolve("out");
        List<String> names = List.of("m", "other", "app", "gone", "hidden");
        for (String name : names) {
            Path info = src.resolve(name).resolve("module-info.java");
            Files.createDirectories(info.getParent());
            Files.writeString(info, "module " + name + " { }\n");
        }
        run("javac", "--module-source-path", src.toString(), "-d", out.toString(),
                "--module", String.join(",", names));
        resource(out, "m", "a/b.css", "b{}");
        resource(out, "m", "a/sub/c.txt", "c");
        resource(out, "m", "index.html", "M");
        resource(out, "other", "a/d.css", "d{}");
        resource(out, "app", "index.html", "APP");
        resource(out, "gone", "g.txt", "g");
        resource(out, "hidden", "h.txt", "h");
        Path home = dir.resolve("image");
        jlink("--module-path", out.toString(), "--add-modules", String.join(",", names),
                "--output", home.toString(), "--strip-debug", "--no-header-files", "--no-man-pages");
        image = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", home.toString()));
        modules = image.getPath("/modules");
    }

    @AfterAll
    static void close() throws Exception {
        if (image != null) image.close();
    }

    private static void resource(Path out, String module, String path, String content) throws Exception {
        Path file = out.resolve(module).resolve("META-INF/resources").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void run(String tool, String... args) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(log, true, StandardCharsets.UTF_8);
        int rc = ToolProvider.findFirst(tool).orElseThrow().run(ps, ps, args);
        assertEquals(0, rc, () -> tool + " failed: " + log.toString(StandardCharsets.UTF_8));
    }

    /**
     * Runs the {@code jlink} of this JDK in a process of its own: a JDK without {@code jmods} links from its
     * run-time image, which {@code jlink} refuses in a JVM patched with {@code --patch-module}, as the tests of
     * this module are.
     */
    private static void jlink(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "jlink").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> "jlink failed: " + log);
    }

    /** The class loader of an image that sees {@code visible}: their files, never a directory. */
    private static ClassLoader imageLoader(String... visible) {
        return new ClassLoader(null) {
            @Override protected Enumeration<URL> findResources(String name) throws java.io.IOException {
                List<URL> found = new java.util.ArrayList<>();
                for (String module : visible) {
                    if (Files.isRegularFile(modules.resolve(module).resolve(name))) {
                        found.add(URI.create("jrt:/" + module + "/" + name).toURL());
                    }
                }
                return Collections.enumeration(found);
            }
        };
    }

    private static ClassPathResourceProvider provider(ClassLoader loader, List<URL> ordered, Set<URL> excluded) {
        return new ClassPathResourceProvider(loader, ordered, excluded, modules);
    }

    private static URL root(String module) throws Exception {
        return URI.create("jrt:/" + module).toURL();
    }

    private static String read(InputStream in) throws Exception {
        assertNotNull(in);
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    void theFilesOfAModuleAreServed() throws Exception {
        var p = provider(imageLoader("m", "other"), List.of(), Set.of());
        assertEquals("b{}", read(p.openStream("/a/b.css")));
        assertEquals(3, p.metadata("/a/b.css").length());
        assertEquals("jrt:/m/META-INF/resources/a/b.css", p.toUrl("/a/b.css").toString());
    }

    @Test
    void theDirectoriesOfTheModulesAreListedTogether() {
        var p = provider(imageLoader("m", "other"), List.of(), Set.of());
        assertEquals(Set.of("/a/b.css", "/a/d.css", "/a/sub/"), p.listPaths("/a/"));
        assertEquals(Set.of("/a/sub/c.txt"), p.listPaths("/a/sub"));
    }

    @Test
    void aModuleTheClassLoaderDoesNotSeeIsNotServed() {
        var p = provider(imageLoader("m"), List.of(), Set.of());
        assertNull(p.openStream("/h.txt"));
        assertNull(p.openStream("/a/d.css"));
        assertNull(p.toUrl("/h.txt"));
    }

    @Test
    void anApplicationRootInTheImageComesFirst() throws Exception {
        var p = provider(imageLoader("m", "app"), List.of(root("app")), Set.of());
        assertEquals("APP", read(p.openStream("/index.html")));
        assertEquals("jrt:/app/META-INF/resources/index.html", p.toUrl("/index.html").toString());
    }

    @Test
    void anOrderedRootTheClassLoaderDoesNotSeeIsServedAsDeclared() throws Exception {
        var p = provider(imageLoader("m"), List.of(root("app")), Set.of());
        assertEquals("APP", read(p.openStream("/index.html")));
    }

    @Test
    void anExcludedModuleIsNotServed() throws Exception {
        var p = provider(imageLoader("m", "gone"), List.of(root("gone")), Set.of(root("gone")));
        assertNull(p.openStream("/g.txt"));
        assertEquals("M", read(p.openStream("/index.html")));
    }

    @Test
    void aPathCannotLeaveTheResourcesOfAModule() throws Exception {
        var p = provider(imageLoader("m"), List.of(root("m")), Set.of());
        assertNull(p.openStream("/../module-info.class"));
        assertNull(p.toUrl("/a/../../module-info.class"));
        assertNull(p.openStream("/A/b.css"));
    }

    @Test
    void withoutAnImageAJrtRootIsIgnored() throws Exception {
        var p = new ClassPathResourceProvider(imageLoader(), List.of(root("app")), Set.of(), null);
        assertNull(p.openStream("/index.html"));
        assertNull(p.listPaths("/"));
    }
}
