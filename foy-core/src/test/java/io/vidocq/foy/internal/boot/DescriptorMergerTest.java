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
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
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
}
