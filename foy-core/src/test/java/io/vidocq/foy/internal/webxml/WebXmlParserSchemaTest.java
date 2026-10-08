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

import jakarta.servlet.SessionTrackingMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebXmlParserSchemaTest {

    private static ByteArrayInputStream xml(String body) {
        return new ByteArrayInputStream(("<web-app version=\"6.1\">" + body + "</web-app>")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static WebAppDescriptor parse(String body) throws IOException {
        return WebXmlParser.parse(xml(body));
    }

    @Test
    void welcomeMimeEncodingsContextPathAndDenyUncovered() throws IOException {
        var d = parse("""
                <default-context-path>/app</default-context-path>
                <request-character-encoding>UTF-8</request-character-encoding>
                <response-character-encoding>ISO-8859-1</response-character-encoding>
                <deny-uncovered-http-methods/>
                <welcome-file-list><welcome-file>index.html</welcome-file>
                  <welcome-file>home.jsp</welcome-file></welcome-file-list>
                <mime-mapping><extension>.TXT</extension><mime-type>text/plain</mime-type></mime-mapping>
                <mime-mapping><extension>svg</extension><mime-type>image/svg+xml</mime-type></mime-mapping>
                """);
        assertEquals(List.of("index.html", "home.jsp"), d.welcomeFiles());
        assertEquals(Map.of("txt", "text/plain", "svg", "image/svg+xml"), d.mimeMappings());
        assertEquals("UTF-8", d.requestCharacterEncoding());
        assertEquals("ISO-8859-1", d.responseCharacterEncoding());
        assertEquals("/app", d.defaultContextPath());
        assertTrue(d.denyUncoveredHttpMethods());
    }

    @Test
    void cookieConfigAttributesAndTrackingModes() throws IOException {
        var d = parse("""
                <session-config>
                  <session-timeout>30</session-timeout>
                  <cookie-config>
                    <name>SID</name><domain>example.com</domain><path>/p</path>
                    <comment>c</comment><http-only>true</http-only><secure>false</secure>
                    <max-age>600</max-age>
                    <attribute><attribute-name>SameSite</attribute-name>
                      <attribute-value>Lax</attribute-value></attribute>
                  </cookie-config>
                  <tracking-mode>COOKIE</tracking-mode>
                  <tracking-mode>SSL</tracking-mode>
                </session-config>
                """);
        assertEquals(30, d.sessionTimeoutMinutes());
        var c = d.cookieConfig();
        assertEquals("SID", c.name());
        assertEquals("example.com", c.domain());
        assertEquals("/p", c.path());
        assertEquals("c", c.comment());
        assertEquals(Boolean.TRUE, c.httpOnly());
        assertEquals(Boolean.FALSE, c.secure());
        assertEquals(600, c.maxAge());
        assertEquals(Map.of("SameSite", "Lax"), c.attributes());
        assertEquals(Set.of(SessionTrackingMode.COOKIE, SessionTrackingMode.SSL), d.trackingModes());
    }

    @Test
    void cookieConfigDefaultsAndBadValues() throws IOException {
        var c = parse("<session-config><cookie-config/></session-config>").cookieConfig();
        assertNotNull(c);
        assertNull(c.name());
        assertNull(c.httpOnly());
        assertNull(c.secure());
        assertNull(c.maxAge());
        assertTrue(c.attributes().isEmpty());
        var e = assertThrows(IOException.class,
                () -> parse("<session-config><tracking-mode>BOGUS</tracking-mode></session-config>"));
        assertTrue(e.getMessage().contains("BOGUS"), e.getMessage());
        e = assertThrows(IOException.class, () -> parse(
                "<session-config><cookie-config><max-age>x</max-age></cookie-config></session-config>"));
        assertTrue(e.getMessage().contains("max-age"), e.getMessage());
    }

    @Test
    void multipartEnabledRunAsJspFile() throws IOException {
        var d = parse("""
                <servlet><servlet-name>up</servlet-name><servlet-class>a.Up</servlet-class>
                  <enabled>false</enabled><run-as><role-name>admin</role-name></run-as>
                  <multipart-config><location>/tmp</location><max-file-size>10</max-file-size>
                    <max-request-size>20</max-request-size>
                    <file-size-threshold>5</file-size-threshold></multipart-config>
                </servlet>
                <servlet><servlet-name>jsp</servlet-name><jsp-file>/x.jsp</jsp-file>
                  <multipart-config/></servlet>
                <servlet><servlet-name>plain</servlet-name><servlet-class>a.P</servlet-class></servlet>
                """);
        var up = d.servlets().get(0);
        assertFalse(up.enabled());
        assertEquals("admin", up.runAs());
        assertEquals(new WebAppDescriptor.MultipartConfigDef("/tmp", 10L, 20L, 5), up.multipartConfig());
        assertNull(up.jspFile());
        var jsp = d.servlets().get(1);
        assertEquals("/x.jsp", jsp.jspFile());
        assertEquals(new WebAppDescriptor.MultipartConfigDef(null, -1L, -1L, 0), jsp.multipartConfig());
        assertTrue(jsp.enabled());
        var plain = d.servlets().get(2);
        assertNull(plain.multipartConfig());
        assertNull(plain.runAs());
        assertTrue(plain.enabled());
    }

    @Test
    void securityConstraint() throws IOException {
        var d = parse("""
                <security-constraint>
                  <web-resource-collection><web-resource-name>a</web-resource-name>
                    <url-pattern>/a/*</url-pattern><url-pattern>/b</url-pattern>
                    <http-method>GET</http-method><http-method>POST</http-method>
                  </web-resource-collection>
                  <web-resource-collection><web-resource-name>b</web-resource-name>
                    <url-pattern>/c</url-pattern>
                    <http-method-omission>DELETE</http-method-omission>
                  </web-resource-collection>
                  <auth-constraint><role-name>admin</role-name><role-name>user</role-name></auth-constraint>
                  <user-data-constraint><transport-guarantee>CONFIDENTIAL</transport-guarantee>
                  </user-data-constraint>
                </security-constraint>
                <security-constraint>
                  <web-resource-collection><url-pattern>/deny</url-pattern></web-resource-collection>
                  <auth-constraint/>
                </security-constraint>
                <security-constraint>
                  <web-resource-collection><url-pattern>/open</url-pattern></web-resource-collection>
                </security-constraint>
                """);
        var cs = d.securityConstraints();
        assertEquals(3, cs.size());
        var c0 = cs.get(0);
        assertEquals(List.of(
                new SecurityDefs.WebResourceCollectionDef("a", List.of("/a/*", "/b"),
                        List.of("GET", "POST"), List.of()),
                new SecurityDefs.WebResourceCollectionDef("b", List.of("/c"),
                        List.of(), List.of("DELETE"))), c0.collections());
        assertEquals(List.of("admin", "user"), c0.rolesAllowed());
        assertEquals("CONFIDENTIAL", c0.transportGuarantee());
        assertEquals(List.of(), cs.get(1).rolesAllowed());
        assertNull(cs.get(1).transportGuarantee());
        assertNull(cs.get(2).rolesAllowed());
    }

    @Test
    void mixedMethodAndOmissionAreKept() throws IOException {
        var d = parse("""
                <security-constraint><web-resource-collection><url-pattern>/m</url-pattern>
                  <http-method>GET</http-method><http-method-omission>PUT</http-method-omission>
                </web-resource-collection></security-constraint>""");
        var col = d.securityConstraints().get(0).collections().get(0);
        assertEquals(List.of("GET"), col.httpMethods());
        assertEquals(List.of("PUT"), col.httpMethodOmissions());
    }

    @Test
    void loginConfigAndRoles() throws IOException {
        var d = parse("""
                <login-config><auth-method>FORM</auth-method><realm-name>r</realm-name>
                  <form-login-config><form-login-page>/login.html</form-login-page>
                    <form-error-page>/err.html</form-error-page></form-login-config></login-config>
                <security-role><role-name>admin</role-name></security-role>
                <security-role><role-name>user</role-name></security-role>
                """);
        assertEquals(new SecurityDefs.LoginConfigDef("FORM", "r", "/login.html", "/err.html"),
                d.loginConfig());
        assertEquals(List.of("admin", "user"), d.securityRoles());
    }

    @Test
    void fragmentsParseTheseElementsToo() throws IOException {
        var d = WebXmlParser.parseFragment(new ByteArrayInputStream("""
                <web-fragment><name>f</name>
                  <welcome-file-list><welcome-file>f.html</welcome-file></welcome-file-list>
                  <mime-mapping><extension>foo</extension><mime-type>x/foo</mime-type></mime-mapping>
                  <session-config><tracking-mode>URL</tracking-mode></session-config>
                </web-fragment>""".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("f.html"), d.welcomeFiles());
        assertEquals(Map.of("foo", "x/foo"), d.mimeMappings());
        assertEquals(Set.of(SessionTrackingMode.URL), d.trackingModes());
    }

    @Test
    void absentElementsHaveEmptyDefaults() throws IOException {
        var d = parse("");
        assertTrue(d.welcomeFiles().isEmpty());
        assertTrue(d.mimeMappings().isEmpty());
        assertNull(d.requestCharacterEncoding());
        assertNull(d.responseCharacterEncoding());
        assertNull(d.defaultContextPath());
        assertFalse(d.denyUncoveredHttpMethods());
        assertNull(d.cookieConfig());
        assertTrue(d.trackingModes().isEmpty());
        assertTrue(d.securityConstraints().isEmpty());
        assertNull(d.loginConfig());
        assertTrue(d.securityRoles().isEmpty());
    }
}
