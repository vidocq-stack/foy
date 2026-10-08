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

import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.*;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileDescriptorReaderTest {

    @WebServlet(name = "cf", urlPatterns = {"/a", "/b"}, loadOnStartup = 4, asyncSupported = true,
            initParams = {@WebInitParam(name = "x", value = "1"), @WebInitParam(name = "y", value = "2")})
    @MultipartConfig(maxFileSize = 7)
    public static class Annotated extends HttpServlet {}

    @WebFilter(value = "/f", dispatcherTypes = DispatcherType.ASYNC)
    public static class AFilter implements jakarta.servlet.Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                       jakarta.servlet.FilterChain c) {}
    }

    public static class Plain extends HttpServlet {}

    @Test
    void decodesWebServlet() {
        var d = ClassFileDescriptorReader.read(Annotated.class).orElseThrow();
        assertEquals(Kind.SERVLET, d.kind());
        assertEquals("cf", d.name());
        assertEquals(List.of("/a", "/b"), d.urlPatterns());
        assertEquals(Map.of("x", "1", "y", "2"), d.initParams());
        assertEquals(4, d.loadOnStartup());
        assertTrue(d.asyncSupported());
        assertEquals(7, d.multipartConfig().getMaxFileSize());
    }

    @Test
    void decodesWebFilterWithDefaults() {
        var d = ClassFileDescriptorReader.read(AFilter.class).orElseThrow();
        assertEquals(Kind.FILTER, d.kind());
        assertEquals(AFilter.class.getName(), d.name());
        assertEquals(List.of("/f"), d.urlPatterns());
        assertEquals(Set.of(DispatcherType.ASYNC), d.dispatcherTypes());
    }

    @Test
    void unannotatedServletIsPlain() {
        assertEquals(Kind.PLAIN, ClassFileDescriptorReader.read(Plain.class).orElseThrow().kind());
    }

    @Test
    void hiddenOrLambdaClassesYieldEmpty() {
        Runnable r = () -> {};
        assertTrue(ClassFileDescriptorReader.read(r.getClass()).isEmpty());
    }

    // ---- beyond the brief (Ruling B): every annotation the processor handles ----

    public static class SomeNested {}

    @HandlesTypes({Runnable.class, SomeNested.class})
    public static class Initializer implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> c, ServletContext ctx) {}
    }

    @WebListener
    public static class Listener implements ServletContextListener {}

    @WebServlet("/s")
    @ServletSecurity(value = @HttpConstraint(EmptyRoleSemantic.DENY),
            httpMethodConstraints = @HttpMethodConstraint(value = "POST", rolesAllowed = {"admin", "ops"},
                    transportGuarantee = TransportGuarantee.CONFIDENTIAL))
    @DeclareRoles({"admin", "ops"})
    @RunAs("admin")
    public static class Secured extends HttpServlet {}

    @WebServlet(urlPatterns = "/d", loadOnStartup = -5)
    public static class Defaults extends HttpServlet {}

    @WebFilter(servletNames = "cf", dispatcherTypes = {DispatcherType.FORWARD, DispatcherType.FORWARD})
    public static class NamedFilter implements jakarta.servlet.Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                       jakarta.servlet.FilterChain c) {}
    }

    @WebFilter("/g")
    public static class DefaultDispatcherFilter implements jakarta.servlet.Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                       jakarta.servlet.FilterChain c) {}
    }

    @MultipartConfig
    public static class DefaultMultipart extends HttpServlet {}

    public abstract static class AbstractServlet extends HttpServlet {}

    public static class NotAComponent {}

    @WebServlet(value = "/a", urlPatterns = "/b")
    public static class BothPatterns extends HttpServlet {}

    @Test
    void initializerHandlesTypesUseBinaryNames() {
        var d = ClassFileDescriptorReader.read(Initializer.class).orElseThrow();
        assertEquals(Kind.INITIALIZER, d.kind());
        assertNull(d.name());
        assertEquals(List.of("java.lang.Runnable", SomeNested.class.getName()), d.handlesTypes());
    }

    @Test
    void webListenerIsListener() {
        var d = ClassFileDescriptorReader.read(Listener.class).orElseThrow();
        assertEquals(Kind.LISTENER, d.kind());
        assertNull(d.name());
    }

    @Test
    void servletSecurityRolesAndRunAsRoundTrip() {
        var d = ClassFileDescriptorReader.read(Secured.class).orElseThrow();
        assertEquals(List.of("/s"), d.urlPatterns());
        var s = d.servletSecurity();
        assertEquals(EmptyRoleSemantic.DENY, s.getEmptyRoleSemantic());
        assertEquals(TransportGuarantee.NONE, s.getTransportGuarantee());
        assertEquals(0, s.getRolesAllowed().length);
        var methods = List.copyOf(s.getHttpMethodConstraints());
        assertEquals(1, methods.size());
        var post = methods.getFirst();
        assertEquals("POST", post.getMethodName());
        assertEquals(EmptyRoleSemantic.PERMIT, post.getEmptyRoleSemantic());
        assertEquals(TransportGuarantee.CONFIDENTIAL, post.getTransportGuarantee());
        assertArrayEquals(new String[]{"admin", "ops"}, post.getRolesAllowed());
        assertEquals(List.of("admin", "ops"), d.declaredRoles());
        assertEquals("admin", d.runAs());
    }

    @Test
    void servletDefaultsMatchTheProcessor() {
        var d = ClassFileDescriptorReader.read(Defaults.class).orElseThrow();
        assertEquals("io.vidocq.foy.internal.gen.ClassFileDescriptorReaderTest$Defaults", d.name());
        assertEquals(Integer.MIN_VALUE, d.loadOnStartup());
        assertFalse(d.asyncSupported());
        assertEquals(Map.of(), d.initParams());
        assertEquals(Set.of(), d.dispatcherTypes());
        assertNull(d.multipartConfig());
        assertNull(d.servletSecurity());
        assertEquals(List.of(), d.declaredRoles());
        assertNull(d.runAs());
    }

    @Test
    void filterDispatcherTypesAreDeduplicatedAndDefaultToRequest() {
        var named = ClassFileDescriptorReader.read(NamedFilter.class).orElseThrow();
        assertEquals(List.of("cf"), named.servletNames());
        assertEquals(List.of(), named.urlPatterns());
        assertEquals(Set.of(DispatcherType.FORWARD), named.dispatcherTypes());
        assertEquals(Set.of(DispatcherType.REQUEST),
                ClassFileDescriptorReader.read(DefaultDispatcherFilter.class).orElseThrow().dispatcherTypes());
    }

    @Test
    void multipartDefaults() {
        var m = ClassFileDescriptorReader.read(DefaultMultipart.class).orElseThrow().multipartConfig();
        assertEquals("", m.getLocation());
        assertEquals(-1L, m.getMaxFileSize());
        assertEquals(-1L, m.getMaxRequestSize());
        assertEquals(0, m.getFileSizeThreshold());
    }

    @Test
    void nonComponentsYieldEmpty() {
        assertTrue(ClassFileDescriptorReader.read(AbstractServlet.class).isEmpty());
        assertTrue(ClassFileDescriptorReader.read(NotAComponent.class).isEmpty());
        assertTrue(ClassFileDescriptorReader.read(String[].class).isEmpty());
        assertTrue(ClassFileDescriptorReader.read(int.class).isEmpty());
    }

    @Test
    void malformedBytesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ClassFileDescriptorReader.read(new byte[]{1, 2, 3}, Plain.class));
    }

    @Test
    void bothValueAndUrlPatternsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClassFileDescriptorReader.read(BothPatterns.class));
    }
}
