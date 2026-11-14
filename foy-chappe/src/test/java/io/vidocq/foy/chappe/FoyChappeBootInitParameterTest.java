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

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@code FoyChappeBoot.Builder.initParameter}: init parameters set by the code that boots Foy. */
class FoyChappeBootInitParameterTest {

    private static final String NAME = "io.vidocq.foy.websocket.allowed-origins";
    private static final AtomicReference<String> SEEN = new AtomicReference<>();

    /** Records what the context answers for {@link #NAME} while the initializers run. */
    public static class Recorder implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> classes, ServletContext ctx) {
            SEEN.set(ctx.getInitParameter(NAME));
        }
    }

    private static final String XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <context-param><param-name>io.vidocq.foy.websocket.allowed-origins</param-name>
                <param-value>https://descriptor.example</param-value></context-param>
              <context-param><param-name>other</param-name><param-value>kept</param-value></context-param>
            </web-app>""";

    private static FoyChappeBoot.Builder app(Path dir) throws Exception {
        Path services = dir.resolve("META-INF").resolve("services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ServletContainerInitializer.class.getName()), Recorder.class.getName());
        ClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()},
                FoyChappeBootInitParameterTest.class.getClassLoader());
        return FoyChappeBoot.builder().contextPath("/").classLoader(loader)
                .webXml(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anInitParameterFromTheBuilderReachesInitializers(@TempDir Path dir) throws Exception {
        SEEN.set(null);
        try (var mounted = app(dir).initParameter(NAME, "https://builder.example").build().orElseThrow()) {
            assertEquals("https://builder.example", SEEN.get());
            assertEquals("https://builder.example", mounted.servletContext().getInitParameter(NAME));
        }
    }

    @Test
    void theBuilderInitParameterWinsOverTheDescriptor(@TempDir Path dir) throws Exception {
        try (var mounted = app(dir).initParameter(NAME, "first").initParameter(NAME, "https://builder.example")
                .build().orElseThrow()) {
            var ctx = mounted.servletContext();
            assertEquals("https://builder.example", ctx.getInitParameter(NAME));
            assertEquals("kept", ctx.getInitParameter("other"));
            List<String> names = Collections.list(ctx.getInitParameterNames());
            assertEquals(1, names.stream().filter(NAME::equals).count());
            assertTrue(names.contains("other"));
        }
    }

    @Test
    void aNullNameOrValueIsRejected() {
        var builder = FoyChappeBoot.builder();
        assertThrows(NullPointerException.class, () -> builder.initParameter(null, "v"));
        assertThrows(NullPointerException.class, () -> builder.initParameter("n", null));
    }
}
