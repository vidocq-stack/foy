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
import io.vidocq.foy.internal.webxml.Fragment;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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

    // ---- web fragments (Servlet 6.1 §8.2.3) ----------------------------------------------------

    /** Compiles a servlet, a filter and a listener into {@code dir/jar.jar}; returns the jar. */
    private static Path compileComponentJar(Path dir) throws Exception {
        Path src = dir.resolve("src/jarpkg");
        Files.createDirectories(src);
        Files.writeString(src.resolve("JarServlet.java"),
                "package jarpkg; public class JarServlet extends jakarta.servlet.http.HttpServlet {}");
        Files.writeString(src.resolve("JarFilter.java"), """
                package jarpkg;
                public class JarFilter implements jakarta.servlet.Filter {
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                         jakarta.servlet.FilterChain c) {}
                }""");
        Files.writeString(src.resolve("JarListener.java"),
                "package jarpkg; public class JarListener implements jakarta.servlet.ServletContextListener {}");
        Path classes = dir.resolve("classes");
        String servletApi = Path.of(HttpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        int rc = java.util.spi.ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-cp", servletApi, "-d", classes.toString(),
                src.resolve("JarServlet.java").toString(), src.resolve("JarFilter.java").toString(),
                src.resolve("JarListener.java").toString());
        assertEquals(0, rc, "test classes must compile");
        Path jar = dir.resolve("jar.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static <T> Supplier<T> never() {
        return () -> { throw new AssertionError("not instantiated by the merge"); };
    }

    @Test
    void fragmentMetadataCompleteExcludesOnlyItsJarAnnotations(@TempDir Path dir) throws Exception {
        Path jar = compileComponentJar(dir);
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            Class<? extends Servlet> jarServlet = loader.loadClass("jarpkg.JarServlet").asSubclass(Servlet.class);
            Class<? extends Filter> jarFilter = loader.loadClass("jarpkg.JarFilter").asSubclass(Filter.class);
            Class<? extends EventListener> jarListener =
                    loader.loadClass("jarpkg.JarListener").asSubclass(EventListener.class);
            var ann = new AnnotatedComponents(
                    List.of(new ServletDecl("jar", jarServlet, never(), List.of("/jar"), Map.of(),
                                    Integer.MIN_VALUE, false),
                            new ServletDecl("ann", Annotated.class, Annotated::new, List.of("/ann"), Map.of(),
                                    Integer.MIN_VALUE, false)),
                    List.of(new FilterDecl("jarFilter", jarFilter, never(), Map.of(), false),
                            new FilterDecl("f1", F1.class, F1::new, Map.of(), false)),
                    List.of(new FilterMappingDecl("jarFilter", "/*", null, null),
                            new FilterMappingDecl("f1", "/*", null, null)),
                    List.of(new ListenerDecl(jarListener, never()), new ListenerDecl(L1.class, L1::new)));
            var fragment = WebXmlParser.parseFragment(new ByteArrayInputStream(("""
                    <web-fragment xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1" metadata-complete="true">
                      <servlet><servlet-name>fx</servlet-name><servlet-class>%s</servlet-class></servlet>
                      <servlet-mapping><servlet-name>fx</servlet-name><url-pattern>/fx</url-pattern></servlet-mapping>
                    </web-fragment>""".formatted(FromXml.class.getName())).getBytes()));
            // The fragment jar is spelled as the discovery sees it, the classes' code source as file:.
            var fragments = List.of(new Fragment("F1", URI.create("jar:" + jar.toUri() + "!/").toURL(), fragment));
            var b = WebAppModel.builder("/");
            DescriptorMerger.merge(WebXmlParser.parse(new ByteArrayInputStream((HEAD + "></web-app>").getBytes())),
                    fragments, ann, F, b);
            var m = b.build();
            assertEquals(List.of("fx", "ann"), m.servlets().stream().map(ServletDecl::name).toList());
            assertEquals(List.of("/fx"), m.servlets().getFirst().urlPatterns());
            assertEquals(List.of("f1"), m.filters().stream().map(FilterDecl::name).toList());
            assertEquals(List.of("f1"), m.filterMappings().stream().map(FilterMappingDecl::filterName).toList());
            assertEquals(List.of(L1.class), m.listeners().stream().map(ListenerDecl::type).toList());
        }
    }

    @Test
    void excludingSourcesKeepsComponentsWithoutAMatchingCodeSource() throws Exception {
        var ann = annotatedServlet("a", "/a", Map.of());
        var none = ann.excludingSources(Set.of(URI.create("file:/elsewhere/x.jar").toURL()));
        assertEquals(ann, none);
        URL testClasses = Annotated.class.getProtectionDomain().getCodeSource().getLocation();
        assertTrue(ann.excludingSources(Set.of(testClasses)).servlets().isEmpty());
    }

    @Test
    void excludingSourcesWithAMapperComparesTheMappedSource() throws Exception {
        URL libJar = new URI("file", null, "/app.war/WEB-INF/lib/a.jar", null).toURL();
        URL classes = new URI("file", null, "/app.war/WEB-INF/classes/", null).toURL();
        var ann = new AnnotatedComponents(
                List.of(new ServletDecl("a", Annotated.class, Annotated::new, List.of("/a"), Map.of(),
                        Integer.MIN_VALUE, true),
                        new ServletDecl("x", FromXml.class, FromXml::new, List.of("/x"), Map.of(),
                        Integer.MIN_VALUE, true)),
                List.of(new FilterDecl("f1", F1.class, F1::new, Map.of(), false)),
                List.of(new FilterMappingDecl("f1", "/*", null, Set.of())),
                List.of(new ListenerDecl(L1.class, L1::new)));
        Map<Class<?>, URL> sources = Map.of(Annotated.class, libJar, F1.class, libJar, FromXml.class, classes);
        // The jar is spelled as discovery would (jar: wrapper, trailing slash): keys are normalised.
        var kept = ann.excludingSources(Set.of(URI.create("jar:" + libJar + "!/").toURL()), sources::get);
        assertEquals(List.of("x"), kept.servlets().stream().map(ServletDecl::name).toList());
        assertTrue(kept.filters().isEmpty());
        assertTrue(kept.filterMappings().isEmpty());
        // L1 maps to no source: never dropped, even though its code source is the test classes.
        assertEquals(List.of(L1.class), kept.listeners().stream().map(ListenerDecl::type).toList());
        URL testClasses = Annotated.class.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(ann, ann.excludingSources(Set.of(testClasses), sources::get));
    }

    @Test
    void metadataCompleteFragmentDropsTheAnnotationsOfTheClassesMappedToItsJar() throws Exception {
        URL libJar = new URI("file", null, "/app.war/WEB-INF/lib/a.jar", null).toURL();
        var fragment = WebXmlParser.parseFragment(new ByteArrayInputStream(("""
                <web-fragment xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1"
                              metadata-complete="true"/>""").getBytes()));
        var b = WebAppModel.builder("/");
        DescriptorMerger.mergeMerged(WebAppDescriptor.empty(), List.of(new Fragment("a.jar", libJar, fragment)),
                annotatedServlet("a", "/a", Map.of()), type -> libJar, F, b);
        assertTrue(b.build().servlets().isEmpty());
    }

    @Test
    void fourArgumentMergeIsTheMergeWithoutFragments() throws Exception {
        var d = WebXmlParser.parse(new ByteArrayInputStream((HEAD + """
                >
                  <servlet><servlet-name>x</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(FromXml.class.getName())).getBytes()));
        var b = WebAppModel.builder("/");
        DescriptorMerger.merge(d, List.of(), annotatedServlet("a", "/a", Map.of()), F, b);
        assertEquals(List.of("x", "a"), b.build().servlets().stream().map(ServletDecl::name).toList());
    }

    @Test
    void fragmentDescriptorsAreMergedWithWebXml() throws Exception {
        var fragment = WebXmlParser.parseFragment(new ByteArrayInputStream(("""
                <web-fragment xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <context-param><param-name>k</param-name><param-value>frag</param-value></context-param>
                  <context-param><param-name>only</param-name><param-value>frag</param-value></context-param>
                  <listener><listener-class>%s</listener-class></listener>
                </web-fragment>""".formatted(L2.class.getName())).getBytes()));
        var d = WebXmlParser.parse(new ByteArrayInputStream((HEAD + """
                >
                  <context-param><param-name>k</param-name><param-value>xml</param-value></context-param>
                </web-app>""").getBytes()));
        var b = WebAppModel.builder("/");
        DescriptorMerger.merge(d, List.of(new Fragment("F", URI.create("file:/f.jar").toURL(), fragment)),
                AnnotatedComponents.none(), F, b);
        var m = b.build();
        assertEquals(Map.of("k", "xml", "only", "frag"), m.contextParams());
        assertEquals(List.of(L2.class), m.listeners().stream().map(ListenerDecl::type).toList());
    }

    // ---- Phase 1 follow-ups -------------------------------------------------------------------

    @Test
    void errorPagesAndContextParamsAreCopiedFromWebXml() throws Exception {
        var m = merge(HEAD + """
                >
                  <context-param><param-name>a</param-name><param-value>1</param-value></context-param>
                  <context-param><param-name>b</param-name><param-value></param-value></context-param>
                  <error-page><error-code>404</error-code><location>/nf</location></error-page>
                  <error-page><exception-type>java.lang.IllegalStateException</exception-type>
                    <location>/ise</location></error-page>
                </web-app>""", AnnotatedComponents.none());
        assertEquals(Map.of("a", "1", "b", ""), m.contextParams());
        assertEquals(2, m.errorPages().size());
        assertEquals(java.util.Optional.of("/nf"), m.errorPages().findByStatus(404));
        assertEquals(java.util.Optional.of("/ise"), m.errorPages().findByException(new IllegalStateException()));
        assertEquals(java.util.Optional.empty(), m.errorPages().findByStatus(500));
    }

    @Test
    void errorPageExceptionTypeThatIsNotAThrowableFails() {
        var e = assertThrows(jakarta.servlet.ServletException.class, () -> merge(HEAD + """
                >
                  <error-page><exception-type>java.lang.String</exception-type><location>/x</location></error-page>
                </web-app>""", AnnotatedComponents.none()));
        assertTrue(e.getMessage().contains("java.lang.String"), e.getMessage());
    }

    @Test
    void filterMappingDeclNeedsExactlyOneTarget() {
        assertThrows(IllegalArgumentException.class, () -> new FilterMappingDecl("f", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new FilterMappingDecl("f", "/x", "s", null));
        assertThrows(NullPointerException.class, () -> new FilterMappingDecl(null, "/x", null, null));
        assertEquals(Set.of(DispatcherType.REQUEST), new FilterMappingDecl("f", "/x", null, null).dispatcherTypes());
        assertEquals(Set.of(DispatcherType.REQUEST), new FilterMappingDecl("f", null, "s", Set.of()).dispatcherTypes());
        var types = java.util.EnumSet.of(DispatcherType.FORWARD);
        var decl = new FilterMappingDecl("f", "/x", null, types);
        types.add(DispatcherType.ERROR);
        assertEquals(Set.of(DispatcherType.FORWARD), decl.dispatcherTypes(), "defensive copy");
        assertThrows(UnsupportedOperationException.class, () -> decl.dispatcherTypes().add(DispatcherType.ERROR));
    }

    static final List<String> FILTER_EVENTS = new CopyOnWriteArrayList<>();

    public static class Recording implements Filter {
        final String id;
        Recording(String id) { this.id = id; }
        @Override public void init(jakarta.servlet.FilterConfig c) { FILTER_EVENTS.add("init:" + id); }
        @Override public void destroy() { FILTER_EVENTS.add("destroy:" + id); }
        @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res,
                                       jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            FILTER_EVENTS.add("filter:" + id);
            chain.doFilter(req, res);
        }
    }

    public static class Ok extends HttpServlet {
        @Override protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                                       jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
            r.getWriter().write("ok");
        }
    }

    @Test
    void unmappedAnnotatedAndDynamicFiltersBehaveTheSame() throws Exception {
        FILTER_EVENTS.clear();
        var ann = new AnnotatedComponents(
                List.of(new ServletDecl("ok", Ok.class, Ok::new, List.of("/x"), Map.of(), Integer.MIN_VALUE, false)),
                List.of(new FilterDecl("ann", Recording.class, () -> new Recording("ann"), Map.of(), false)),
                List.of(), List.of());
        var b = WebAppModel.builder("/");
        DescriptorMerger.merge(WebXmlParser.parse(new ByteArrayInputStream((HEAD + "></web-app>").getBytes())),
                ann, F, b);
        b.initializer((classes, ctx) -> ctx.addFilter("dyn", new Recording("dyn")));
        try (var deployment = WebAppDeployer.deploy(b.build(), DeployOptions.defaults(getClass().getClassLoader()))) {
            var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
            try {
                var res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + started.port() + "/x")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals("ok", res.body());
            } finally {
                started.server().stop();
            }
            var ctx = deployment.servletContext();
            assertNotNull(ctx.getFilterRegistration("ann"), "static unmapped filter is registered");
            assertNotNull(ctx.getFilterRegistration("dyn"), "dynamic unmapped filter is registered");
        }
        assertEquals(Set.of("init:ann", "init:dyn", "destroy:ann", "destroy:dyn"), Set.copyOf(FILTER_EVENTS),
                "both are initialised and destroyed, neither is applied: " + FILTER_EVENTS);
    }
}
