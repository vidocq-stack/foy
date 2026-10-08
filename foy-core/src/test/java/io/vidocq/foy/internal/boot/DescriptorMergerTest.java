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
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.boot.DescriptorMerger.AnnotatedComponents;
import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DescriptorMergerTest {

    public static class Annotated extends HttpServlet {}
    public static class FromXml extends HttpServlet {}
    public static class F1 implements Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res,
                                       jakarta.servlet.FilterChain chain) {}
    }
    public static class L1 implements ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent e) {}
    }
    public static class L2 implements ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent e) {}
    }

    private static final ComponentFactory F = ComponentFactory.reflective(DescriptorMergerTest.class.getClassLoader());

    private static WebAppModel merge(String webXml, AnnotatedComponents ann) throws Exception {
        var d = WebXmlParser.parse(new ByteArrayInputStream(webXml.getBytes()));
        var b = WebAppModel.builder("/");
        DescriptorMerger.merge(d, ann, F, b);
        return b.build();
    }

    private static AnnotatedComponents annotatedServlet(String name, String pattern, Map<String, String> params) {
        return new AnnotatedComponents(List.of(new ServletDecl(name, Annotated.class, Annotated::new,
                List.of(pattern), params, Integer.MIN_VALUE, true)), List.of(), List.of(), List.of());
    }

    private static final String HEAD = "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"";

    @Test
    void metadataCompleteIgnoresAnnotations() throws Exception {
        var m = merge(HEAD + " metadata-complete=\"true\"></web-app>",
                annotatedServlet("a", "/a", Map.of()));
        assertTrue(m.servlets().isEmpty());
    }

    @Test
    void webXmlMappingReplacesAnnotationPatternsAndParamsMergeWithXmlWinning() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>s</servlet-name>
                    <servlet-class>%s</servlet-class>
                    <init-param><param-name>k</param-name><param-value>xml</param-value></init-param>
                  </servlet>
                  <servlet-mapping><servlet-name>s</servlet-name><url-pattern>/xml</url-pattern></servlet-mapping>
                </web-app>""".formatted(FromXml.class.getName()),
                annotatedServlet("s", "/ann", Map.of("k", "ann", "only", "ann")));
        var s = m.servlets().getFirst();
        assertEquals(FromXml.class, s.type());
        assertEquals(List.of("/xml"), s.urlPatterns());
        assertEquals(Map.of("k", "xml", "only", "ann"), s.initParams());
        assertInstanceOf(FromXml.class, s.factory().get());
    }

    @Test
    void annotationPatternsKeptWhenWebXmlHasNoMappingForTheName() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>s</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(FromXml.class.getName()),
                annotatedServlet("s", "/ann", Map.of()));
        assertEquals(List.of("/ann"), m.servlets().getFirst().urlPatterns());
    }

    @Test
    void distinctNamesAreBothKept() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>x</servlet-name><servlet-class>%s</servlet-class></servlet>
                  <servlet-mapping><servlet-name>x</servlet-name><url-pattern>/x</url-pattern></servlet-mapping>
                </web-app>""".formatted(FromXml.class.getName()),
                annotatedServlet("a", "/a", Map.of()));
        assertEquals(List.of("x", "a"), m.servlets().stream().map(ServletDecl::name).toList());
    }

    @Test
    void loadOnStartupAndAsyncComeFromWebXml() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>s</servlet-name><servlet-class>%s</servlet-class>
                    <load-on-startup>2</load-on-startup><async-supported>true</async-supported></servlet>
                </web-app>""".formatted(FromXml.class.getName()), AnnotatedComponents.none());
        var s = m.servlets().getFirst();
        assertEquals(2, s.loadOnStartup());
        assertTrue(s.asyncSupported());
    }

    @Test
    void webXmlAsyncFalseOverridesAnnotationTrue() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>a</servlet-name><servlet-class>%s</servlet-class>
                    <async-supported>false</async-supported></servlet>
                </web-app>""".formatted(Annotated.class.getName()), annotatedServlet("a", "/a", Map.of()));
        assertFalse(m.servlets().getFirst().asyncSupported());
    }

    @Test
    void absentAsyncInWebXmlKeepsAnnotationValue() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>a</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(Annotated.class.getName()), annotatedServlet("a", "/a", Map.of()));
        assertTrue(m.servlets().getFirst().asyncSupported());
    }

    @Test
    void absentLoadOnStartupInWebXmlKeepsAnnotationValue() throws Exception {
        var ann = new AnnotatedComponents(List.of(new ServletDecl("s", Annotated.class, Annotated::new,
                List.of("/a"), Map.of(), 5, false)), List.of(), List.of(), List.of());
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>s</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(FromXml.class.getName()), ann);
        assertEquals(5, m.servlets().getFirst().loadOnStartup());
    }

    @Test
    void filterMappingsFromWebXmlReplaceAnnotationOnesAndParamsMerge() throws Exception {
        var annFilter = new FilterDecl("f", F1.class, F1::new, Map.of("k", "ann", "only", "ann"), false);
        var ann = new AnnotatedComponents(List.of(), List.of(annFilter),
                List.of(new FilterMappingDecl("f", "/ann/*", null, Set.of(DispatcherType.REQUEST))), List.of());
        var m = merge(HEAD + """
                >
                  <filter><filter-name>f</filter-name><filter-class>%s</filter-class>
                    <init-param><param-name>k</param-name><param-value>xml</param-value></init-param>
                  </filter>
                  <filter-mapping><filter-name>f</filter-name><servlet-name>s</servlet-name>
                    <dispatcher>FORWARD</dispatcher></filter-mapping>
                </web-app>""".formatted(F1.class.getName()), ann);
        assertEquals(Map.of("k", "xml", "only", "ann"), m.filters().getFirst().initParams());
        assertEquals(1, m.filterMappings().size());
        var fm = m.filterMappings().getFirst();
        assertNull(fm.urlPattern());
        assertEquals("s", fm.servletName());
        assertEquals(Set.of(DispatcherType.FORWARD), fm.dispatcherTypes());
    }

    @Test
    void annotationFilterMappingsKeptWhenWebXmlHasNone() throws Exception {
        var annFilter = new FilterDecl("f", F1.class, F1::new, Map.of(), true);
        var ann = new AnnotatedComponents(List.of(), List.of(annFilter),
                List.of(new FilterMappingDecl("f", "/ann/*", null, Set.of(DispatcherType.REQUEST))), List.of());
        var m = merge(HEAD + "></web-app>", ann);
        assertEquals("/ann/*", m.filterMappings().getFirst().urlPattern());
    }

    @Test
    void listenersAreUnionWebXmlFirstDeduplicatedByClass() throws Exception {
        var ann = new AnnotatedComponents(List.of(), List.of(), List.of(),
                List.of(new ListenerDecl(L2.class, L2::new), new ListenerDecl(L1.class, L1::new)));
        var m = merge(HEAD + """
                >
                  <listener><listener-class>%s</listener-class></listener>
                </web-app>""".formatted(L1.class.getName()), ann);
        assertEquals(List.of(L1.class, L2.class), m.listeners().stream().map(ListenerDecl::type).toList());
        assertInstanceOf(L1.class, m.listeners().getFirst().factory().get());
    }

    @WebServlet("/ann")
    @ServletSecurity(@HttpConstraint(rolesAllowed = "admin"))
    public static class Unnamed extends HttpServlet {}

    /** The annotation-derived declaration of {@code type}, as discovery builds it from the registry. */
    private static AnnotatedComponents discovered(Class<? extends HttpServlet> type) {
        var d = WebComponentRegistry.forClassLoader(DescriptorMergerTest.class.getClassLoader())
                .lookup(type).descriptor();
        return new AnnotatedComponents(List.of(new ServletDecl(d.name(), type, () -> new Unnamed(),
                d.urlPatterns(), d.initParams(), d.loadOnStartup(), d.asyncSupported(), d.servletSecurity())),
                List.of(), List.of(), List.of());
    }

    @Test
    void webXmlServletNamedByTheBinaryNameMergesWithTheUnnamedAnnotation() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>%1$s</servlet-name><servlet-class>%1$s</servlet-class></servlet>
                </web-app>""".formatted(Unnamed.class.getName()), discovered(Unnamed.class));
        assertEquals(List.of(Unnamed.class.getName()), m.servlets().stream().map(ServletDecl::name).toList(),
                "default @WebServlet name is the binary class name (§8.1.1), so §8.2.3 merges by name");
        var s = m.servlets().getFirst();
        assertEquals(List.of("/ann"), s.urlPatterns());
        assertEquals(Set.of("admin"), Set.of(s.servletSecurity().getRolesAllowed()));
    }

    @Test
    void webXmlDeclaredServletTakesServletSecurityFromItsClass() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>x</servlet-name><servlet-class>%s</servlet-class></servlet>
                  <servlet-mapping><servlet-name>x</servlet-name><url-pattern>/x</url-pattern></servlet-mapping>
                </web-app>""".formatted(Unnamed.class.getName()), AnnotatedComponents.none());
        assertEquals(Set.of("admin"), Set.of(m.servlets().getFirst().servletSecurity().getRolesAllowed()));
    }

    @Test
    void metadataCompleteIgnoresServletSecurityToo() throws Exception {
        var m = merge(HEAD + """
                 metadata-complete="true">
                  <servlet><servlet-name>x</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(Unnamed.class.getName()), AnnotatedComponents.none());
        assertNull(m.servlets().getFirst().servletSecurity());
    }

    @Test
    void componentsOfTheWrongTypeAreSkippedWithOneWarningEach() throws Exception {
        WebAppModel m;
        try (var log = io.vidocq.foy.internal.LogCapture.of(DescriptorMerger.class.getName())) {
            m = merge(HEAD + """
                    >
                      <servlet><servlet-name>bad</servlet-name><servlet-class>java.lang.Object</servlet-class></servlet>
                      <servlet><servlet-name>good</servlet-name><servlet-class>%s</servlet-class></servlet>
                      <servlet-mapping><servlet-name>bad</servlet-name><url-pattern>/bad</url-pattern></servlet-mapping>
                      <servlet-mapping><servlet-name>good</servlet-name><url-pattern>/good</url-pattern></servlet-mapping>
                      <filter><filter-name>badFilter</filter-name><filter-class>java.lang.Object</filter-class></filter>
                      <filter><filter-name>f1</filter-name><filter-class>%s</filter-class></filter>
                      <filter-mapping><filter-name>badFilter</filter-name><url-pattern>/*</url-pattern></filter-mapping>
                      <filter-mapping><filter-name>f1</filter-name><url-pattern>/*</url-pattern></filter-mapping>
                      <listener><listener-class>java.lang.Object</listener-class></listener>
                      <listener><listener-class>%s</listener-class></listener>
                    </web-app>""".formatted(FromXml.class.getName(), F1.class.getName(), L1.class.getName()),
                    AnnotatedComponents.none());
            List<String> warnings = log.warnings();
            assertEquals(3, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("'bad'") && warnings.get(0).contains("java.lang.Object"),
                    warnings.get(0));
            assertTrue(warnings.get(1).contains("'badFilter'"), warnings.get(1));
            assertTrue(warnings.get(2).contains("java.lang.Object"), warnings.get(2));
        }
        assertEquals(List.of("good"), m.servlets().stream().map(ServletDecl::name).toList());
        assertEquals(List.of("f1"), m.filters().stream().map(FilterDecl::name).toList());
        assertEquals(List.of("f1"), m.filterMappings().stream().map(FilterMappingDecl::filterName).toList());
        assertEquals(List.of(L1.class), m.listeners().stream().map(ListenerDecl::type).toList());
    }

    @Test
    void missingClassStillFailsTheDeployment() {
        assertThrows(jakarta.servlet.ServletException.class, () -> merge(HEAD + """
                >
                  <servlet><servlet-name>x</servlet-name><servlet-class>no.such.Servlet</servlet-class></servlet>
                </web-app>""", AnnotatedComponents.none()));
    }

    /** Spec-forbidden: 'value' and 'urlPatterns' together. */
    @WebServlet(value = "/a", urlPatterns = "/b")
    public static class BothPatterns extends HttpServlet {}

    @Test
    void annotationMisuseOfAWebXmlServletIsAServletException() {
        var e = assertThrows(jakarta.servlet.ServletException.class, () -> merge(HEAD + """
                >
                  <servlet><servlet-name>x</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(BothPatterns.class.getName()), AnnotatedComponents.none()));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
        assertTrue(e.getMessage().contains(BothPatterns.class.getName()), e.getMessage());
    }

    @WebServlet("/pc")
    public static class PrivateCtor extends HttpServlet { private PrivateCtor() {} }

    @Test
    void instantiationFailureNamesTheClassOnce() throws Exception {
        var d = WebXmlParser.parse(new ByteArrayInputStream((HEAD + """
                >
                  <servlet><servlet-name>x</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(PrivateCtor.class.getName())).getBytes()));
        var b = WebAppModel.builder("/");
        DescriptorMerger.merge(d, AnnotatedComponents.none(),
                io.vidocq.foy.internal.gen.RegistryComponentFactory.forClassLoader(getClass().getClassLoader()), b);
        var supplier = b.build().servlets().getFirst().factory();
        var e = assertThrows(IllegalStateException.class, supplier::get);
        String name = PrivateCtor.class.getName();
        assertEquals("cannot instantiate " + name + ": " + name + " has no non-private no-arg constructor",
                e.getMessage());
    }
}
