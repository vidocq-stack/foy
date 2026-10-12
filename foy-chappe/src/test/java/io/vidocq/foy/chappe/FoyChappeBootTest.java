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

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.http.RequestLimits;
import io.vidocq.foy.spi.jsp.JspEngine;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Proxy;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class FoyChappeBootTest {

    public static final AtomicInteger INITS = new AtomicInteger();
    public static final AtomicInteger DESTROYS = new AtomicInteger();

    public static class Hello extends HttpServlet {
        @Override public void init() { INITS.incrementAndGet(); }
        @Override public void destroy() { DESTROYS.incrementAndGet(); }
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("hello " + getInitParameter("who"));
        }
    }

    /** No no-arg constructor: the reflective factory cannot instantiate it. */
    public static class NotInstantiable extends HttpServlet {
        public NotInstantiable(String required) {}
    }

    @Test
    void webXmlOnlyApplicationIsInitialisedServedAndDestroyed() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>h</servlet-name><servlet-class>%s</servlet-class>
                <init-param><param-name>who</param-name><param-value>foy</param-value></init-param>
                <load-on-startup>1</load-on-startup></servlet>
              <servlet-mapping><servlet-name>h</servlet-name><url-pattern>/hello</url-pattern></servlet-mapping>
            </web-app>""".formatted(Hello.class.getName());

        var mounted = FoyChappeBoot.builder().contextPath("/")
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes()))
                .build().orElseThrow();
        assertEquals(1, INITS.get(), "load-on-startup servlet initialised at deploy time");

        Server server = Server.builder().host("127.0.0.1").port(0).handler(mounted.handler()).build();
        server.start();
        int port = server.port();
        try {
            var resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("hello foy", resp.body());
        } finally {
            server.stop();
            mounted.close();
        }
        assertEquals(1, DESTROYS.get());
    }

    private static FoyChappeBoot.Builder plainApp(String defaultContextPath) {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">%s
              <servlet><servlet-name>p</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>p</servlet-name><url-pattern>/p</url-pattern></servlet-mapping>
            </web-app>""".formatted(defaultContextPath == null ? ""
                : "<default-context-path>" + defaultContextPath + "</default-context-path>", Plain.class.getName());
        return FoyChappeBoot.builder().classLoader(FoyChappeBootTest.class.getClassLoader())
                .discoverPluggability(false).webXml(new ByteArrayInputStream(xml.getBytes()));
    }

    @Test
    void mountedServletContextIsExposedAsTheJakartaType() throws Exception {
        assertEquals(jakarta.servlet.ServletContext.class,
                FoyChappeBoot.Mounted.class.getMethod("servletContext").getReturnType());
        try (var mounted = plainApp(null).contextPath("/").build().orElseThrow()) {
            jakarta.servlet.ServletContext ctx = mounted.servletContext();
            assertEquals(30 * 60, ctx.getSessionTimeout() * 60);
        }
    }

    @Test
    void usersDeclaredOnTheBuilderReachTheContext() throws Exception {
        try (var mounted = plainApp(null).contextPath("/").user("alice", "a-secret".toCharArray(), "admin")
                .build().orElseThrow()) {
            var store = mounted.deployment().servletContext().identityStore();
            assertEquals(Set.of("admin"), store.validate("alice", "a-secret".toCharArray()).orElseThrow().roles());
            assertTrue(store.validate("alice", "wrong".toCharArray()).isEmpty());
        }
    }

    @Test
    void identityStoreAndUsersAreExclusive() {
        var b = plainApp(null).contextPath("/").identityStore(io.vidocq.foy.spi.security.IdentityStore.none())
                .user("alice", "a".toCharArray());
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void anInvalidConfidentialPortIsRejectedAtTheCallSite() {
        var b = plainApp(null);
        assertThrows(IllegalArgumentException.class, () -> b.confidentialPort(0));
        assertThrows(IllegalArgumentException.class, () -> b.confidentialPort(65_536));
        b.confidentialPort(-1).confidentialPort(8443);
    }

    @Test
    void requestLimitsAreConfigurableAndReachTheContext() throws Exception {
        try (var mounted = plainApp(null).contextPath("/").build().orElseThrow()) {
            assertEquals(RequestLimits.DEFAULTS, mounted.deployment().servletContext().requestLimits());
        }
        try (var mounted = plainApp(null).contextPath("/").maxChunkedRequestBodySize(1024).maxFormBodySize(512)
                .maxMultipartParts(5).maxMultipartPartHeaderSize(256).build().orElseThrow()) {
            assertEquals(new RequestLimits(1024, 512, 5, 256), mounted.deployment().servletContext().requestLimits());
        }
    }

    @Test
    void invalidRequestLimitsAreRejectedAtTheCallSite() {
        var b = plainApp(null);
        assertThrows(IllegalArgumentException.class, () -> b.maxChunkedRequestBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> b.maxFormBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> b.maxMultipartParts(0));
        assertThrows(IllegalArgumentException.class, () -> b.maxMultipartPartHeaderSize(0));
        assertDoesNotThrow(() -> b.maxChunkedRequestBodySize(-1).maxFormBodySize(-1).maxMultipartParts(-1));
    }

    @Test
    void virtualServerNameIsConfigurableAndDefaultsToVidocq() throws Exception {
        try (var mounted = plainApp(null).contextPath("/").build().orElseThrow()) {
            assertEquals("vidocq", mounted.servletContext().getVirtualServerName());
        }
        try (var mounted = plainApp(null).contextPath("/").virtualServerName("example.org").build().orElseThrow()) {
            assertEquals("example.org", mounted.servletContext().getVirtualServerName());
        }
    }

    @Test
    void invalidContextPathIsADeploymentFailure() {
        for (String bad : new String[] {"app", "/app/", "/a\\b", "/a;b", "/a%20b", "/../x"}) {
            var ex = assertThrows(ServletException.class, () -> plainApp(null).contextPath(bad).build(), bad);
            assertTrue(ex.getMessage().contains("context path"), ex.getMessage());
        }
        var ex = assertThrows(ServletException.class, () -> plainApp("/dcp/").build());
        assertTrue(ex.getMessage().contains("context path"), ex.getMessage());
    }

    @Test
    void emptyApplicationYieldsEmpty() throws Exception {
        assertTrue(FoyChappeBoot.builder().classLoader(new ClassLoader(null) {}).build().isEmpty());
    }

    /** A test engine whose pages answer {@code page}; {@code hasPages} and {@code deploy} are observable. */
    private static JspEngine pageEngine(boolean hasPages, List<ModuleLayer> hasPagesLayers,
                                        List<ModuleLayer> deployLayers, AtomicInteger openings) {
        return new JspEngine() {
            @Override
            public Application deploy(jakarta.servlet.ServletContext context, ModuleLayer layer) {
                deployLayers.add(layer);
                openings.incrementAndGet();
                return new Application() {
                    @Override public Servlet servlet() { return new PageServlet(); }
                    @Override public Servlet servletFor(String jspFile) { return new PageServlet(); }
                    @Override public void close() {}
                };
            }

            @Override
            public boolean hasPages(ModuleLayer layer) {
                hasPagesLayers.add(layer);
                return hasPages;
            }
        };
    }

    public static class PageServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("page");
        }
    }

    @Test
    void aJspOnlyApplicationIsDeployedWhenTheEngineHasPages() throws Exception {
        var engine = pageEngine(true, new ArrayList<>(), new ArrayList<>(), new AtomicInteger());
        try (var mounted = FoyChappeBoot.builder().classLoader(new ClassLoader(null) {})
                .jspEngineLookup(layer -> Optional.of(engine)).contextPath("/").build().orElseThrow()) {
            assertEquals("page", getThroughRouter(mounted, false, "/x.jsp").body());
        }
    }

    @Test
    void anEngineWithoutPagesDeploysNothingAlone() throws Exception {
        var engine = pageEngine(false, new ArrayList<>(), new ArrayList<>(), new AtomicInteger());
        assertTrue(FoyChappeBoot.builder().classLoader(new ClassLoader(null) {})
                .jspEngineLookup(layer -> Optional.of(engine)).build().isEmpty());
    }

    @Test
    void anEngineWithoutPagesStillServesAnOtherwiseNonEmptyApp() throws Exception {
        var engine = pageEngine(false, new ArrayList<>(), new ArrayList<>(), new AtomicInteger());
        try (var mounted = plainApp(null).contextPath("/")
                .jspEngineLookup(layer -> Optional.of(engine)).build().orElseThrow()) {
            assertEquals("page", getThroughRouter(mounted, false, "/x.jsp").body());
        }
    }

    @Test
    void aLayerWithoutAnEngineStaysEmpty() throws Exception {
        assertTrue(FoyChappeBoot.builder().classLoader(new ClassLoader(null) {})
                .jspEngineLookup(layer -> Optional.empty()).build().isEmpty());
    }

    @Test
    void theLayerIsHandedToTheLookupAndToTheEngine() throws Exception {
        var lookups = new ArrayList<ModuleLayer>();
        var asked = new ArrayList<ModuleLayer>();
        var opened = new ArrayList<ModuleLayer>();
        var engine = pageEngine(true, asked, opened, new AtomicInteger());
        Function<ModuleLayer, Optional<JspEngine>> lookup = layer -> {
            lookups.add(layer);
            return Optional.of(engine);
        };
        try (var mounted = FoyChappeBoot.builder().classLoader(new ClassLoader(null) {})
                .applicationLayer(ModuleLayer.empty()).jspEngineLookup(lookup).build().orElseThrow()) {
            assertEquals(List.of(ModuleLayer.empty()), lookups);
            assertEquals(List.of(ModuleLayer.empty()), asked);
            assertEquals(List.of(ModuleLayer.empty()), opened);
        }
        lookups.clear();
        FoyChappeBoot.builder().classLoader(new ClassLoader(null) {}).jspEngineLookup(lookup).build()
                .ifPresent(FoyChappeBoot.Mounted::close);
        assertEquals(List.of(ModuleLayer.boot()), lookups);
    }

    @Test
    void theEngineIsLookedUpOnceAndOpenedOnce() throws Exception {
        var lookups = new AtomicInteger();
        var openings = new AtomicInteger();
        var engine = pageEngine(true, new ArrayList<>(), new ArrayList<>(), openings);
        try (var mounted = FoyChappeBoot.builder().classLoader(new ClassLoader(null) {})
                .jspEngineLookup(layer -> {
                    lookups.incrementAndGet();
                    return Optional.of(engine);
                }).build().orElseThrow()) {
            assertEquals(1, lookups.get());
            assertEquals(1, openings.get());
        }
    }

    private static JspEngine throwingHasPagesEngine(AtomicInteger asked) {
        return new JspEngine() {
            @Override
            public Application deploy(jakarta.servlet.ServletContext context, ModuleLayer layer) {
                return new Application() {
                    @Override public Servlet servlet() { return new PageServlet(); }
                    @Override public Servlet servletFor(String jspFile) { return new PageServlet(); }
                    @Override public void close() {}
                };
            }

            @Override
            public boolean hasPages(ModuleLayer layer) {
                asked.incrementAndGet();
                throw new IllegalStateException("boom");
            }
        };
    }

    @Test
    void hasPagesIsNotAskedForANonEmptyApp() throws Exception {
        var asked = new AtomicInteger();
        var engine = throwingHasPagesEngine(asked);
        try (var mounted = plainApp(null).contextPath("/")
                .jspEngineLookup(layer -> Optional.of(engine)).build().orElseThrow()) {
            assertEquals("page", getThroughRouter(mounted, false, "/x.jsp").body());
        }
        assertEquals(0, asked.get());
    }

    @Test
    void aThrowingHasPagesFailsTheBuild() {
        var engine = throwingHasPagesEngine(new AtomicInteger());
        assertThrows(ServletException.class, () -> FoyChappeBoot.builder()
                .classLoader(new ClassLoader(null) {}).jspEngineLookup(layer -> Optional.of(engine)).build());
    }

    @Test
    void aLookupFailingWithAServiceConfigurationErrorFailsTheBuildAsAServletException() {
        var e = assertThrows(ServletException.class, () -> FoyChappeBoot.builder()
                .classLoader(new ClassLoader(null) {})
                .jspEngineLookup(layer -> { throw new java.util.ServiceConfigurationError("broken provider"); })
                .build());
        assertTrue(e.getMessage().contains("broken provider"), e.getMessage());
    }

    @Test
    void aLookupFailingWithARuntimeExceptionFailsTheBuildAsAServletException() {
        var e = assertThrows(ServletException.class, () -> FoyChappeBoot.builder()
                .classLoader(new ClassLoader(null) {})
                .jspEngineLookup(layer -> { throw new IllegalStateException("two engines"); })
                .build());
        assertTrue(e.getMessage().contains("two engines"), e.getMessage());
    }

    @Test
    void applicationLayerRejectsNull() {
        assertThrows(NullPointerException.class, () -> FoyChappeBoot.builder().applicationLayer(null));
    }

    @Test
    void componentThatCannotBeInstantiatedSurfacesAsServletException() {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>bad</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>bad</servlet-name><url-pattern>/bad</url-pattern></servlet-mapping>
            </web-app>""".formatted(NotInstantiable.class.getName());

        var builder = FoyChappeBoot.builder().contextPath("/")
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes()));
        var ex = assertThrows(ServletException.class, builder::build);
        assertNotNull(ex.getCause(), "the underlying failure is kept as the cause");
    }

    /** Misuses {@code @WebFilter} on a servlet: the annotation requires a {@code Filter}. */
    @WebFilter("/misused")
    public static class MisusedFilter extends HttpServlet {}

    @Test
    void annotationMisuseFoundDuringCdiDiscoverySurfacesAsServletException() {
        // A minimal BeanManager without any CdiWebComponents index: discovery walks the Servlet beans.
        Bean<?> bean = (Bean<?>) Proxy.newProxyInstance(Bean.class.getClassLoader(), new Class<?>[]{Bean.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getBeanClass" -> MisusedFilter.class;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "Bean[" + MisusedFilter.class.getName() + "]";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        BeanManager bm = (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[]{BeanManager.class}, (p, m, a) -> switch (m.getName()) {
                    case "getBeans" -> a[0] == Servlet.class ? Set.of(bean) : Set.of();
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "FakeBeanManager";
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        var builder = FoyChappeBoot.builder().contextPath("/")
                .classLoader(getClass().getClassLoader())
                .beanManager(bm);
        var ex = assertThrows(ServletException.class, builder::build);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause(), "the misuse is kept as the cause");
        assertTrue(ex.getMessage().contains(MisusedFilter.class.getName()), ex::getMessage);
        assertTrue(ex.getMessage().contains("@WebFilter"), ex::getMessage);
    }

    public static class Still extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("hello " + getInitParameter("who"));
        }
    }

    @Test
    void servletOfTheWrongTypeIsSkippedAndTheRestDeploys() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>notAServlet</servlet-name><servlet-class>java.lang.Object</servlet-class></servlet>
              <servlet-mapping><servlet-name>notAServlet</servlet-name><url-pattern>/bad</url-pattern></servlet-mapping>
              <servlet><servlet-name>h</servlet-name><servlet-class>%s</servlet-class>
                <init-param><param-name>who</param-name><param-value>still</param-value></init-param></servlet>
              <servlet-mapping><servlet-name>h</servlet-name><url-pattern>/hello</url-pattern></servlet-mapping>
            </web-app>""".formatted(Still.class.getName());

        var mounted = FoyChappeBoot.builder().contextPath("/")
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes()))
                .build().orElseThrow();
        Server server = Server.builder().host("127.0.0.1").port(0).handler(mounted.handler()).build();
        server.start();
        int port = server.port();
        try {
            var client = HttpClient.newHttpClient();
            assertEquals("hello still", client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(404, client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/bad")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertNull(mounted.servletContext().getServletRegistration("notAServlet"));
        } finally {
            server.stop();
            mounted.close();
        }
    }

    /** Spec-forbidden: 'value' and 'urlPatterns' together. */
    @jakarta.servlet.annotation.WebServlet(value = "/a", urlPatterns = "/b")
    public static class BothPatterns extends HttpServlet {}

    @Test
    void annotationMisuseSurfacesAsServletException() {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>m</servlet-name><servlet-class>%s</servlet-class></servlet>
            </web-app>""".formatted(BothPatterns.class.getName());
        var builder = FoyChappeBoot.builder().contextPath("/")
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes()));
        var ex = assertThrows(ServletException.class, builder::build);
        assertTrue(ex.getMessage().contains(BothPatterns.class.getName()), ex.getMessage());
    }

    /** A servlet with no side effect on the counters of the other tests. */
    public static class Plain extends HttpServlet {}

    @Test
    void builderSessionTimeoutOfZeroOrLessNeverExpires() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>h</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>h</servlet-name><url-pattern>/hello</url-pattern></servlet-mapping>
            </web-app>""".formatted(Plain.class.getName());
        for (int seconds : new int[] {-120, -1, 0}) {
            var mounted = FoyChappeBoot.builder().contextPath("/").sessionTimeoutSeconds(seconds)
                    .classLoader(getClass().getClassLoader())
                    .webXml(new ByteArrayInputStream(xml.getBytes()))
                    .build().orElseThrow();
            try {
                assertEquals(0, mounted.servletContext().getSessionTimeout(), "seconds=" + seconds);
                assertEquals(-1, mounted.deployment().sessionManager().defaultMaxInactiveSeconds(),
                        "seconds=" + seconds);
            } finally {
                mounted.close();
            }
        }
        var mounted = FoyChappeBoot.builder().contextPath("/").sessionTimeoutSeconds(90)
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes()))
                .build().orElseThrow();
        try {
            assertEquals(2, mounted.servletContext().getSessionTimeout());
        } finally {
            mounted.close();
        }
    }

    @Test
    void handlerUnderAContextPathIsMountedWithoutStrippingThePrefix() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>h</servlet-name><servlet-class>%s</servlet-class>
                <init-param><param-name>who</param-name><param-value>app</param-value></init-param></servlet>
              <servlet-mapping><servlet-name>h</servlet-name><url-pattern>/hello</url-pattern></servlet-mapping>
            </web-app>""".formatted(Hello.class.getName());
        try (var mounted = FoyChappeBoot.builder().contextPath("/app")
                .classLoader(getClass().getClassLoader()).discoverPluggability(false)
                .webXml(new ByteArrayInputStream(xml.getBytes()))
                .build().orElseThrow()) {
            assertEquals("/app", mounted.mountPrefix());
            // The handler dispatches on the full request path, context path included:
            // the prefix routes the request but must not be stripped.
            assertEquals(200, getThroughRouter(mounted, false, "/app/hello").statusCode());
            assertEquals("hello app", getThroughRouter(mounted, false, "/app/hello").body());
            assertEquals(404, getThroughRouter(mounted, true, "/app/hello").statusCode(),
                    "a stripping mount hides the context path from the handler");
        }
    }

    private static HttpResponse<String> getThroughRouter(FoyChappeBoot.Mounted mounted, boolean stripPrefix,
                                                         String path) throws Exception {
        var router = Router.builder()
                .mount(mounted.mountPrefix(), mounted.handler(), stripPrefix)
                .build();
        Server server = Server.builder().host("127.0.0.1").port(0).handler(router).build();
        server.start();
        int port = server.port();
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } finally {
            server.stop();
        }
    }

    private static FoyChappeBoot.Mounted versioned(String versionAttribute) throws ServletException {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"%s>
              <servlet><servlet-name>p</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>p</servlet-name><url-pattern>/p</url-pattern></servlet-mapping>
            </web-app>""".formatted(versionAttribute, Plain.class.getName());
        return FoyChappeBoot.builder().contextPath("/").classLoader(FoyChappeBootTest.class.getClassLoader())
                .discoverPluggability(false).webXml(new ByteArrayInputStream(xml.getBytes()))
                .build().orElseThrow();
    }

    @Test
    void webXmlVersionIsTheEffectiveVersion() throws Exception {
        var mounted = versioned(" version=\"5.0\"");
        try {
            assertEquals(5, mounted.servletContext().getEffectiveMajorVersion());
            assertEquals(0, mounted.servletContext().getEffectiveMinorVersion());
            assertEquals(6, mounted.servletContext().getMajorVersion(), "the container version is unchanged");
        } finally {
            mounted.close();
        }
    }

    @Test
    void webXmlWithoutVersionKeepsTheContainerVersion() throws Exception {
        var mounted = versioned("");
        try {
            assertEquals(6, mounted.servletContext().getEffectiveMajorVersion());
            assertEquals(1, mounted.servletContext().getEffectiveMinorVersion());
        } finally {
            mounted.close();
        }
    }

    public static class SessionServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            if ("/invalidate".equals(q.getServletPath())) {
                var session = q.getSession(false);
                if (session != null) session.invalidate();
            } else {
                q.getSession();
            }
            r.getWriter().write("ok");
        }
    }

    @Test
    void activeSessionsTracksTheLiveSessionsOfTheDeployment() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>s</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>s</servlet-name><url-pattern>/open</url-pattern>
                <url-pattern>/invalidate</url-pattern></servlet-mapping>
            </web-app>""".formatted(SessionServlet.class.getName());
        var mounted = FoyChappeBoot.builder().contextPath("/").discoverPluggability(false)
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow();
        Server server = Server.builder().host("127.0.0.1").port(0).handler(mounted.handler()).build();
        server.start();
        int port = server.port();
        try {
            assertEquals(0, mounted.activeSessions());
            var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/open")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(1, mounted.activeSessions());
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/invalidate")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(0, mounted.activeSessions());
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/open")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(1, mounted.activeSessions());
        } finally {
            server.stop();
            mounted.close();
        }
        assertEquals(0, mounted.activeSessions(), "closing the deployment invalidates every session");
    }

    public static class ProgrammaticListener implements jakarta.servlet.ServletRequestListener {}

    public static class DescriptorListener implements jakarta.servlet.ServletContextListener {
        @Override public void contextInitialized(jakarta.servlet.ServletContextEvent e) {
            e.getServletContext().addListener(ProgrammaticListener.class);
        }
    }

    @Test
    void listenerClassNamesListsEveryListenerInRegistrationOrder() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <listener><listener-class>%s</listener-class></listener>
              <servlet><servlet-name>p</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>p</servlet-name><url-pattern>/p</url-pattern></servlet-mapping>
            </web-app>""".formatted(DescriptorListener.class.getName(), Plain.class.getName());
        try (var mounted = FoyChappeBoot.builder().contextPath("/").discoverPluggability(false)
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes())).build().orElseThrow()) {
            var names = mounted.listenerClassNames();
            assertEquals(java.util.List.of(DescriptorListener.class.getName(),
                    ProgrammaticListener.class.getName()), names);
            assertThrows(UnsupportedOperationException.class, () -> names.add("x"));
        }
    }

    @Test
    void certificateUserIsMappedByTheInMemoryStore() throws Exception {
        X509Certificate alice;
        try (var in = FoyChappeBootTest.class.getResourceAsStream("/tls/alice.pem")) {
            alice = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        var store = FoyChappeBoot.builder().certificateUser("CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR", "admin")
                .securitySettings().identityStore();
        assertEquals(Set.of("admin"), store.validate(new X509Certificate[] {alice}).orElseThrow().roles());
    }

    @Test
    void certificateUserIsExclusiveWithIdentityStore() {
        var b = FoyChappeBoot.builder().identityStore(IdentityStore.none())
                .certificateUser("CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR", "admin");
        assertThrows(IllegalStateException.class, b::securitySettings);
    }
}
