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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Descriptor elements carried by {@link WebAppModel} and applied by the deployer. */
class WebAppModelElementsEndToEndTest {

    public static class Probe extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var ctx = req.getServletContext();
            var cc = ctx.getSessionCookieConfig();
            String cookieState;
            try {
                cc.setName("LATE");
                cookieState = "mutable";
            } catch (IllegalStateException e) {
                cookieState = "readonly";
            }
            resp.getWriter().write(String.join("|",
                    String.valueOf(ctx.getMimeType("a.FOO")),
                    String.valueOf(ctx.getMimeType("a.html")),
                    String.valueOf(req.getCharacterEncoding()),
                    String.valueOf(resp.getCharacterEncoding()),
                    cc.getName(), String.valueOf(cc.isHttpOnly()), cookieState,
                    String.valueOf(ctx.getEffectiveSessionTrackingModes()),
                    String.valueOf(ctx.getDefaultSessionTrackingModes())));
        }
    }

    public static class Multipart extends HttpServlet {}

    public static class Disabled extends HttpServlet {
        public Disabled() { throw new AssertionError("a disabled servlet must not be instantiated"); }
    }

    private static final String WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <request-character-encoding>UTF-8</request-character-encoding>
              <response-character-encoding>ISO-8859-1</response-character-encoding>
              <default-context-path>/dflt</default-context-path>
              <deny-uncovered-http-methods/>
              <welcome-file-list><welcome-file>index.html</welcome-file></welcome-file-list>
              <mime-mapping><extension>foo</extension><mime-type>application/x-foo</mime-type></mime-mapping>
              <security-role><role-name>admin</role-name></security-role>
              <servlet><servlet-name>probe</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet><servlet-name>mp</servlet-name><servlet-class>%s</servlet-class>
                <multipart-config><location>/tmp/up</location><max-file-size>100</max-file-size>
                  <max-request-size>200</max-request-size><file-size-threshold>10</file-size-threshold>
                </multipart-config>
              </servlet>
              <servlet><servlet-name>off</servlet-name><servlet-class>%s</servlet-class>
                <enabled>false</enabled></servlet>
              <servlet-mapping><servlet-name>probe</servlet-name><url-pattern>/probe</url-pattern></servlet-mapping>
              <servlet-mapping><servlet-name>mp</servlet-name><url-pattern>/mp</url-pattern></servlet-mapping>
              <servlet-mapping><servlet-name>off</servlet-name><url-pattern>/off</url-pattern></servlet-mapping>
              <session-config>
                <cookie-config><name>MYSESSION</name><http-only>true</http-only></cookie-config>
                <tracking-mode>COOKIE</tracking-mode>
              </session-config>
            </web-app>""".formatted(Probe.class.getName(), Multipart.class.getName(), Disabled.class.getName());

    private Server server;
    private Deployment deployment;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
    }

    private WebAppModel model() throws Exception {
        var d = WebXmlParser.parse(new ByteArrayInputStream(WEB_XML.getBytes()));
        var b = WebAppModel.builder("/");
        DescriptorMerger.merge(d, DescriptorMerger.AnnotatedComponents.none(),
                ComponentFactory.reflective(getClass().getClassLoader()), b);
        return b.build();
    }

    private void deploy(WebAppModel model) {
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()));
        var r = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void modelCarriesTheNewElements() throws Exception {
        var m = model();
        assertEquals(List.of("index.html"), m.welcomeFiles());
        assertEquals("application/x-foo", m.mimeMappings().get("foo"));
        assertEquals("UTF-8", m.requestCharacterEncoding());
        assertEquals("ISO-8859-1", m.responseCharacterEncoding());
        assertEquals("/dflt", m.defaultContextPath());
        assertTrue(m.denyUncoveredHttpMethods());
        assertEquals("MYSESSION", m.cookieConfig().name());
        assertEquals(Set.of(SessionTrackingMode.COOKIE), m.trackingModes());
        assertEquals(List.of("admin"), m.securityRoles());
        ServletDecl mp = m.servlets().stream().filter(s -> s.name().equals("mp")).findFirst().orElseThrow();
        MultipartConfigElement e = mp.multipartConfig();
        assertEquals("/tmp/up", e.getLocation());
        assertEquals(100, e.getMaxFileSize());
        assertEquals(200, e.getMaxRequestSize());
        assertEquals(10, e.getFileSizeThreshold());
        ServletDecl off = m.servlets().stream().filter(s -> s.name().equals("off")).findFirst().orElseThrow();
        assertFalse(off.enabled());
    }

    @Test
    void runtimeAppliesTheElements() throws Exception {
        deploy(model());
        assertEquals("application/x-foo|text/html|UTF-8|ISO-8859-1|MYSESSION|true|readonly|[COOKIE]|[COOKIE]",
                get("/probe").body());
    }

    @Test
    void disabledServletIsDeclaredButNeitherInstantiatedNorMapped() throws Exception {
        deploy(model());
        assertEquals(404, get("/off").statusCode());
        assertNotNull(deployment.servletContext().getServletRegistration("off"));
    }
}
