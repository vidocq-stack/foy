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
import jakarta.servlet.DispatcherType;
import jakarta.servlet.annotation.ServletSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentKindsGenerationTest {

    private static final String GEN = "$$FoyComponent";

    @TempDir Path out;

    private WebComponent component(CompileHarness.Result r, String fqcn) throws Exception {
        return (WebComponent) r.loader().loadClass(fqcn + GEN).getConstructor().newInstance();
    }

    private WebComponent compileOne(String fqcn, String src) throws Exception {
        var r = CompileHarness.compile(out, Map.of(fqcn, src));
        assertTrue(r.success(), r.messages());
        return component(r, fqcn);
    }

    private CompileHarness.Result compileFailing(String fqcn, String src) throws Exception {
        var r = CompileHarness.compile(out, Map.of(fqcn, src));
        assertFalse(r.success(), "expected a compile error");
        return r;
    }

    @Test
    void filter() throws Exception {
        var d = compileOne("a.F", """
                package a;
                import jakarta.servlet.*; import jakarta.servlet.annotation.*;
                @WebFilter(urlPatterns = "/*", servletNames = "s", dispatcherTypes = {DispatcherType.FORWARD, DispatcherType.ERROR})
                public class F implements Filter {
                    public void doFilter(ServletRequest q, ServletResponse r, FilterChain c) {}
                }
                """).descriptor();
        assertEquals(Kind.FILTER, d.kind());
        assertEquals("a.F", d.name());
        assertEquals(List.of("/*"), d.urlPatterns());
        assertEquals(List.of("s"), d.servletNames());
        assertEquals(Set.of(DispatcherType.FORWARD, DispatcherType.ERROR), d.dispatcherTypes());
    }

    @Test
    void filterDefaultsToRequestAndToleratesDuplicateDispatcherTypes() throws Exception {
        var plain = compileOne("a.F1", """
                package a;
                @jakarta.servlet.annotation.WebFilter("/x")
                public class F1 implements jakarta.servlet.Filter {
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                         jakarta.servlet.FilterChain c) {}
                }
                """).descriptor();
        assertEquals(Set.of(DispatcherType.REQUEST), plain.dispatcherTypes());
        var dup = compileOne("a.F2", """
                package a;
                @jakarta.servlet.annotation.WebFilter(value = "/x",
                    dispatcherTypes = {jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.REQUEST},
                    initParams = @jakarta.servlet.annotation.WebInitParam(name = "k", value = "v"), asyncSupported = true)
                public class F2 implements jakarta.servlet.Filter {
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                         jakarta.servlet.FilterChain c) {}
                }
                """).descriptor();
        assertEquals(Set.of(DispatcherType.REQUEST), dup.dispatcherTypes());
        assertEquals(Map.of("k", "v"), dup.initParams());
        assertTrue(dup.asyncSupported());
    }

    @Test
    void listenerAndPlainAndInitializer() throws Exception {
        var r = CompileHarness.compile(out, Map.of(
                "a.L", """
                    package a;
                    @jakarta.servlet.annotation.WebListener
                    public class L implements jakarta.servlet.ServletContextListener {}
                    """,
                "a.Plain", """
                    package a;
                    public class Plain extends jakarta.servlet.http.HttpServlet {}
                    """,
                "a.Init", """
                    package a;
                    @jakarta.servlet.annotation.HandlesTypes({java.lang.Runnable.class, jakarta.servlet.annotation.WebServlet.class})
                    public class Init implements jakarta.servlet.ServletContainerInitializer {
                        public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {}
                    }
                    """));
        assertTrue(r.success(), r.messages());
        assertEquals(Kind.LISTENER, component(r, "a.L").descriptor().kind());
        assertEquals(Kind.PLAIN, component(r, "a.Plain").descriptor().kind());
        var i = component(r, "a.Init").descriptor();
        assertEquals(Kind.INITIALIZER, i.kind());
        assertEquals(List.of("java.lang.Runnable", "jakarta.servlet.annotation.WebServlet"), i.handlesTypes());
    }

    @Test
    void unannotatedListenerFilterAndBareInitializer() throws Exception {
        var r = CompileHarness.compile(out, Map.of(
                "a.PL", "package a; public class PL implements jakarta.servlet.http.HttpSessionListener {}",
                "a.PF", """
                    package a;
                    public class PF implements jakarta.servlet.Filter {
                        public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                             jakarta.servlet.FilterChain c) {}
                    }
                    """,
                "a.BI", """
                    package a;
                    public class BI implements jakarta.servlet.ServletContainerInitializer {
                        public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {}
                    }
                    """,
                "a.Abs", "package a; public abstract class Abs extends jakarta.servlet.http.HttpServlet {}",
                "a.Other", "package a; public class Other {}"));
        assertTrue(r.success(), r.messages());
        assertEquals(Kind.PLAIN, component(r, "a.PL").descriptor().kind());
        assertEquals(Kind.PLAIN, component(r, "a.PF").descriptor().kind());
        var bi = component(r, "a.BI").descriptor();
        assertEquals(Kind.INITIALIZER, bi.kind());
        assertEquals(List.of(), bi.handlesTypes());
        assertThrows(ClassNotFoundException.class, () -> r.loader().loadClass("a.Abs" + GEN));
        assertThrows(ClassNotFoundException.class, () -> r.loader().loadClass("a.Other" + GEN));
        assertFalse(r.messages().contains("no generated component"), r.messages());
    }

    @Test
    void securityMultipartRolesRunAs() throws Exception {
        var d = compileOne("a.S", """
                package a;
                import jakarta.servlet.annotation.*;
                @WebServlet("/s")
                @MultipartConfig(location = "/tmp", maxFileSize = 10, maxRequestSize = 20, fileSizeThreshold = 5)
                @ServletSecurity(value = @HttpConstraint(rolesAllowed = "admin"),
                                 httpMethodConstraints = @HttpMethodConstraint(value = "GET",
                                     emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.PERMIT))
                @jakarta.annotation.security.DeclareRoles({"admin", "user"})
                @jakarta.annotation.security.RunAs("admin")
                public class S extends jakarta.servlet.http.HttpServlet {}
                """).descriptor();
        assertEquals("/tmp", d.multipartConfig().getLocation());
        assertEquals(10, d.multipartConfig().getMaxFileSize());
        assertEquals(20, d.multipartConfig().getMaxRequestSize());
        assertEquals(5, d.multipartConfig().getFileSizeThreshold());
        assertArrayEquals(new String[]{"admin"}, d.servletSecurity().getRolesAllowed());
        var get = d.servletSecurity().getHttpMethodConstraints().iterator().next();
        assertEquals("GET", get.getMethodName());
        assertEquals(ServletSecurity.EmptyRoleSemantic.PERMIT, get.getEmptyRoleSemantic());
        assertEquals(List.of("admin", "user"), d.declaredRoles());
        assertEquals("admin", d.runAs());
    }

    @Test
    void methodConstraintKeepsTransportGuaranteeAndRoles() throws Exception {
        var d = compileOne("a.M", """
                package a;
                import jakarta.servlet.annotation.*;
                @WebServlet("/m")
                @ServletSecurity(value = @HttpConstraint(transportGuarantee = ServletSecurity.TransportGuarantee.CONFIDENTIAL),
                    httpMethodConstraints = @HttpMethodConstraint(value = "POST",
                        transportGuarantee = ServletSecurity.TransportGuarantee.CONFIDENTIAL, rolesAllowed = {"a", "b"}))
                public class M extends jakarta.servlet.http.HttpServlet {}
                """).descriptor();
        assertEquals(ServletSecurity.TransportGuarantee.CONFIDENTIAL, d.servletSecurity().getTransportGuarantee());
        assertEquals(ServletSecurity.EmptyRoleSemantic.PERMIT, d.servletSecurity().getEmptyRoleSemantic());
        var post = d.servletSecurity().getHttpMethodConstraints().iterator().next();
        assertEquals(ServletSecurity.TransportGuarantee.CONFIDENTIAL, post.getTransportGuarantee());
        assertEquals(List.of("a", "b"), List.of(post.getRolesAllowed()));
    }

    @Test
    void webServletOnNonServletIsAnError() throws Exception {
        var r = compileFailing("a.X", """
                package a;
                @jakarta.servlet.annotation.WebServlet("/x") public class X {}
                """);
        assertTrue(r.messages().contains("a.X") && r.messages().contains("jakarta.servlet.Servlet"), r.messages());
    }

    @Test
    void webFilterOnNonFilterIsAnError() throws Exception {
        var r = compileFailing("a.X", """
                package a;
                @jakarta.servlet.annotation.WebFilter("/x") public class X {}
                """);
        assertTrue(r.messages().contains("jakarta.servlet.Filter"), r.messages());
    }

    @Test
    void valueAndUrlPatternsTogetherIsAnError() throws Exception {
        var r = compileFailing("a.Y", """
                package a;
                @jakarta.servlet.annotation.WebServlet(value = "/a", urlPatterns = "/b")
                public class Y extends jakarta.servlet.http.HttpServlet {}
                """);
        assertTrue(r.messages().contains("value") && r.messages().contains("urlPatterns"), r.messages());
    }

    @Test
    void servletWithoutPatternsIsAnError() throws Exception {
        var r = compileFailing("a.N", """
                package a;
                @jakarta.servlet.annotation.WebServlet
                public class N extends jakarta.servlet.http.HttpServlet {}
                """);
        assertTrue(r.messages().contains("urlPatterns"), r.messages());
    }

    @Test
    void filterWithoutPatternsNorServletNamesIsAnError() throws Exception {
        var r = compileFailing("a.N", """
                package a;
                @jakarta.servlet.annotation.WebFilter
                public class N implements jakarta.servlet.Filter {
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                         jakarta.servlet.FilterChain c) {}
                }
                """);
        assertTrue(r.messages().contains("servletNames"), r.messages());
    }

    @Test
    void webListenerWithoutListenerInterfaceIsAnError() throws Exception {
        compileFailing("a.Z", """
                package a;
                @jakarta.servlet.annotation.WebListener public class Z implements java.util.EventListener {}
                """);
    }
}
