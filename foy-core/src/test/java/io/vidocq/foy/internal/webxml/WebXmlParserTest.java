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
package io.vidocq.foy.internal.webxml;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebXmlParserTest {

    @Test
    void parsesServletsFiltersListenersAndMappings() throws IOException {
        String xml = """
                <web-app>
                  <context-param>
                    <param-name>mode</param-name><param-value>prod</param-value>
                  </context-param>
                  <servlet>
                    <servlet-name>hello</servlet-name>
                    <servlet-class>com.example.HelloServlet</servlet-class>
                    <init-param><param-name>k</param-name><param-value>v</param-value></init-param>
                  </servlet>
                  <servlet-mapping>
                    <servlet-name>hello</servlet-name>
                    <url-pattern>/hello</url-pattern>
                    <url-pattern>/hi</url-pattern>
                  </servlet-mapping>
                  <filter>
                    <filter-name>auth</filter-name>
                    <filter-class>com.example.AuthFilter</filter-class>
                  </filter>
                  <filter-mapping>
                    <filter-name>auth</filter-name>
                    <url-pattern>/secure/*</url-pattern>
                    <dispatcher>REQUEST</dispatcher>
                    <dispatcher>FORWARD</dispatcher>
                  </filter-mapping>
                  <listener>
                    <listener-class>com.example.AppListener</listener-class>
                  </listener>
                  <error-page>
                    <error-code>404</error-code>
                    <location>/404.html</location>
                  </error-page>
                  <error-page>
                    <exception-type>java.lang.IllegalStateException</exception-type>
                    <location>/oops</location>
                  </error-page>
                  <session-config>
                    <session-timeout>15</session-timeout>
                  </session-config>
                </web-app>
                """;
        WebAppDescriptor d = WebXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("prod", d.contextParams().get("mode"));

        assertEquals(1, d.servlets().size());
        var servlet = d.servlets().get(0);
        assertEquals("hello", servlet.name());
        assertEquals("com.example.HelloServlet", servlet.className());
        assertEquals("v", servlet.initParams().get("k"));
        assertEquals(2, d.servletMappings().size());
        assertEquals("/hello", d.servletMappings().get(0).urlPattern());
        assertEquals("/hi", d.servletMappings().get(1).urlPattern());

        assertEquals(1, d.filters().size());
        assertEquals("auth", d.filters().get(0).name());
        var fm = d.filterMappings().get(0);
        assertEquals("/secure/*", fm.urlPattern());
        assertTrue(fm.dispatcherTypes().contains(DispatcherType.REQUEST));
        assertTrue(fm.dispatcherTypes().contains(DispatcherType.FORWARD));

        assertEquals("com.example.AppListener", d.listenerClasses().get(0));

        assertEquals(2, d.errorPages().size());
        assertEquals(404, d.errorPages().get(0).statusCode());
        assertEquals("/404.html", d.errorPages().get(0).location());
        assertEquals("java.lang.IllegalStateException", d.errorPages().get(1).exceptionType());

        assertEquals(15, d.sessionTimeoutMinutes());
    }

    @Test
    void emptyWebAppYieldsEmptyDescriptor() throws IOException {
        WebAppDescriptor d = WebXmlParser.parse(new ByteArrayInputStream("<web-app/>".getBytes()));
        assertTrue(d.isEmpty());
        assertEquals(-1, d.sessionTimeoutMinutes());
    }

    @Test
    void rejectsDoctypeDeclaration() {
        String xml = "<!DOCTYPE web-app SYSTEM \"foo.dtd\"><web-app/>";
        assertThrows(IOException.class,
                () -> WebXmlParser.parse(new ByteArrayInputStream(xml.getBytes())));
    }

    @Test
    void defaultDispatcherIsRequestWhenMissing() throws IOException {
        String xml = """
                <web-app>
                  <filter-mapping>
                    <filter-name>f</filter-name>
                    <url-pattern>/*</url-pattern>
                  </filter-mapping>
                </web-app>
                """;
        var d = WebXmlParser.parse(new ByteArrayInputStream(xml.getBytes()));
        assertEquals(1, d.filterMappings().size());
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST),
                d.filterMappings().get(0).dispatcherTypes());
    }

    @Test
    void patternsForReturnsOnlyMatching() throws IOException {
        String xml = """
                <web-app>
                  <servlet-mapping>
                    <servlet-name>a</servlet-name><url-pattern>/a</url-pattern>
                  </servlet-mapping>
                  <servlet-mapping>
                    <servlet-name>b</servlet-name><url-pattern>/b</url-pattern>
                  </servlet-mapping>
                </web-app>
                """;
        var d = WebXmlParser.parse(new ByteArrayInputStream(xml.getBytes()));
        assertEquals(java.util.List.of("/a"), d.patternsFor("a"));
        assertEquals(java.util.List.of("/b"), d.patternsFor("b"));
    }
}
