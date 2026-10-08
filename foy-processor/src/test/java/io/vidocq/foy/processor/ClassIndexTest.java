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

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassIndexTest {

    @TempDir Path out;

    @Test
    void indexesSupertypesTransitivelyAndAnnotations() throws Exception {
        var r = CompileHarness.compile(out, Map.of(
                "a.Base", "package a; public abstract class Base implements Runnable {}",
                "a.Impl", "package a; @Deprecated public class Impl extends Base { public void run() {} }"));
        assertTrue(r.success(), r.messages());
        var lines = r.resource("META-INF/foy/class-index.list").lines().toList();
        assertEquals("# foy class index v1", lines.getFirst());
        String impl = lines.stream().filter(l -> l.startsWith("a.Impl|")).findFirst().orElseThrow();
        var parts = impl.split("\\|", -1);
        assertTrue(parts[1].contains("a.Base") && parts[1].contains("java.lang.Runnable")
                && parts[1].contains("java.lang.Object"), impl);
        assertEquals("java.lang.Deprecated", parts[2]);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("a.Base|")));
    }

    @Test
    void usesBinaryNamesForNestedAnnotationsAndErasesGenericSupertypes() throws Exception {
        var r = CompileHarness.compile(out, Map.of(
                "a.Outer", "package a; public class Outer { public @interface Ann {} }",
                "a.Impl", "package a; @Outer.Ann public class Impl implements Comparable<Impl> "
                        + "{ public int compareTo(Impl o) { return 0; } }"));
        assertTrue(r.success(), r.messages());
        var lines = r.resource("META-INF/foy/class-index.list").lines().toList();
        String impl = lines.stream().filter(l -> l.startsWith("a.Impl|")).findFirst().orElseThrow();
        var parts = impl.split("\\|", -1);
        assertEquals("a.Outer$Ann", parts[2], impl);
        assertTrue(parts[1].contains("java.lang.Comparable") && !parts[1].contains("<"), impl);
    }

    @Test
    void indexesNestedTypesAndSkipsGeneratedCompanions() throws Exception {
        var r = CompileHarness.compile(out, Map.of(
                "a.Outer", "package a; public class Outer { public interface In {} }",
                "a.Hello", "package a; @jakarta.servlet.annotation.WebServlet(\"/h\") "
                        + "public class Hello extends jakarta.servlet.http.HttpServlet {}"));
        assertTrue(r.success(), r.messages());
        var lines = r.resource("META-INF/foy/class-index.list").lines().toList();
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("a.Outer$In|")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("a.Hello|")), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.contains("$$FoyComponent")), lines.toString());
        var names = lines.stream().skip(1).map(l -> l.substring(0, l.indexOf('|'))).toList();
        assertEquals(names.stream().sorted().toList(), names);
    }
}
