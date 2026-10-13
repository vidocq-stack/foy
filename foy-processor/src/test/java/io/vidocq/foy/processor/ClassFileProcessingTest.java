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
package io.vidocq.foy.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileProcessingTest {

    @TempDir Path out;

    private static final Map<String, String> SOURCES = Map.of("a.S", """
            package a;
            @jakarta.servlet.annotation.WebServlet("/s")
            public class S extends jakarta.servlet.http.HttpServlet {
                @jakarta.servlet.annotation.WebListener
                public static class L implements jakarta.servlet.ServletContextListener {}
            }
            """);

    @Test
    void processingCompiledClassesGeneratesTheCompanions() throws Exception {
        var r = CompileHarness.processClasses(out, SOURCES, List.of("a.S"));
        assertTrue(r.success(), r.messages());
        assertTrue(Files.exists(out.resolve("generated/a/S$$FoyComponent.java")), r.messages());
        assertTrue(Files.exists(out.resolve("generated/a/S$L$$FoyComponent.java")), r.messages());
        String services = r.resource("META-INF/services/io.vidocq.foy.spi.gen.WebComponent");
        assertTrue(services.contains("a.S$$FoyComponent"), services);
        assertTrue(services.contains("a.S$L$$FoyComponent"), services);
    }

    @Test
    void passingANestedTypeAsWellAsItsOwnerDoesNotRecreateAFile() throws Exception {
        var r = CompileHarness.processClasses(out, SOURCES, List.of("a.S", "a.S$L"));
        assertTrue(r.success(), r.messages());
        assertFalse(r.messages().contains("recreate"), r.messages());
        assertTrue(Files.exists(out.resolve("generated/a/S$L$$FoyComponent.java")), r.messages());
    }

    @Test
    void theMarkerIsWrittenWhenComponentsAreGenerated() throws Exception {
        var r = CompileHarness.processClasses(out, SOURCES, List.of("a.S"));
        assertTrue(r.success(), r.messages());
        assertTrue(r.resource(DependencyHints.MARKER).startsWith("foy-processor "), r.resource(DependencyHints.MARKER));
    }

    @Test
    void aPlainClassIsIndexedSoItAlsoGetsTheMarker() throws Exception {
        // The class index lists every compiled type, so any compilation with a type has index entries.
        var r = CompileHarness.compile(out, Map.of("a.Plain", "package a; public class Plain {}"));
        assertTrue(r.success(), r.messages());
        assertFalse(Files.exists(out.resolve("generated/a/Plain$$FoyComponent.java")));
        assertTrue(Files.exists(out.resolve("classes/META-INF/foy/class-index.list")));
        assertTrue(r.resource(DependencyHints.MARKER).startsWith("foy-processor "));
    }

    @Test
    void noMarkerWhenNothingIsGenerated() throws Exception {
        // No type at all: no component and no class-index entry.
        var r = CompileHarness.compile(out, Map.of("a.package-info", "package a;"));
        assertTrue(r.success(), r.messages());
        assertFalse(Files.exists(out.resolve("classes").resolve(DependencyHints.MARKER)));
    }

    @Test
    void dependencyHintsMatchTheServletAnnotations() {
        for (Class<?> c : List.of(jakarta.servlet.annotation.WebServlet.class,
                jakarta.servlet.annotation.WebFilter.class, jakarta.servlet.annotation.WebListener.class,
                jakarta.servlet.annotation.HandlesTypes.class)) {
            String internal = c.getName().replace('.', '/');
            assertTrue(DependencyHints.CLASS_BYTE_HINTS.stream().anyMatch(internal::startsWith), internal);
        }
    }
}
