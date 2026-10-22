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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-20261022-04: an initializer that is also a {@code @WebListener}, as Vaadin's
 * {@code DevModeStartupListener}, must receive the classes its {@code @HandlesTypes} names.
 */
class FoyChappeBootListenerInitializerTest {

    private static final String PROPERTY = "bothpkg.handled";

    /**
     * A jar holding {@code bothpkg.Both}, a {@code @WebListener} that is also the initializer of
     * the jar's {@code META-INF/services} and handles {@code bothpkg.Marker}, and {@code bothpkg.Impl},
     * which implements it. {@code Both} records the names it receives (or {@code null}) in
     * {@link #PROPERTY}.
     */
    private static Path jar(Path dir) throws Exception {
        Path src = dir.resolve("src").resolve("bothpkg");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Marker.java"), "package bothpkg; public interface Marker {}");
        Files.writeString(src.resolve("Impl.java"), "package bothpkg; public class Impl implements Marker {}");
        Files.writeString(src.resolve("Both.java"), """
                package bothpkg;
                @jakarta.servlet.annotation.WebListener
                @jakarta.servlet.annotation.HandlesTypes(Marker.class)
                public class Both implements jakarta.servlet.ServletContextListener,
                        jakarta.servlet.ServletContainerInitializer {
                    public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {
                        System.setProperty("%s", c == null ? "null" : c.stream().map(Class::getName)
                                .sorted().collect(java.util.stream.Collectors.joining(",")));
                    }
                }""".formatted(PROPERTY));
        Path classes = dir.resolve("classes");
        String servletApi = Path.of(HttpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        int rc = java.util.spi.ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-cp", servletApi, "-proc:none", "-d", classes.toString(), src.resolve("Marker.java").toString(),
                src.resolve("Impl.java").toString(), src.resolve("Both.java").toString());
        assertEquals(0, rc, "test classes must compile");
        Path jar = dir.resolve("both.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[] {"Marker", "Impl", "Both"}) {
                out.putNextEntry(new JarEntry("bothpkg/" + name + ".class"));
                out.write(Files.readAllBytes(classes.resolve("bothpkg").resolve(name + ".class")));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/services/jakarta.servlet.ServletContainerInitializer"));
            out.write("bothpkg.Both\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void aWebListenerThatIsAnInitializerReceivesItsHandledClasses(@TempDir Path dir) throws Exception {
        try (var loader = new URLClassLoader(new URL[] {jar(dir).toUri().toURL()},
                FoyChappeBootListenerInitializerTest.class.getClassLoader())) {
            System.clearProperty(PROPERTY);
            var mounted = FoyChappeBoot.builder().classLoader(loader).build().orElseThrow();
            try {
                // Before the fix the descriptor kept no handled types for a listener, and the set was null.
                assertEquals("bothpkg.Impl", System.getProperty(PROPERTY));
            } finally {
                mounted.close();
            }
        } finally {
            System.clearProperty(PROPERTY);
        }
    }
}
