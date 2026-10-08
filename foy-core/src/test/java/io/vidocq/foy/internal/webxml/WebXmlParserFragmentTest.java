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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebXmlParserFragmentTest {

    private static ByteArrayInputStream xml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesFragmentNameAndRelativeOrdering() throws IOException {
        var d = WebXmlParser.parseFragment(xml("""
                <web-fragment xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <name>A</name>
                  <ordering>
                    <after><name>B</name></after>
                    <before><others/></before>
                  </ordering>
                </web-fragment>"""));
        assertEquals(WebAppDescriptor.Kind.WEB_FRAGMENT, d.kind());
        assertEquals("A", d.fragmentName());
        assertEquals(List.of("B"), d.ordering().after());
        assertFalse(d.ordering().afterOthers());
        assertTrue(d.ordering().beforeOthers());
    }

    @Test
    void parsesAbsoluteOrderingWithOthers() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app version="6.1"><absolute-ordering>
                  <name>B</name><others/><name>A</name>
                </absolute-ordering></web-app>"""));
        assertEquals(List.of("B", WebAppDescriptor.OTHERS, "A"), d.absoluteOrdering());
    }

    @Test
    void noAbsoluteOrderingIsNull() throws IOException {
        assertNull(WebXmlParser.parse(xml("<web-app version=\"6.1\"/>")).absoluteOrdering());
    }

    @Test
    void wrongRootIsRejected() {
        var e = assertThrows(IOException.class,
                () -> WebXmlParser.parse(xml("<web-fragment version=\"6.1\"/>")));
        assertTrue(e.getMessage().contains("web-fragment"), e.getMessage());
        assertThrows(IOException.class, () -> WebXmlParser.parseFragment(xml("<web-app/>")));
    }

    @Test
    void duplicateInitParamKeepsTheFirstValue() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app><servlet><servlet-name>s</servlet-name><servlet-class>x.S</servlet-class>
                  <init-param><param-name>msg1</param-name><param-value>first</param-value></init-param>
                  <init-param><param-name>msg1</param-name><param-value>ignore</param-value></init-param>
                </servlet></web-app>"""));
        assertEquals(Map.of("msg1", "first"), d.servlets().getFirst().initParams());
    }

    @Test
    void asyncSupportedIsTriState() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app>
                  <servlet><servlet-name>a</servlet-name><servlet-class>x.A</servlet-class></servlet>
                  <servlet><servlet-name>f</servlet-name><servlet-class>x.F</servlet-class>
                    <async-supported>false</async-supported></servlet>
                  <filter><filter-name>t</filter-name><filter-class>x.T</filter-class>
                    <async-supported>true</async-supported></filter>
                </web-app>"""));
        assertNull(d.servlets().get(0).asyncSupported());
        assertEquals(Boolean.FALSE, d.servlets().get(1).asyncSupported());
        assertEquals(Boolean.TRUE, d.filters().getFirst().asyncSupported());
    }

    @Test
    void malformedLoadOnStartupIsLazy() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app><servlet><servlet-name>s</servlet-name><servlet-class>x.S</servlet-class>
                  <load-on-startup>soon</load-on-startup></servlet></web-app>"""));
        assertEquals(Integer.MIN_VALUE, d.servlets().getFirst().loadOnStartup());
    }

    @Test
    void emptyLoadOnStartupIsLazy() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app><servlet><servlet-name>s</servlet-name><servlet-class>x.S</servlet-class>
                  <load-on-startup/></servlet></web-app>"""));
        assertEquals(Integer.MIN_VALUE, d.servlets().getFirst().loadOnStartup());
    }

    @Test
    void unknownDispatcherIsRejected() {
        var e = assertThrows(IOException.class, () -> WebXmlParser.parse(xml("""
                <web-app><filter-mapping><filter-name>f</filter-name><url-pattern>/*</url-pattern>
                  <dispatcher>TELEPORT</dispatcher></filter-mapping></web-app>""")));
        assertTrue(e.getMessage().contains("TELEPORT") || String.valueOf(e.getCause()).contains("TELEPORT"));
    }
}
