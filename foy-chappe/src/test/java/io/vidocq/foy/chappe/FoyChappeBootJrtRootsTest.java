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

import jakarta.servlet.http.HttpServlet;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-20261015-01: in a jlink image every module's code source is {@code jrt:/<module>}. The
 * {@code @HandlesTypes} set of an initializer from such a module must come from that module's classes.
 */
class FoyChappeBootJrtRootsTest {

    private static final String PROPERTY = "jrtpkg.handled";

    /**
     * A jar holding initializer {@code jrtpkg.Sci}, {@code @HandlesTypes(java.util.logging.Formatter.class)},
     * which records the names of the classes it receives (or {@code null}) in {@link #PROPERTY}.
     */
    private static Path sciJar(Path dir) throws Exception {
        Path src = dir.resolve("src").resolve("jrtpkg");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Sci.java"), """
                package jrtpkg;
                @jakarta.servlet.annotation.HandlesTypes(java.util.logging.Formatter.class)
                public class Sci implements jakarta.servlet.ServletContainerInitializer {
                    public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {
                        System.setProperty("%s", c == null ? "null" : c.stream().map(Class::getName)
                                .sorted().collect(java.util.stream.Collectors.joining(",")));
                    }
                }""".formatted(PROPERTY));
        Path classes = dir.resolve("classes");
        String servletApi = Path.of(HttpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        int rc = java.util.spi.ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-cp", servletApi, "-d", classes.toString(), src.resolve("Sci.java").toString());
        assertEquals(0, rc, "test initializer must compile");
        Path jar = dir.resolve("sci.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("jrtpkg/Sci.class"));
            out.write(Files.readAllBytes(classes.resolve("jrtpkg").resolve("Sci.class")));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/jakarta.servlet.ServletContainerInitializer"));
            out.write("jrtpkg.Sci\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void anInitializerOfALinkedModuleReceivesTheModuleClasses(@TempDir Path dir) throws Exception {
        // As in an image: the initializer's code source is its module's jrt root, which holds the handled classes.
        URL module = URI.create("jrt:/java.logging").toURL();
        try (var loader = new URLClassLoader(new URL[] {sciJar(dir).toUri().toURL()},
                FoyChappeBootJrtRootsTest.class.getClassLoader())) {
            System.clearProperty(PROPERTY);
            var mounted = FoyChappeBoot.builder().classLoader(loader)
                    .componentSource(type -> type.getName().startsWith("jrtpkg.") ? module : null)
                    .build().orElseThrow();
            try {
                // Without the jrt root the set is null; the module also holds JDK-internal formatters.
                var handled = java.util.List.of(System.getProperty(PROPERTY).split(","));
                assertTrue(handled.containsAll(java.util.List.of("java.util.logging.SimpleFormatter",
                        "java.util.logging.XMLFormatter")), handled.toString());
            } finally {
                mounted.close();
            }
        } finally {
            System.clearProperty(PROPERTY);
        }
    }
}
