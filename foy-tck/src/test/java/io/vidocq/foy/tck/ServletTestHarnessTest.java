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
package io.vidocq.foy.tck;

import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketPeer;
import io.vidocq.foy.spi.websocket.WebSocketUpgradeHandler;
import io.vidocq.foy.tck.arquillian.VidocqContainerConfiguration;
import io.vidocq.foy.tck.arquillian.VidocqDeployableContainer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletTestHarnessTest {

    private static final String WEB_APP = "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"";
    private static final String FRAGMENT = "<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"";

    @Test
    void servletRegisteredOnTwoPatternsIsInitialisedOnceWithItsParams() throws Exception {
        var inits = new AtomicInteger();
        HttpServlet s = new HttpServlet() {
            @Override public void init() { inits.incrementAndGet(); }
            @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
                r.getWriter().write(getInitParameter("k"));
            }
        };
        try (var h = ServletTestHarness.builder()
                .servlet("/a", s, "S", Map.of("k", "v"))
                .servlet("/b", s, "S", Map.of("k", "v"))
                .contextPath("/app").start()) {
            // get() resolves against baseUrl(), which already carries the context path.
            assertEquals("v", h.get("/a").body());
            assertEquals("v", h.get("/b").body());
            assertEquals(1, inits.get());
        }
    }

    @Test
    void unnamedServletsOfTheSameClassStayDistinct() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("/x", writing("x"))
                .servlet("/y", writing("y"))
                .start()) {
            assertEquals("x", h.get("/x").body());
            assertEquals("y", h.get("/y").body());
        }
    }

    @Test
    void warWithoutWebXmlDeploysTheServletOfItsLibJarFragment() throws Exception {
        JavaArchive lib = ShrinkWrap.create(JavaArchive.class, "fragment-1.jar")
                .addClass(FragmentServlet.class)
                .addAsResource(new StringAsset(FRAGMENT + """
                        ><name>Fragment1</name>
                          <servlet><servlet-name>frag</servlet-name><servlet-class>%s</servlet-class></servlet>
                          <servlet-mapping><servlet-name>frag</servlet-name><url-pattern>/a</url-pattern></servlet-mapping>
                        </web-fragment>""".formatted(FragmentServlet.class.getName())), "META-INF/web-fragment.xml");
        WebArchive war = ShrinkWrap.create(WebArchive.class, "fragment_web.war").addAsLibraries(lib);
        try (var d = new Deployed(war)) {
            HttpResponse<String> r = d.get("/a");
            assertEquals(200, r.statusCode());
            assertEquals("fragment", r.body());
        }
    }

    @Test
    void metadataCompleteWebXmlIgnoresAnnotatedServlets() throws Exception {
        WebArchive complete = ShrinkWrap.create(WebArchive.class, "complete_web.war")
                .addClass(AnnotatedServlet.class)
                .setWebXML(new StringAsset(WEB_APP + " metadata-complete=\"true\"></web-app>"));
        try (var d = new Deployed(complete)) {
            assertEquals(404, d.get("/annotated").statusCode());
        }
        WebArchive open = ShrinkWrap.create(WebArchive.class, "open_web.war")
                .addClass(AnnotatedServlet.class)
                .setWebXML(new StringAsset(WEB_APP + "></web-app>"));
        try (var d = new Deployed(open)) {
            assertEquals("annotated", d.get("/annotated").body());
        }
    }

    @Test
    void webXmlFilterWithoutAsyncSupportedDisablesAsyncForTheServlet() throws Exception {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "async_web.war")
                .addClasses(PassFilter.class, AsyncProbeServlet.class)
                .setWebXML(new StringAsset(WEB_APP + """
                        >
                          <filter><filter-name>pass</filter-name><filter-class>%s</filter-class></filter>
                          <filter-mapping><filter-name>pass</filter-name><url-pattern>/*</url-pattern></filter-mapping>
                          <servlet><servlet-name>probe</servlet-name><servlet-class>%s</servlet-class>
                            <async-supported>true</async-supported></servlet>
                          <servlet-mapping><servlet-name>probe</servlet-name><url-pattern>/probe</url-pattern></servlet-mapping>
                        </web-app>""".formatted(PassFilter.class.getName(), AsyncProbeServlet.class.getName())));
        try (var d = new Deployed(war)) {
            assertEquals("false", d.get("/probe").body());
        }
    }

    @Test
    void libJarResourcesAndInitializersAreVisibleToTheApplication() throws Exception {
        JavaArchive lib = ShrinkWrap.create(JavaArchive.class, "lib-1.jar")
                .addClasses(MarkingInitializer.class, ResourceServlet.class)
                .addAsResource(new StringAsset("from-jar"), "META-INF/resources/r.txt")
                .addAsResource(new StringAsset("shadowed"), "META-INF/resources/root.txt")
                .addAsResource(new StringAsset(MarkingInitializer.class.getName()),
                        "META-INF/services/" + ServletContainerInitializer.class.getName())
                .addAsResource(new StringAsset(FRAGMENT + """
                        >
                          <servlet><servlet-name>res</servlet-name><servlet-class>%s</servlet-class></servlet>
                          <servlet-mapping><servlet-name>res</servlet-name><url-pattern>/res</url-pattern></servlet-mapping>
                        </web-fragment>""".formatted(ResourceServlet.class.getName())), "META-INF/web-fragment.xml");
        WebArchive war = ShrinkWrap.create(WebArchive.class, "lib_web.war")
                .addAsWebResource(new StringAsset("from-war"), "root.txt")
                .addAsLibraries(lib);
        try (var d = new Deployed(war)) {
            assertEquals("from-jar|from-war|marked", d.get("/res").body());
        }
    }

    @Test
    void absoluteOrderingWithoutOthersExcludesTheUnlistedFragmentJarAndItsInitializers() throws Exception {
        JavaArchive lib = ShrinkWrap.create(JavaArchive.class, "lib-2.jar")
                .addClasses(MarkingInitializer.class, AnnotatedServlet.class)
                .addAsResource(new StringAsset(MarkingInitializer.class.getName()),
                        "META-INF/services/" + ServletContainerInitializer.class.getName())
                .addAsResource(new StringAsset(FRAGMENT + "><name>Excluded</name></web-fragment>"),
                        "META-INF/web-fragment.xml");
        WebArchive war = ShrinkWrap.create(WebArchive.class, "ordering_web.war")
                .addClass(ResourceServlet.class)
                .addAsLibraries(lib)
                .setWebXML(new StringAsset(WEB_APP + """
                        >
                          <absolute-ordering/>
                          <servlet><servlet-name>res</servlet-name><servlet-class>%s</servlet-class></servlet>
                          <servlet-mapping><servlet-name>res</servlet-name><url-pattern>/res</url-pattern></servlet-mapping>
                        </web-app>""".formatted(ResourceServlet.class.getName())));
        try (var d = new Deployed(war)) {
            // The excluded jar's annotated servlet is not deployed, its initializer does not run.
            assertEquals(404, d.get("/annotated").statusCode());
            assertEquals("null|null|null", d.get("/res").body());
        }
    }

    @Test
    void malformedWebXmlFailsTheDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "bad_xml_web.war")
                .setWebXML(new StringAsset(WEB_APP + "><servlet>"));
        assertThrows(DeploymentException.class, () -> new Deployed(war).close());
    }

    @Test
    void initializerThatCannotBeCreatedFailsTheDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "bad_sci_web.war")
                .addAsResource(new StringAsset("no.such.Initializer"),
                        "META-INF/services/" + ServletContainerInitializer.class.getName());
        assertThrows(DeploymentException.class, () -> new Deployed(war).close());
    }

    @Test
    void annotationMisuseFailsTheDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "misuse_web.war").addClass(NotAServlet.class);
        assertThrows(DeploymentException.class, () -> new Deployed(war).close());
    }

    @Test
    void servicesFileOutsideTheClassesRootIsIgnored() throws Exception {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "root_sci_web.war")
                .addClass(ResourceServlet.class)
                .addAsWebResource(new StringAsset(MarkingInitializer.class.getName()),
                        "META-INF/services/" + ServletContainerInitializer.class.getName())
                .setWebXML(new StringAsset(WEB_APP + """
                        >
                          <servlet><servlet-name>res</servlet-name><servlet-class>%s</servlet-class></servlet>
                          <servlet-mapping><servlet-name>res</servlet-name><url-pattern>/res</url-pattern></servlet-mapping>
                        </web-app>""".formatted(ResourceServlet.class.getName())));
        try (var d = new Deployed(war)) {
            assertEquals("null|null|null", d.get("/res").body());
        }
    }

    private static HttpServlet writing(String body) {
        return new HttpServlet() {
            @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
                r.getWriter().write(body);
            }
        };
    }

    /**
     * {@code close()} undeploys the application while the server it owns still serves, then stops that
     * server (BUG-20261115-05): a WebSocket peer reads the undeploy's 1001 Close before the end of the
     * stream. The other way round, the server's stop cut the connection with no Close frame.
     */
    @Test
    void closeSendsAWebSocketPeerItsCloseFrameBeforeTheServerStops() throws Exception {
        var opened = new CountDownLatch(1);
        OpenedTakeover.OPENED.set(opened);
        // The harness closes in the test, and again, as a no-op, when the test failed before.
        try (var harness = ServletTestHarness.builder().servlet("/ws", new UpgradingServlet()).start();
             var socket = new Socket("127.0.0.1", harness.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            assertTrue(opened.await(5, TimeUnit.SECONDS), "onOpen");
            harness.close();
            assertEquals(0x88, firstByte(in), "no Close frame before the end of the stream");
            byte[] payload = in.readNBytes(in.read() & 0x7F);
            assertEquals(1001, ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF));
        }
    }

    /** The response head, up to its blank line. */
    private static String readHead(InputStream in) throws IOException {
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) break;
            head.append((char) b);
        }
        return head.toString();
    }

    /** The first byte of what the server sends next, or -1 when the stream ends (or is reset) first. */
    private static int firstByte(InputStream in) {
        try {
            return in.read();
        } catch (IOException _) {
            return -1;
        }
    }

    /** Hands the request over to a WebSocket that the transport runs (Foy's WebSocket SPI). */
    public static class UpgradingServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException, ServletException {
            q.upgrade(OpenedTakeover.class);
        }
    }

    /** A WebSocket that says when it opened, and does nothing else. */
    public static class OpenedTakeover implements WebSocketUpgradeHandler {
        /** The latch of the running test: each handler counts down the one set when it was created. */
        static final AtomicReference<CountDownLatch> OPENED = new AtomicReference<>(new CountDownLatch(1));

        private final CountDownLatch opened = OPENED.get();

        @Override public String subprotocol() { return null; }

        @Override public WebSocketListener listener() {
            return new WebSocketListener() {
                @Override public void onOpen(WebSocketPeer peer) { opened.countDown(); }
            };
        }

        @Override public void destroy() {
            // nothing to release
        }
    }

    @Test
    void twoWarsOfOneContainerShareOneHostAndUndeployIndependently() throws Exception {
        WebArchive one = ShrinkWrap.create(WebArchive.class, "host_one_web.war").addClass(AnnotatedServlet.class);
        WebArchive two = ShrinkWrap.create(WebArchive.class, "host_two_web.war").addClass(AnnotatedServlet.class);
        var container = new VidocqDeployableContainer();
        container.setup(new VidocqContainerConfiguration());
        try (var client = HttpClient.newHttpClient()) {
            var c1 = container.deploy(one).getContexts(HTTPContext.class).iterator().next();
            var c2 = container.deploy(two).getContexts(HTTPContext.class).iterator().next();
            assertEquals(c1.getPort(), c2.getPort());
            String host = "http://" + c1.getHost() + ":" + c1.getPort();
            assertEquals("annotated", fetch(client, host + "/host_one_web/annotated").body());
            assertEquals("annotated", fetch(client, host + "/host_two_web/annotated").body());
            assertEquals(404, fetch(client, host + "/unknown/annotated").statusCode());
            container.undeploy(one);
            assertEquals(404, fetch(client, host + "/host_one_web/annotated").statusCode());
            assertEquals("annotated", fetch(client, host + "/host_two_web/annotated").body());
        } finally {
            container.undeploy(two);
            container.stop();
        }
    }

    private static HttpResponse<String> fetch(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A war deployed through the Arquillian container, the way the official TCK deploys it. */
    private static final class Deployed implements AutoCloseable {
        private final VidocqDeployableContainer container = new VidocqDeployableContainer();
        private final WebArchive war;
        private final String base;

        Deployed(WebArchive war) throws Exception {
            this.war = war;
            container.setup(new VidocqContainerConfiguration());
            var ctx = container.deploy(war).getContexts(HTTPContext.class).iterator().next();
            String name = war.getName().substring(0, war.getName().length() - ".war".length());
            this.base = "http://" + ctx.getHost() + ":" + ctx.getPort() + "/" + name;
        }

        HttpResponse<String> get(String path) throws Exception {
            try (var client = HttpClient.newHttpClient()) {
                return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            }
        }

        @Override public void close() {
            container.undeploy(war);
            try { container.stop(); } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    public static class FragmentServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("fragment");
        }
    }

    @WebServlet("/annotated")
    public static class AnnotatedServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("annotated");
        }
    }

    @WebServlet("/misused")
    public static class NotAServlet {}

    public static class PassFilter implements Filter {
        @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(req, res);
        }
    }

    public static class AsyncProbeServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write(String.valueOf(q.isAsyncSupported()));
        }
    }

    public static class MarkingInitializer implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> classes, ServletContext ctx) {
            ctx.setAttribute("marked", "marked");
        }
    }

    public static class ResourceServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            ServletContext ctx = getServletContext();
            r.getWriter().write(read(ctx, "/r.txt") + "|" + read(ctx, "/root.txt") + "|" + ctx.getAttribute("marked"));
        }

        private static String read(ServletContext ctx, String path) throws IOException {
            try (InputStream in = ctx.getResourceAsStream(path)) {
                return in == null ? "null" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
