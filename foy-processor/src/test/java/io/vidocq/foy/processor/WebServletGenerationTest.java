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

import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebServletGenerationTest {

    @TempDir Path out;

    private static final String HELLO = """
            package com.acme;
            import jakarta.servlet.annotation.*;
            @WebServlet(name = "hello", urlPatterns = "/h", loadOnStartup = 1, asyncSupported = true,
                        initParams = @WebInitParam(name = "k", value = "v\\"q"))
            public class Hello extends jakarta.servlet.http.HttpServlet {}
            """;

    private WebComponent load(CompileHarness.Result r, String fqcn) throws Exception {
        return (WebComponent) r.loader().loadClass(fqcn).getConstructor().newInstance();
    }

    @Test
    void generatesDescriptorAndFactory() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.Hello", HELLO));
        assertTrue(r.success(), r.messages());
        WebComponent c = load(r, "com.acme.Hello$$FoyComponent");
        assertEquals("com.acme.Hello", c.type().getName());
        assertEquals("com.acme.Hello", c.newInstance().getClass().getName());
        var d = c.descriptor();
        assertEquals(Kind.SERVLET, d.kind());
        assertEquals("hello", d.name());
        assertEquals(List.of("/h"), d.urlPatterns());
        assertEquals(Map.of("k", "v\"q"), d.initParams());
        assertEquals(1, d.loadOnStartup());
        assertTrue(d.asyncSupported());
    }

    @Test
    void valueIsAnAliasAndDefaultNameIsTheClassName() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.V", """
                package com.acme;
                @jakarta.servlet.annotation.WebServlet("/v")
                public class V extends jakarta.servlet.http.HttpServlet {}
                """));
        assertTrue(r.success(), r.messages());
        var d = load(r, "com.acme.V$$FoyComponent").descriptor();
        assertEquals("com.acme.V", d.name());
        assertEquals(List.of("/v"), d.urlPatterns());
        assertEquals(Integer.MIN_VALUE, d.loadOnStartup());
    }

    @Test
    void staticNestedClassUsesBinaryName() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.Outer", """
                package com.acme;
                public class Outer {
                    @jakarta.servlet.annotation.WebServlet("/n")
                    public static class Inner extends jakarta.servlet.http.HttpServlet {}
                }
                """));
        assertTrue(r.success(), r.messages());
        assertEquals("com.acme.Outer$Inner", load(r, "com.acme.Outer$Inner$$FoyComponent").type().getName());
    }

    @Test
    void privateConstructorGetsNoteAndNoGeneratedClass() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.P", """
                package com.acme;
                @jakarta.servlet.annotation.WebServlet("/p")
                public class P extends jakarta.servlet.http.HttpServlet { private P() {} }
                """));
        assertTrue(r.success(), r.messages());
        assertThrows(ClassNotFoundException.class, () -> r.loader().loadClass("com.acme.P$$FoyComponent"));
        assertTrue(r.messages().contains("NOTE") && r.messages().contains("com.acme.P"), r.messages());
    }

    @Test
    void writesServiceFile() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.Hello", HELLO));
        assertEquals("com.acme.Hello$$FoyComponent",
                r.resource("META-INF/services/io.vidocq.foy.spi.gen.WebComponent").strip());
    }
}
