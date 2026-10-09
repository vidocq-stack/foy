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

import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebXmlParserLegacyTest {

    private static final String DT22 = "<!DOCTYPE web-app PUBLIC '-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN'"
            + " 'http://java.sun.com/j2ee/dtds/web-app_2_2.dtd'>";
    private static final String DT23 = "<!DOCTYPE web-app PUBLIC '-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN'"
            + " 'http://java.sun.com/dtd/web-app_2_3.dtd'>";

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String app(String doctype, String patterns) {
        return doctype + "<web-app><servlet><servlet-name>S</servlet-name><servlet-class>x.S</servlet-class></servlet>"
                + "<servlet-mapping><servlet-name>S</servlet-name>" + patterns + "</servlet-mapping></web-app>";
    }

    @Test
    void doctype22SetsVersionAndDoesNotFetchDtd() throws IOException {
        var d = WebXmlParser.parse(in(app(DT22, "<url-pattern>/a</url-pattern>")));
        assertEquals("2.2", d.version());
    }

    @Test
    void doctype23SetsVersion() throws IOException {
        assertEquals("2.3", WebXmlParser.parse(in(app(DT23, "<url-pattern>/a</url-pattern>"))).version());
    }

    @Test
    void unreachableExternalDtdStillParses() throws IOException {
        String xml = "<!DOCTYPE web-app SYSTEM 'file:///does/not/exist.dtd'><web-app version=\"3.0\"/>";
        assertEquals("3.0", WebXmlParser.parse(in(xml)).version());
    }

    @Test
    void legacyPatternWithoutLeadingSlashGetsOne() throws IOException {
        var d = WebXmlParser.parse(in(app(DT22,
                "<url-pattern>WithoutLeadingSlashTest</url-pattern><url-pattern>*.jsp</url-pattern>"
                        + "<url-pattern></url-pattern><url-pattern>/x/*</url-pattern>")));
        var p = d.servletMappings().stream().map(m -> m.urlPattern()).toList();
        assertEquals(java.util.List.of("/WithoutLeadingSlashTest", "*.jsp", "", "/x/*"), p);
    }

    @Test
    void legacyFilterMappingAndSecurityPatternsAreFixed() throws IOException {
        String xml = DT23 + "<web-app><filter><filter-name>F</filter-name><filter-class>x.F</filter-class></filter>"
                + "<filter-mapping><filter-name>F</filter-name><url-pattern>f</url-pattern></filter-mapping>"
                + "<security-constraint><web-resource-collection><web-resource-name>r</web-resource-name>"
                + "<url-pattern>sec</url-pattern></web-resource-collection></security-constraint></web-app>";
        var d = WebXmlParser.parse(in(xml));
        assertEquals("/f", d.filterMappings().getFirst().urlPattern());
        assertEquals("/sec", d.securityConstraints().getFirst().collections().getFirst().urlPatterns().getFirst());
    }

    @Test
    void modernDescriptorKeepsPatternAndMatcherRejectsIt() throws IOException {
        var d = WebXmlParser.parse(in("<web-app version=\"6.1\"><servlet><servlet-name>S</servlet-name>"
                + "<servlet-class>x.S</servlet-class></servlet><servlet-mapping><servlet-name>S</servlet-name>"
                + "<url-pattern>WithoutLeadingSlashTest</url-pattern></servlet-mapping></web-app>"));
        String p = d.servletMappings().getFirst().urlPattern();
        assertEquals("WithoutLeadingSlashTest", p);
        assertThrows(IllegalArgumentException.class, () -> UrlPatternMatcher.of(p));
    }

    @Test
    void billionLaughsDoesNotExpand() {
        String xml = "<!DOCTYPE web-app [<!ENTITY a 'AAAAAAAAAA'><!ENTITY b '&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;'>"
                + "<!ENTITY c '&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;'><!ENTITY d '&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;'>"
                + "<!ENTITY e '&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;'>]><web-app><display-name>&e;</display-name></web-app>";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try {
                var d = WebXmlParser.parse(in(xml));
                assertFalse(String.valueOf(d.displayName()).contains("AAAAAAAAAA"),
                        "entity text must not be expanded");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().startsWith("invalid"), expected.getMessage());
            }
        });
    }

    @Test
    void externalEntityContentNeverAppears(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String xml = "<!DOCTYPE web-app [<!ENTITY x SYSTEM '" + secret.toUri() + "'>]>"
                + "<web-app><display-name>&x;</display-name></web-app>";
        try {
            var d = WebXmlParser.parse(in(xml));
            assertFalse(String.valueOf(d.displayName()).contains("TOP-SECRET"));
        } catch (IOException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains("TOP-SECRET"));
        }
    }

    @Test
    void fragmentDoctypeIsHandledTheSameWay(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("s.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String xml = "<!DOCTYPE web-fragment [<!ENTITY x SYSTEM '" + secret.toUri() + "'>]>"
                + "<web-fragment version=\"6.1\"><name>&x;</name></web-fragment>";
        try {
            var d = WebXmlParser.parseFragment(in(xml));
            assertFalse(String.valueOf(d.fragmentName()).contains("TOP-SECRET"));
        } catch (IOException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains("TOP-SECRET"));
        }
    }
}
