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
package io.vidocq.foy.internal.dispatcher;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Servlet 6.1 section 12.2 and the {@code HttpServletMapping} Javadoc. */
class HttpServletMappingTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    private static String describe(HttpServletMapping m) {
        return "matchValue=" + m.getMatchValue() + ", pattern=" + m.getPattern()
                + ", servletName=" + m.getServletName() + ", mappingMatch=" + m.getMappingMatch();
    }

    private static String attr(HttpServletRequest req, String name) {
        return req.getAttribute(name) instanceof HttpServletMapping m ? describe(m) : "null";
    }

    private static HttpServlet reporter() {
        return new HttpServlet() {
            @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write(describe(req.getHttpServletMapping())
                        + "|sp=" + req.getServletPath() + "|pi=" + req.getPathInfo()
                        + "|fwd=" + attr(req, "jakarta.servlet.forward.mapping")
                        + "|inc=" + attr(req, "jakarta.servlet.include.mapping")
                        + "|async=" + attr(req, "jakarta.servlet.async.mapping"));
            }
        };
    }

    private static HttpServlet dispatcher(String mode, String target) {
        return new HttpServlet() {
            @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                try {
                    var rd = mode.startsWith("named") ? req.getServletContext().getNamedDispatcher(target)
                            : req.getRequestDispatcher(target);
                    if (mode.endsWith("include")) {
                        resp.getWriter().write(describe(req.getHttpServletMapping()) + "#");
                        rd.include(req, resp);
                    } else if (mode.equals("async")) {
                        req.startAsync().dispatch(target);
                    } else {
                        rd.forward(req, resp);
                    }
                } catch (jakarta.servlet.ServletException e) {
                    throw new IOException(e);
                }
            }
        };
    }

    private static ServletDispatcher.Mapping map(String path, HttpServlet s, String name) {
        return new ServletDispatcher.Mapping(UrlPatternMatcher.of(path), s, name);
    }

    @Test
    void exactMatch() throws Exception {
        start(map("/TestServlet", reporter(), "TestServlet"));
        assertEquals("matchValue=TestServlet, pattern=/TestServlet, servletName=TestServlet, mappingMatch=EXACT"
                + "|sp=/TestServlet|pi=null|fwd=null|inc=null|async=null", get("/TestServlet"));
    }

    @Test
    void extensionMatch() throws Exception {
        start(map("*.ts", reporter(), "Ext"));
        assertEquals("matchValue=a, pattern=*.ts, servletName=Ext, mappingMatch=EXTENSION"
                + "|sp=/a.ts|pi=null|fwd=null|inc=null|async=null", get("/a.ts"));
    }

    @Test
    void pathMatch() throws Exception {
        start(map("/foo/*", reporter(), "P"));
        assertEquals("matchValue=bar, pattern=/foo/*, servletName=P, mappingMatch=PATH"
                + "|sp=/foo|pi=/bar|fwd=null|inc=null|async=null", get("/foo/bar"));
    }

    @Test
    void defaultMatchHasWholePathAsServletPath() throws Exception {
        start(map("/", reporter(), "defaultServlet"));
        assertEquals("matchValue=, pattern=/, servletName=defaultServlet, mappingMatch=DEFAULT"
                + "|sp=/default|pi=null|fwd=null|inc=null|async=null", get("/default"));
    }

    @Test
    void contextRootMatchBeatsDefault() throws Exception {
        start(map("/", reporter(), "def"), map("", reporter(), "root"));
        assertEquals("matchValue=, pattern=, servletName=root, mappingMatch=CONTEXT_ROOT"
                + "|sp=|pi=/|fwd=null|inc=null|async=null", get("/"));
    }

    @Test
    void forwardExposesTargetMappingAndForwardAttribute() throws Exception {
        start(map("/F", dispatcher("forward", "/a.ts"), "F"), map("*.ts", reporter(), "Ext"));
        assertEquals("matchValue=a, pattern=*.ts, servletName=Ext, mappingMatch=EXTENSION"
                + "|sp=/a.ts|pi=null|fwd=matchValue=F, pattern=/F, servletName=F, mappingMatch=EXACT"
                + "|inc=null|async=null", get("/F"));
    }

    @Test
    void forwardToDefaultServlet() throws Exception {
        start(map("/F", dispatcher("forward", "/"), "F"), map("/", reporter(), "defaultServlet"));
        assertEquals("matchValue=, pattern=/, servletName=defaultServlet, mappingMatch=DEFAULT"
                + "|sp=/|pi=null|fwd=matchValue=F, pattern=/F, servletName=F, mappingMatch=EXACT"
                + "|inc=null|async=null", get("/F"));
    }

    @Test
    void includeKeepsCallerMappingAndSetsIncludeAttribute() throws Exception {
        start(map("/I", dispatcher("include", "/T"), "I"), map("/T", reporter(), "T"));
        assertEquals("matchValue=I, pattern=/I, servletName=I, mappingMatch=EXACT#"
                + "matchValue=I, pattern=/I, servletName=I, mappingMatch=EXACT"
                + "|sp=/I|pi=null|fwd=null|inc=matchValue=T, pattern=/T, servletName=T, mappingMatch=EXACT"
                + "|async=null", get("/I"));
    }

    @Test
    void namedForwardKeepsCallerMappingAndSetsNoMappingAttribute() throws Exception {
        start(map("/N", dispatcher("named", "T"), "N"), map("/T", reporter(), "T"));
        String body = get("/N");
        assertTrue(body.startsWith("matchValue=N, pattern=/N, servletName=N, mappingMatch=EXACT|"), body);
        assertTrue(body.endsWith("|fwd=null|inc=null|async=null"), body);
    }

    @Test
    void namedIncludeKeepsCallerMappingAndSetsNoMappingAttribute() throws Exception {
        start(map("/N", dispatcher("namedinclude", "T"), "N"), map("/T", reporter(), "T"));
        String body = get("/N");
        assertTrue(body.startsWith("matchValue=N, pattern=/N, servletName=N, mappingMatch=EXACT#"
                + "matchValue=N, pattern=/N, servletName=N, mappingMatch=EXACT|"), body);
        assertTrue(body.endsWith("|fwd=null|inc=null|async=null"), body);
    }

    @Test
    void asyncDispatchExposesTargetMapping() throws Exception {
        start(map("/A", dispatcher("async", "/TestServlet"), "A"), map("/TestServlet", reporter(), "TestServlet"));
        assertEquals("matchValue=TestServlet, pattern=/TestServlet, servletName=TestServlet, mappingMatch=EXACT"
                + "|sp=/TestServlet|pi=null|fwd=null|inc=null|async=null", get("/A"));
    }

    @Test
    void servletPathAndPrecedenceUnits() {
        var def = new ServletDispatcher.Mapping(UrlPatternMatcher.of("/"), reporter(), "d");
        var root = new ServletDispatcher.Mapping(UrlPatternMatcher.of(""), reporter(), "r");
        assertEquals("/x/y", DispatchResolver.servletPathFor(def, "/x/y"));
        assertEquals(null, DispatchResolver.pathInfoFor(def, "/x/y", "/x/y"));
        assertEquals("", DispatchResolver.servletPathFor(root, "/"));
        assertEquals("/", DispatchResolver.pathInfoFor(root, "/", ""));
        assertTrue(UrlPatternMatcher.of("").precedence() < UrlPatternMatcher.of("/").precedence());
        assertTrue(UrlPatternMatcher.of("").precedence() < UrlPatternMatcher.of("/a/*").precedence());
        assertTrue(UrlPatternMatcher.of("").precedence() < UrlPatternMatcher.of("*.x").precedence());
    }

    private void start(ServletDispatcher.Mapping... mappings) {
        var ctx = new VidocqServletContext("/");
        var bridge = new ChappeServletBridge(new ServletDispatcher(List.of(mappings)),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncherAccess.start(bridge);
        this.server = r.server();
        this.port = r.port();
    }

    private String get(String p) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();
    }
}
