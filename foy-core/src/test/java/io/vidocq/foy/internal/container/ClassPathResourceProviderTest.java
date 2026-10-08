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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ClassPathResourceProviderTest {

    private static Path jar(Path dir, String name, String... pathAndContent) throws Exception {
        Path jar = dir.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < pathAndContent.length; i += 2) {
                out.putNextEntry(new JarEntry(pathAndContent[i]));
                out.write(pathAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String read(InputStream in) throws Exception {
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String read(URL url) throws Exception {
        return read(url.openStream());
    }

    private static URLClassLoader loader(Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new URLClassLoader(urls, null);
    }

    private Path a, b;

    private ClassPathResourceProvider twoJars(Path dir, boolean reversed) throws Exception {
        a = jar(dir, "a.jar", "META-INF/resources/index.html", "A", "META-INF/resources/css/a.css", "a{}");
        b = jar(dir, "b.jar", "META-INF/resources/index.html", "B", "META-INF/resources/css/b.css", "b{}");
        List<URL> order = reversed ? List.of(b.toUri().toURL(), a.toUri().toURL())
                : List.of(a.toUri().toURL(), b.toUri().toURL());
        return new ClassPathResourceProvider(loader(a, b), order);
    }

    @Test
    void firstJarInOrderWins(@TempDir Path dir) throws Exception {
        var p = twoJars(dir, false);
        assertEquals("A", read(p.toUrl("/index.html")));
        assertEquals("A", read(p.openStream("/index.html")));
        assertEquals("b{}", read(p.toUrl("/css/b.css")));
        var reversed = twoJars(dir, true);
        assertEquals("B", read(reversed.toUrl("/index.html")));
        assertEquals("B", read(reversed.openStream("/index.html")));
    }

    @Test
    void urlsAreJarUrlsForJarsAndFileUrlsForExplodedRoots(@TempDir Path dir) throws Exception {
        var p = twoJars(dir, false);
        assertEquals("jar", p.toUrl("/css/a.css").getProtocol());
        Path exploded = dir.resolve("exploded");
        Files.createDirectories(exploded.resolve("META-INF/resources/img"));
        Files.writeString(exploded.resolve("META-INF/resources/img/x.txt"), "X");
        var q = new ClassPathResourceProvider(loader(), List.of(exploded.toUri().toURL()));
        URL url = q.toUrl("/img/x.txt");
        assertEquals("file", url.getProtocol());
        assertEquals("X", read(url));
        assertEquals("X", read(q.openStream("/img/x.txt")));
        assertEquals(Set.of("/img/x.txt"), q.listPaths("/img/"));
        assertEquals(Set.of("/img/"), q.listPaths("/"));
    }

    @Test
    void resourcePathsAreTheUnionOfDirectChildren(@TempDir Path dir) throws Exception {
        var p = twoJars(dir, false);
        assertEquals(Set.of("/css/a.css", "/css/b.css"), p.listPaths("/css/"));
        assertEquals(Set.of("/css/a.css", "/css/b.css"), p.listPaths("/css"));
        assertEquals(Set.of("/index.html", "/css/"), p.listPaths("/"));
        assertEquals(List.of("/css/", "/index.html"), List.copyOf(p.listPaths("/")), "stable order");
        assertNull(p.listPaths("/nothing/"));
        assertNull(p.listPaths("/index.html/x/"));
    }

    @Test
    void directoriesWithoutEntryAreStillListed(@TempDir Path dir) throws Exception {
        // jar() writes no directory entries at all
        var p = twoJars(dir, false);
        assertNotNull(p.toUrl("/css/"));
        assertNull(p.openStream("/css/"));
    }

    @Test
    void unsafePathsAreRejected(@TempDir Path dir) throws Exception {
        var p = twoJars(dir, false);
        for (String bad : new String[] {"/../x", "/css/../index.html", "/css\\a.css", "/index.html\0", "index.html"}) {
            assertNull(p.toUrl(bad), bad);
            assertNull(p.openStream(bad), bad);
            assertNull(p.listPaths(bad), bad);
        }
        assertNull(p.toUrl(null));
    }

    @Test
    void missingResourcesAreNull(@TempDir Path dir) throws Exception {
        var p = twoJars(dir, false);
        assertNull(p.toUrl("/nope.css"));
        assertNull(p.openStream("/nope.css"));
    }

    @Test
    // Roots outside the ordered list are found through the class loader: their jars need directory entries to be listed.
    void rootsOutsideTheOrderedListComeAfterIt(@TempDir Path dir) throws Exception {
        Path c = jar(dir, "c.jar", "META-INF/resources/", "", "META-INF/resources/index.html", "C", "META-INF/resources/only-c.txt", "c");
        Path a = jar(dir, "a.jar", "META-INF/resources/index.html", "A");
        var p = new ClassPathResourceProvider(loader(c, a), List.of(a.toUri().toURL()));
        assertEquals("A", read(p.openStream("/index.html")), "ordered jars first, whatever the class path order");
        assertEquals("c", read(p.openStream("/only-c.txt")));
        assertEquals(Set.of("/index.html", "/only-c.txt"), p.listPaths("/"));
    }

    @Test
    void metaInfAndWebInfAreNotServableByTheDefaultServlet() {
        assertFalse(ClassPathResourceProvider.isServable("/WEB-INF/web.xml"));
        assertFalse(ClassPathResourceProvider.isServable("/web-inf/x"));
        assertFalse(ClassPathResourceProvider.isServable("/META-INF/MANIFEST.MF"));
        assertFalse(ClassPathResourceProvider.isServable("/WEB-INF"));
        assertFalse(ClassPathResourceProvider.isServable("/a/../b"));
        assertFalse(ClassPathResourceProvider.isServable("/a\\b"));
        assertTrue(ClassPathResourceProvider.isServable("/css/META-INF.css"));
        assertTrue(ClassPathResourceProvider.isServable("/css/a.css"));
    }

    @Test
    void getResourceStillReachesWebInfAndMetaInfUnderTheRoot(@TempDir Path dir) throws Exception {
        Path j = jar(dir, "w.jar", "META-INF/resources/WEB-INF/x.txt", "w");
        var p = new ClassPathResourceProvider(loader(j), List.of(j.toUri().toURL()));
        assertEquals("w", read(p.toUrl("/WEB-INF/x.txt")));
    }

    @Test
    void emptySegmentsCannotEscapeAnExplodedRoot(@TempDir Path dir) throws Exception {
        Path exploded = dir.resolve("exploded");
        Files.createDirectories(exploded.resolve("META-INF/resources/a"));
        Files.writeString(exploded.resolve("META-INF/resources/a/x.txt"), "X");
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "SECRET");
        var p = new ClassPathResourceProvider(loader(), List.of(exploded.toUri().toURL()));
        String abs = secret.toString();
        for (String bad : new String[] {"/" + abs, "//", "///", "/a//x.txt", "/a//", "//a/x.txt"}) {
            assertNull(p.toUrl(bad), bad);
            assertNull(p.openStream(bad), bad);
            assertNull(p.listPaths(bad), bad);
        }
        assertEquals("X", read(p.openStream("/a/x.txt")));
        assertEquals(Set.of("/a/x.txt"), p.listPaths("/a/"));
        assertFalse(ClassPathResourceProvider.isServable("//x"));
        assertFalse(ClassPathResourceProvider.isServable("//"));
    }

    @Test
    void dotSegmentsAreRejected(@TempDir Path dir) throws Exception {
        Path exploded = dir.resolve("exploded");
        Files.createDirectories(exploded.resolve("META-INF/resources/a"));
        Files.createDirectories(exploded.resolve("META-INF/resources/WEB-INF"));
        Files.createDirectories(exploded.resolve("META-INF/resources/META-INF"));
        Files.writeString(exploded.resolve("META-INF/resources/a/x.txt"), "X");
        Files.writeString(exploded.resolve("META-INF/resources/WEB-INF/web.xml"), "W");
        Files.writeString(exploded.resolve("META-INF/resources/META-INF/x"), "M");
        var p = new ClassPathResourceProvider(loader(), List.of(exploded.toUri().toURL()));
        for (String bad : new String[] {"/./WEB-INF/web.xml", "/./META-INF/x", "/a/x.txt/.", "/a/./x.txt"}) {
            assertFalse(ClassPathResourceProvider.isServable(bad), bad);
            assertNull(p.openStream(bad), bad);
            assertNull(p.toUrl(bad), bad);
        }
        assertNotNull(p.openStream("/WEB-INF/web.xml"), "reachable through getResource, not servable");
    }

    @Test
    void symbolicLinksCannotLeaveAnExplodedRoot(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("exploded/META-INF/resources");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/ok.txt"), "OK");
        Path outside = dir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "SECRET");
        try {
            Files.createSymbolicLink(root.resolve("linkdir"), outside);
            Files.createSymbolicLink(root.resolve("a/linkfile.txt"), outside.resolve("secret.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + e);
        }
        var p = new ClassPathResourceProvider(loader(), List.of(dir.resolve("exploded").toUri().toURL()));
        assertNull(p.openStream("/linkdir/secret.txt"));
        assertNull(p.toUrl("/linkdir/secret.txt"));
        assertNull(p.listPaths("/linkdir/"));
        assertNull(p.openStream("/a/linkfile.txt"));
        assertEquals(Set.of("/a/ok.txt"), p.listPaths("/a/"), "escaping links are not listed");
        assertEquals(Set.of("/a/"), p.listPaths("/"));
        assertEquals("OK", read(p.openStream("/a/ok.txt")));
    }

    @Test
    void symbolicLinksCannotLeaveAClassLoaderDirectoryRoot(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("exploded/META-INF/resources");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/ok.txt"), "OK");
        Path outside = dir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "SECRET");
        try {
            Files.createSymbolicLink(root.resolve("a/hop1"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(root.resolve("a/chain.txt"), root.resolve("a/hop1"));
            Files.createSymbolicLink(root.resolve("a/hopdir"), outside);
            Files.createSymbolicLink(root.resolve("a/chaindir"), root.resolve("a/hopdir"));
            Files.createSymbolicLink(root.resolve("a/relout.txt"), Path.of("../../../outside/secret.txt"));
            Files.createSymbolicLink(root.resolve("a/inside.txt"), Path.of("ok.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + e);
        }
        var p = new ClassPathResourceProvider(
                new URLClassLoader(new URL[] {dir.resolve("exploded").toUri().toURL()}, null), List.of());
        for (String bad : new String[] {"/a/chain.txt", "/a/chaindir/secret.txt", "/a/relout.txt",
                "/a/hop1", "/a/hopdir/secret.txt"}) {
            assertNull(p.openStream(bad), bad);
            assertNull(p.toUrl(bad), bad);
        }
        assertNull(p.listPaths("/a/chaindir/"));
        assertEquals(Set.of("/a/ok.txt", "/a/inside.txt"), p.listPaths("/a/"));
        assertEquals("OK", read(p.openStream("/a/ok.txt")));
        assertEquals("OK", read(p.openStream("/a/inside.txt")), "a link staying under the root works");
    }
}
