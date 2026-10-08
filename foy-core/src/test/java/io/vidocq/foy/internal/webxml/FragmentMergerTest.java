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

import io.vidocq.foy.internal.webxml.WebAppDescriptor.ErrorPageDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.FilterMappingDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.ServletDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.ServletMappingDef;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionTrackingMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FragmentMergerTest {

    private static final String NS = "xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"";

    private static WebAppDescriptor web(String body) throws Exception {
        return web("", body);
    }

    private static WebAppDescriptor web(String attrs, String body) throws Exception {
        return WebXmlParser.parse(new ByteArrayInputStream(("<web-app " + NS + attrs + ">" + body + "</web-app>")
                .getBytes(StandardCharsets.UTF_8)));
    }

    private static Fragment frag(String id, String body) throws Exception {
        return frag(id, "", body);
    }

    private static Fragment frag(String id, String attrs, String body) throws Exception {
        var d = WebXmlParser.parseFragment(new ByteArrayInputStream(("<web-fragment " + NS + attrs + "><name>" + id
                + "</name>" + body + "</web-fragment>").getBytes(StandardCharsets.UTF_8)));
        return new Fragment(id, URI.create("file:/jars/" + id + ".jar").toURL(), d);
    }

    private static String servlet(String name, String cls, String extra) {
        return "<servlet><servlet-name>" + name + "</servlet-name><servlet-class>" + cls + "</servlet-class>"
                + extra + "</servlet>";
    }

    private static String param(String k, String v) {
        return "<init-param><param-name>" + k + "</param-name><param-value>" + v + "</param-value></init-param>";
    }

    private static String mapping(String name, String pattern) {
        return "<servlet-mapping><servlet-name>" + name + "</servlet-name><url-pattern>" + pattern
                + "</url-pattern></servlet-mapping>";
    }

    private static String filter(String name, String cls, String extra) {
        return "<filter><filter-name>" + name + "</filter-name><filter-class>" + cls + "</filter-class>"
                + extra + "</filter>";
    }

    private static String filterMapping(String name, String pattern) {
        return "<filter-mapping><filter-name>" + name + "</filter-name><url-pattern>" + pattern
                + "</url-pattern></filter-mapping>";
    }

    private static ServletDef servletNamed(WebAppDescriptor d, String name) {
        return d.servlets().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertConflict(ServletException e, String... parts) {
        assertTrue(e.getMessage().startsWith("conflicting "), e.getMessage());
        for (String p : parts) assertTrue(e.getMessage().contains(p), e.getMessage() + " lacks " + p);
    }

    // ---- servlets -----------------------------------------------------------------------------

    @Test
    void fragmentOnlyServletIsAddedAfterWebXmlServlets() throws Exception {
        var m = FragmentMerger.merge(web(servlet("w", "a.W", "")), List.of(frag("F1", servlet("f", "a.F", ""))));
        assertEquals(List.of("w", "f"), m.servlets().stream().map(ServletDef::name).toList());
        assertEquals("a.F", servletNamed(m, "f").className());
    }

    @Test
    void conflictBetweenFragmentsFails() throws Exception {
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", servlet("s", "a.A", "")), frag("F2", servlet("s", "a.B", "")))));
        assertConflict(e, "<servlet-class>", "'s'", "F1", "F2");
    }

    @Test
    void webXmlResolvesConflict() throws Exception {
        var m = FragmentMerger.merge(web(servlet("s", "a.W", "")), List.of(
                frag("F1", servlet("s", "a.A", "")), frag("F2", servlet("s", "a.B", ""))));
        assertEquals(1, m.servlets().size());
        assertEquals("a.W", servletNamed(m, "s").className());
    }

    @Test
    void sameClassInTwoFragmentsMerges() throws Exception {
        var m = FragmentMerger.merge(web(""), List.of(
                frag("F1", servlet("s", "a.A", param("x", "1"))),
                frag("F2", servlet("s", "a.A", param("y", "2")))));
        assertEquals(1, m.servlets().size());
        assertEquals(Map.of("x", "1", "y", "2"), servletNamed(m, "s").initParams());
    }

    @Test
    void initParamsWebXmlWinsAndFragmentsAddMissingKeys() throws Exception {
        // Mirrors the TCK FragmentTests: msg1 from web.xml, msg2 contributed by the fragment.
        var m = FragmentMerger.merge(web(servlet("s", "a.S", param("msg1", "first"))), List.of(
                frag("F1", servlet("s", "a.S", param("msg1", "ignore") + param("msg2", "second")))));
        assertEquals(Map.of("msg1", "first", "msg2", "second"), servletNamed(m, "s").initParams());
    }

    @Test
    void initParamConflictBetweenFragmentsFails() throws Exception {
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", servlet("s", "a.S", param("k", "1"))),
                frag("F2", servlet("s", "a.S", param("k", "2"))))));
        assertConflict(e, "<init-param>", "'k'", "'s'", "F1", "F2");
    }

    @Test
    void initParamConflictIsResolvedByWebXml() throws Exception {
        var m = FragmentMerger.merge(web(servlet("s", "a.S", param("k", "xml"))), List.of(
                frag("F1", servlet("s", "a.S", param("k", "1"))),
                frag("F2", servlet("s", "a.S", param("k", "2")))));
        assertEquals(Map.of("k", "xml"), servletNamed(m, "s").initParams());
    }

    @Test
    void servletScalarsAreFirstNonNullInWebXmlThenFragmentOrder() throws Exception {
        var m = FragmentMerger.merge(web(servlet("s", "a.S", "")), List.of(
                frag("F1", servlet("s", "a.S", "<load-on-startup>3</load-on-startup>"
                        + "<run-as><role-name>r1</role-name></run-as>")),
                frag("F2", servlet("s", "a.S", "<load-on-startup>4</load-on-startup>"
                        + "<async-supported>true</async-supported>"
                        + "<run-as><role-name>r2</role-name></run-as>"
                        + "<multipart-config><max-file-size>10</max-file-size></multipart-config>"))));
        var s = servletNamed(m, "s");
        assertEquals(3, s.loadOnStartup());
        assertEquals(Boolean.TRUE, s.asyncSupported());
        assertEquals("r1", s.runAs());
        assertEquals(10L, s.multipartConfig().maxFileSize());
    }

    @Test
    void webXmlServletScalarsWinOverFragments() throws Exception {
        var m = FragmentMerger.merge(web(servlet("s", "a.S", "<load-on-startup>1</load-on-startup>"
                        + "<async-supported>false</async-supported>")),
                List.of(frag("F1", servlet("s", "a.S", "<load-on-startup>3</load-on-startup>"
                        + "<async-supported>true</async-supported>"))));
        var s = servletNamed(m, "s");
        assertEquals(1, s.loadOnStartup());
        assertEquals(Boolean.FALSE, s.asyncSupported());
    }

    // ---- servlet mappings ---------------------------------------------------------------------

    @Test
    void fragmentMappingsAreIgnoredForAServletWebXmlMaps() throws Exception {
        var m = FragmentMerger.merge(web(servlet("s", "a.S", "") + mapping("s", "/xml")),
                List.of(frag("F1", mapping("s", "/frag"))));
        assertEquals(List.of("/xml"), m.patternsFor("s"));
    }

    @Test
    void fragmentMappingsAreAUnionOtherwise() throws Exception {
        var m = FragmentMerger.merge(web(servlet("s", "a.S", "")), List.of(
                frag("F1", mapping("s", "/one")), frag("F2", mapping("s", "/two") + mapping("s", "/one"))));
        assertEquals(List.of("/one", "/two"), m.patternsFor("s"));
    }

    @Test
    void samePatternOnTwoServletsInFragmentsFails() throws Exception {
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", servlet("a", "a.A", "") + mapping("a", "/x")),
                frag("F2", servlet("b", "a.B", "") + mapping("b", "/x")))));
        assertConflict(e, "<servlet-mapping>", "'/x'", "F1", "F2");
    }

    @Test
    void patternMappedByWebXmlAndByAFragmentToAnotherServletFails() throws Exception {
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(
                web(servlet("a", "a.A", "") + mapping("a", "/x")),
                List.of(frag("F1", servlet("b", "a.B", "") + mapping("b", "/x")))));
        assertTrue(e.getMessage().startsWith("conflicting <servlet-mapping>"), e.getMessage());
        for (String p : List.of("'/x'", "'a'", "'b'", "web.xml", "F1")) {
            assertTrue(e.getMessage().contains(p), e.getMessage() + " lacks " + p);
        }
    }

    @Test
    void patternOfTheSameServletInWebXmlAndAFragmentIsFine() throws Exception {
        var m = FragmentMerger.merge(web(servlet("a", "a.A", "") + mapping("a", "/x")),
                List.of(frag("F1", mapping("a", "/x") + mapping("a", "/z"))));
        assertEquals(List.of(new ServletMappingDef("a", "/x")), m.servletMappings());
    }

    @Test
    void webXmlServletWithoutClassInheritsTheFragmentClass() throws Exception {
        var m = FragmentMerger.merge(web("<servlet><servlet-name>s</servlet-name>" + param("k", "xml") + "</servlet>"),
                List.of(frag("F1", servlet("s", "a.Frag", param("k", "frag")))));
        var s = servletNamed(m, "s");
        assertEquals("a.Frag", s.className());
        assertEquals(Map.of("k", "xml"), s.initParams());
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(
                web("<servlet><servlet-name>s</servlet-name></servlet>"),
                List.of(frag("F1", servlet("s", "a.A", "")), frag("F2", servlet("s", "a.B", "")))));
        assertConflict(e, "<servlet-class>", "'s'", "F1", "F2");
    }

    // ---- filters ------------------------------------------------------------------------------

    @Test
    void filterClassConflictBetweenFragmentsFails() throws Exception {
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", filter("f", "a.A", "")), frag("F2", filter("f", "a.B", "")))));
        assertConflict(e, "<filter-class>", "'f'", "F1", "F2");
    }

    @Test
    void filterParamsAndAsyncMergeLikeServlets() throws Exception {
        var m = FragmentMerger.merge(web(filter("f", "a.F", param("k", "xml"))), List.of(
                frag("F1", filter("f", "a.Other", param("k", "1") + param("x", "1")
                        + "<async-supported>true</async-supported>")),
                frag("F2", filter("g", "a.G", ""))));
        assertEquals(List.of("f", "g"), m.filters().stream().map(WebAppDescriptor.FilterDef::name).toList());
        var f = m.filters().getFirst();
        assertEquals("a.F", f.className());
        assertEquals(Map.of("k", "xml", "x", "1"), f.initParams());
        assertEquals(Boolean.TRUE, f.asyncSupported());
    }

    @Test
    void filterMappingsWebXmlFirstThenFragmentOrder() throws Exception {
        var m = FragmentMerger.merge(web(filter("w", "a.W", "") + filterMapping("w", "/*")), List.of(
                frag("F3", filter("f3", "a.F3", "") + filterMapping("f3", "/*")),
                frag("F2", filter("f2", "a.F2", "") + filterMapping("f2", "/*")),
                frag("F1", filter("f1", "a.F1", "") + filterMapping("f1", "/*"))));
        assertEquals(List.of("w", "f3", "f2", "f1"),
                m.filterMappings().stream().map(FilterMappingDef::filterName).toList());
    }

    @Test
    void fragmentFilterMappingsAreIgnoredForAFilterWebXmlMaps() throws Exception {
        var m = FragmentMerger.merge(web(filter("w", "a.W", "") + filterMapping("w", "/xml")), List.of(
                frag("F1", filterMapping("w", "/frag") + filter("g", "a.G", "") + filterMapping("g", "/g"))));
        assertEquals(List.of("w:/xml", "g:/g"),
                m.filterMappings().stream().map(fm -> fm.filterName() + ":" + fm.urlPattern()).toList());
    }

    // ---- listeners ----------------------------------------------------------------------------

    @Test
    void listenersWebXmlFirstThenFragmentsWithoutDuplicates() throws Exception {
        String l1 = "<listener><listener-class>a.L1</listener-class></listener>";
        String l2 = "<listener><listener-class>a.L2</listener-class></listener>";
        String l3 = "<listener><listener-class>a.L3</listener-class></listener>";
        var m = FragmentMerger.merge(web(l2), List.of(frag("F1", l1 + l2), frag("F2", l3 + l1)));
        assertEquals(List.of("a.L2", "a.L1", "a.L3"), m.listenerClasses());
    }

    // ---- keyed elements -----------------------------------------------------------------------

    private static String contextParam(String k, String v) {
        return "<context-param><param-name>" + k + "</param-name><param-value>" + v + "</param-value></context-param>";
    }

    @Test
    void contextParamsWebXmlWinsAndFragmentsAdd() throws Exception {
        var m = FragmentMerger.merge(web(contextParam("k", "xml")), List.of(
                frag("F1", contextParam("k", "1") + contextParam("a", "1")),
                frag("F2", contextParam("k", "2") + contextParam("a", "1") + contextParam("b", "2"))));
        assertEquals(Map.of("k", "xml", "a", "1", "b", "2"), m.contextParams());
        assertEquals(List.of("k", "a", "b"), List.copyOf(m.contextParams().keySet()));
    }

    @Test
    void contextParamConflictBetweenFragmentsFails() throws Exception {
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", contextParam("k", "1")), frag("F2", contextParam("k", "2")))));
        assertConflict(e, "<context-param>", "'k'", "F1", "F2");
    }

    private static String mime(String ext, String type) {
        return "<mime-mapping><extension>" + ext + "</extension><mime-type>" + type + "</mime-type></mime-mapping>";
    }

    @Test
    void mimeMappingsWebXmlWinsAdditiveAndConflicting() throws Exception {
        var m = FragmentMerger.merge(web(mime("foo", "x/xml")), List.of(
                frag("F1", mime("foo", "x/one") + mime("bar", "x/bar")), frag("F2", mime("baz", "x/baz"))));
        assertEquals(Map.of("foo", "x/xml", "bar", "x/bar", "baz", "x/baz"), m.mimeMappings());
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", mime("bar", "x/one")), frag("F2", mime("bar", "x/two")))));
        assertConflict(e, "<mime-mapping>", "'bar'", "F1", "F2");
    }

    private static String errorCode(int code, String location) {
        return "<error-page><error-code>" + code + "</error-code><location>" + location + "</location></error-page>";
    }

    private static String errorType(String type, String location) {
        return "<error-page><exception-type>" + type + "</exception-type><location>" + location
                + "</location></error-page>";
    }

    @Test
    void errorPagesByCodeAndTypeWebXmlWinsAndFragmentsAdd() throws Exception {
        var m = FragmentMerger.merge(web(errorCode(404, "/xml404")), List.of(
                frag("F1", errorCode(404, "/f404") + errorCode(500, "/f500")),
                frag("F2", errorType("a.Boom", "/boom") + errorCode(500, "/f500"))));
        assertEquals(List.of(new ErrorPageDef(404, null, "/xml404"), new ErrorPageDef(500, null, "/f500"),
                new ErrorPageDef(null, "a.Boom", "/boom")), m.errorPages());
    }

    @Test
    void errorPageConflictBetweenFragmentsFails() throws Exception {
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", errorType("a.Boom", "/one")), frag("F2", errorType("a.Boom", "/two")))));
        assertConflict(e, "<error-page>", "a.Boom", "F1", "F2");
    }

    private static String localeEncoding(String locale, String enc) {
        return "<locale-encoding-mapping-list><locale-encoding-mapping><locale>" + locale + "</locale><encoding>"
                + enc + "</encoding></locale-encoding-mapping></locale-encoding-mapping-list>";
    }

    @Test
    void localeEncodingMappingsWebXmlWinsAdditiveAndConflicting() throws Exception {
        var m = FragmentMerger.merge(web(localeEncoding("fr", "UTF-8")), List.of(
                frag("F1", localeEncoding("fr", "ISO-8859-1")), frag("F2", localeEncoding("ja", "Shift_JIS"))));
        assertEquals(Map.of("fr", "UTF-8", "ja", "Shift_JIS"), m.localeEncodingMappings());
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", localeEncoding("ja", "A")), frag("F2", localeEncoding("ja", "B")))));
        assertConflict(e, "<locale-encoding-mapping>", "'ja'", "F1", "F2");
    }

    // ---- welcome files ------------------------------------------------------------------------

    private static String welcome(String... files) {
        var sb = new StringBuilder("<welcome-file-list>");
        for (String f : files) sb.append("<welcome-file>").append(f).append("</welcome-file>");
        return sb.append("</welcome-file-list>").toString();
    }

    @Test
    void welcomeFilesOfWebXmlWin() throws Exception {
        var m = FragmentMerger.merge(web(welcome("index.xml")), List.of(frag("F1", welcome("index.frag"))));
        assertEquals(List.of("index.xml"), m.welcomeFiles());
    }

    @Test
    void welcomeFilesOfFragmentsAreConcatenatedInOrderWithoutDuplicates() throws Exception {
        var m = FragmentMerger.merge(web(""), List.of(
                frag("F1", welcome("a.html", "b.html")), frag("F2", welcome("b.html", "c.html"))));
        assertEquals(List.of("a.html", "b.html", "c.html"), m.welcomeFiles());
    }

    // ---- singletons ---------------------------------------------------------------------------

    private static String session(String inner) {
        return "<session-config>" + inner + "</session-config>";
    }

    @Test
    void singletonsComeFromWebXmlFirst() throws Exception {
        var m = FragmentMerger.merge(web(session("<session-timeout>10</session-timeout>"
                        + "<cookie-config><name>XML</name></cookie-config><tracking-mode>URL</tracking-mode>")
                        + "<request-character-encoding>UTF-8</request-character-encoding>"
                        + "<response-character-encoding>UTF-8</response-character-encoding>"
                        + "<default-context-path>/xml</default-context-path>"),
                List.of(frag("F1", session("<session-timeout>20</session-timeout>"
                        + "<cookie-config><name>FRAG</name></cookie-config><tracking-mode>COOKIE</tracking-mode>")
                        + "<request-character-encoding>ISO-8859-1</request-character-encoding>"
                        + "<response-character-encoding>ISO-8859-1</response-character-encoding>"
                        + "<default-context-path>/frag</default-context-path>"
                        + "<deny-uncovered-http-methods/>")));
        assertEquals(10, m.sessionTimeoutMinutes());
        assertEquals("XML", m.cookieConfig().name());
        assertEquals(Set.of(SessionTrackingMode.URL), m.trackingModes());
        assertEquals("UTF-8", m.requestCharacterEncoding());
        assertEquals("UTF-8", m.responseCharacterEncoding());
        assertEquals("/xml", m.defaultContextPath());
        assertTrue(m.denyUncoveredHttpMethods());
    }

    @Test
    void singletonsFallBackToTheFirstFragmentDeclaringThem() throws Exception {
        var m = FragmentMerger.merge(web(""), List.of(
                frag("F1", "<request-character-encoding>UTF-8</request-character-encoding>"),
                frag("F2", session("<session-timeout>20</session-timeout><tracking-mode>COOKIE</tracking-mode>")
                        + "<request-character-encoding>UTF-8</request-character-encoding>"
                        + "<default-context-path>/frag</default-context-path>")));
        assertEquals(20, m.sessionTimeoutMinutes());
        assertEquals(Set.of(SessionTrackingMode.COOKIE), m.trackingModes());
        assertEquals("UTF-8", m.requestCharacterEncoding());
        assertEquals("/frag", m.defaultContextPath());
        assertNull(m.responseCharacterEncoding());
        assertNull(m.cookieConfig());
        assertFalse(m.denyUncoveredHttpMethods());
    }

    @Test
    void conflictingSingletonsBetweenFragmentsFail() throws Exception {
        var timeout = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", session("<session-timeout>1</session-timeout>")),
                frag("F2", session("<session-timeout>2</session-timeout>")))));
        assertConflict(timeout, "<session-timeout>", "F1", "F2");
        var cookie = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", session("<cookie-config><name>A</name></cookie-config>")),
                frag("F2", session("<cookie-config><name>B</name></cookie-config>")))));
        assertConflict(cookie, "<cookie-config>", "F1", "F2");
        var modes = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", session("<tracking-mode>URL</tracking-mode>")),
                frag("F2", session("<tracking-mode>COOKIE</tracking-mode>")))));
        assertConflict(modes, "<tracking-mode>", "F1", "F2");
        var enc = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", "<response-character-encoding>A</response-character-encoding>"),
                frag("F2", "<response-character-encoding>B</response-character-encoding>"))));
        assertConflict(enc, "<response-character-encoding>", "F1", "F2");
        var path = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", "<default-context-path>/a</default-context-path>"),
                frag("F2", "<default-context-path>/b</default-context-path>"))));
        assertConflict(path, "<default-context-path>", "F1", "F2");
    }

    @Test
    void conflictingSingletonsAreResolvedByWebXml() throws Exception {
        var m = FragmentMerger.merge(web(session("<session-timeout>5</session-timeout>")), List.of(
                frag("F1", session("<session-timeout>1</session-timeout>")),
                frag("F2", session("<session-timeout>2</session-timeout>"))));
        assertEquals(5, m.sessionTimeoutMinutes());
    }

    @Test
    void cookieConfigIsMergedPerSubElement() throws Exception {
        var m = FragmentMerger.merge(web(session("<cookie-config><name>XML</name></cookie-config>")), List.of(
                frag("F1", session("<cookie-config><name>FRAG</name><http-only>true</http-only>"
                        + "<attribute><attribute-name>SameSite</attribute-name>"
                        + "<attribute-value>Lax</attribute-value></attribute></cookie-config>")),
                frag("F2", session("<cookie-config><domain>example.com</domain><http-only>true</http-only>"
                        + "</cookie-config>"))));
        var c = m.cookieConfig();
        assertEquals("XML", c.name());
        assertEquals(Boolean.TRUE, c.httpOnly());
        assertEquals("example.com", c.domain());
        assertEquals(Map.of("SameSite", "Lax"), c.attributes());
        assertNull(c.path());
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", session("<cookie-config><path>/a</path></cookie-config>")),
                frag("F2", session("<cookie-config><name>N</name><path>/b</path></cookie-config>")))));
        assertConflict(e, "<path> in <cookie-config>", "F1", "F2");
        var xml = FragmentMerger.merge(web(session("<cookie-config><path>/x</path></cookie-config>")), List.of(
                frag("F1", session("<cookie-config><path>/a</path></cookie-config>")),
                frag("F2", session("<cookie-config><path>/b</path></cookie-config>"))));
        assertEquals("/x", xml.cookieConfig().path());
    }

    // ---- security -----------------------------------------------------------------------------

    private static String constraint(String pattern) {
        return "<security-constraint><web-resource-collection><web-resource-name>r</web-resource-name>"
                + "<url-pattern>" + pattern + "</url-pattern></web-resource-collection></security-constraint>";
    }

    private static String login(String method) {
        return "<login-config><auth-method>" + method + "</auth-method></login-config>";
    }

    private static String role(String r) {
        return "<security-role><role-name>" + r + "</role-name></security-role>";
    }

    @Test
    void securityConstraintsAndRolesAreAdditive() throws Exception {
        var m = FragmentMerger.merge(web(constraint("/xml") + role("admin")), List.of(
                frag("F1", constraint("/one") + role("user") + role("admin")), frag("F2", constraint("/two"))));
        assertEquals(List.of("/xml", "/one", "/two"), m.securityConstraints().stream()
                .map(c -> c.collections().getFirst().urlPatterns().getFirst()).toList());
        assertEquals(List.of("admin", "user"), m.securityRoles());
    }

    @Test
    void loginConfigWebXmlWinsAndConflictingFragmentsFail() throws Exception {
        var m = FragmentMerger.merge(web(login("BASIC")), List.of(frag("F1", login("FORM")), frag("F2", login("DIGEST"))));
        assertEquals("BASIC", m.loginConfig().authMethod());
        assertEquals("FORM", FragmentMerger.merge(web(""), List.of(frag("F1", login("FORM")),
                frag("F2", login("FORM")))).loginConfig().authMethod());
        var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
                frag("F1", login("FORM")), frag("F2", login("DIGEST")))));
        assertConflict(e, "<login-config>", "F1", "F2");
    }

    // ---- descriptor-level rules ---------------------------------------------------------------

    @Test
    void resultIsAWebAppKeepingWebXmlAttributesAndAbsoluteOrdering() throws Exception {
        var xml = web(" metadata-complete=\"false\"",
                "<display-name>App</display-name><absolute-ordering><name>F1</name><others/></absolute-ordering>");
        var m = FragmentMerger.merge(xml, List.of(frag("F1", servlet("s", "a.S", ""))));
        assertEquals(WebAppDescriptor.Kind.WEB_APP, m.kind());
        assertEquals(List.of("F1", WebAppDescriptor.OTHERS), m.absoluteOrdering());
        assertEquals("App", m.displayName());
        assertEquals("6.1", m.version());
        assertFalse(m.metadataComplete());
        assertNull(m.fragmentName());
    }

    @Test
    void fragmentMetadataCompleteKeepsDescriptor() throws Exception {
        var m = FragmentMerger.merge(web(""), List.of(
                frag("F1", " metadata-complete=\"true\"", servlet("s", "a.S", "") + mapping("s", "/s"))));
        assertEquals(List.of("/s"), m.patternsFor("s"));
        assertFalse(m.metadataComplete(), "a fragment's metadata-complete only concerns its own jar");
    }

    @Test
    void webXmlMetadataCompleteIgnoresFragments() throws Exception {
        var xml = web(" metadata-complete=\"true\"", servlet("w", "a.W", ""));
        var m = FragmentMerger.merge(xml, List.of(
                frag("F1", servlet("f", "a.F", "") + contextParam("k", "v")),
                frag("F2", servlet("f", "a.Other", ""))));
        assertEquals(List.of("w"), m.servlets().stream().map(ServletDef::name).toList());
        assertTrue(m.contextParams().isEmpty());
        assertTrue(m.metadataComplete());
    }

    @Test
    void fragmentsExcludedByTheAbsoluteOrderingContributeNothing() throws Exception {
        var xml = web("<absolute-ordering><name>F2</name></absolute-ordering>");
        var all = List.of(frag("F1", servlet("s", "a.A", "") + contextParam("k", "one")),
                frag("F2", servlet("s", "a.B", "")));
        var m = FragmentMerger.merge(xml, FragmentOrderer.order(xml.absoluteOrdering(), all));
        assertEquals("a.B", servletNamed(m, "s").className());
        assertTrue(m.contextParams().isEmpty());
    }

    @Test
    void mergeKeepsTheGivenOrder() throws Exception {
        String l1 = "<listener><listener-class>a.L1</listener-class></listener>";
        String l2 = "<listener><listener-class>a.L2</listener-class></listener>";
        // F1 asks to come after F2, but FragmentMerger takes the list as already ordered.
        var f1 = frag("F1", "<ordering><after><name>F2</name></after></ordering>" + l1);
        var m = FragmentMerger.merge(web(""), List.of(f1, frag("F2", l2)));
        assertEquals(List.of("a.L1", "a.L2"), m.listenerClasses());
    }

    @Test
    void theResultIsACopyThatNeverChangesTheInput() throws Exception {
        for (var xml : List.of(web(servlet("s", "a.S", param("k", "v")) + welcome("index.html")),
                web(" metadata-complete=\"true\"", servlet("s", "a.S", param("k", "v")) + welcome("index.html")))) {
            for (var fragments : List.of(List.<Fragment>of(), List.of(frag("F1", servlet("s", "a.S", param("x", "1")))))) {
                var m = FragmentMerger.merge(xml, fragments);
                assertNotSame(xml, m);
                m.withDisplayName("changed").withWelcomeFiles(List.of("other.html")).withDefaultContextPath("/c")
                        .withMetadataComplete(!xml.metadataComplete());
                assertNull(xml.displayName());
                assertEquals(List.of("index.html"), xml.welcomeFiles());
                assertNull(xml.defaultContextPath());
                assertThrows(UnsupportedOperationException.class,
                        () -> servletNamed(m, "s").initParams().put("y", "2"));
                assertEquals(Map.of("k", "v"), servletNamed(xml, "s").initParams());
            }
        }
    }

    @Test
    void emptyWebXmlAndOneRichFragment() throws Exception {
        // The TCK pluggability shape: no web.xml, everything declared by one web-fragment.xml.
        var m = FragmentMerger.merge(WebAppDescriptor.empty(), List.of(frag("F1",
                servlet("s1", "a.S1", param("k", "v") + "<load-on-startup>1</load-on-startup>")
                        + servlet("s2", "a.S2", "") + mapping("s1", "/s1") + mapping("s2", "/s2")
                        + mapping("s2", "*.do")
                        + filter("f", "a.F", "") + "<filter-mapping><filter-name>f</filter-name>"
                        + "<servlet-name>s1</servlet-name></filter-mapping>"
                        + "<listener><listener-class>a.L</listener-class></listener>"
                        + session("<session-timeout>7</session-timeout><tracking-mode>COOKIE</tracking-mode>")
                        + errorCode(404, "/nf") + mime("foo", "x/foo") + welcome("home.html")
                        + contextParam("c", "1"))));
        assertEquals(WebAppDescriptor.Kind.WEB_APP, m.kind());
        assertEquals(List.of("s1", "s2"), m.servlets().stream().map(ServletDef::name).toList());
        assertEquals(1, servletNamed(m, "s1").loadOnStartup());
        assertEquals(Map.of("k", "v"), servletNamed(m, "s1").initParams());
        assertEquals(List.of("/s1"), m.patternsFor("s1"));
        assertEquals(List.of("/s2", "*.do"), m.patternsFor("s2"));
        assertEquals(List.of(new FilterMappingDef("f", null, "s1", Set.of(jakarta.servlet.DispatcherType.REQUEST))),
                m.filterMappings());
        assertEquals(List.of("a.L"), m.listenerClasses());
        assertEquals(7, m.sessionTimeoutMinutes());
        assertEquals(Set.of(SessionTrackingMode.COOKIE), m.trackingModes());
        assertEquals(List.of(new ErrorPageDef(404, null, "/nf")), m.errorPages());
        assertEquals(Map.of("foo", "x/foo"), m.mimeMappings());
        assertEquals(List.of("home.html"), m.welcomeFiles());
        assertEquals(Map.of("c", "1"), m.contextParams());
    }

    @Test
    void noFragmentsYieldsTheWebXmlContent() throws Exception {
        var xml = web(servlet("s", "a.S", param("k", "v")) + mapping("s", "/s") + contextParam("c", "1")
                + welcome("index.html"));
        var m = FragmentMerger.merge(xml, List.of());
        assertEquals(xml.servlets(), m.servlets());
        assertEquals(xml.servletMappings(), m.servletMappings());
        assertEquals(xml.contextParams(), m.contextParams());
        assertEquals(xml.welcomeFiles(), m.welcomeFiles());
    }
}
