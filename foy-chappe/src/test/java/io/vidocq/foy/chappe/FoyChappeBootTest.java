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

import io.vidocq.chappe.api.Server;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

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

        int port;
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
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

    @Test
    void emptyApplicationYieldsEmpty() throws Exception {
        assertTrue(FoyChappeBoot.builder().classLoader(new ClassLoader(null) {}).build().isEmpty());
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
        int port;
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
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
}
