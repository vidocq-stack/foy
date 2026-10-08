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
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
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
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Servlet 6.1 section 2.3.3.3 and the {@code ServletRequest.isAsyncSupported()} Javadoc: the flag is
 * recomputed on every dispatch (forward, include, async, error), restored when a forward or include
 * returns, and an async dispatch starts a new cycle.
 */
class AsyncSupportedDispatchTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    /** Reports {@code isAsyncSupported/startAsyncThrewIllegalState}. */
    private static String probe(HttpServletRequest req) {
        boolean supported = req.isAsyncSupported();
        boolean ise = false;
        if (!supported) {
            try { req.startAsync(); } catch (IllegalStateException e) { ise = true; }
        }
        return supported + "/" + ise;
    }

    private static HttpServlet reporter() {
        return new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write(probe(req));
            }
        };
    }

    private static HttpServlet forwarder() {
        return new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                req.getRequestDispatcher("/s2").forward(req, resp);
            }
        };
    }

    private static ServletDispatcher.Mapping map(String path, HttpServlet s, String name, boolean async) {
        return new ServletDispatcher.Mapping(UrlPatternMatcher.of(path), s, name, async);
    }

    @Test
    void forwardToNonAsyncServletDisablesAsync() throws Exception {
        start(List.of(map("/s1", forwarder(), "S1", true), map("/s2", reporter(), "S2", false)), List.of());
        assertEquals("false/true", get("/s1"));
    }

    @Test
    void includeToNonAsyncServletRestoresFlagOnReturn() throws Exception {
        HttpServlet s1 = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                resp.getWriter().write("before=" + req.isAsyncSupported() + ";");
                req.getRequestDispatcher("/s2").include(req, resp);
                resp.getWriter().write(";after=" + req.isAsyncSupported());
            }
        };
        start(List.of(map("/s1", s1, "S1", true), map("/s2", reporter(), "S2", false)), List.of());
        assertEquals("before=true;false/true;after=true", get("/s1"));
    }

    @Test
    void asyncDispatchToNonAsyncServletDisablesAsync() throws Exception {
        HttpServlet s1 = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                req.startAsync().dispatch("/s2");
            }
        };
        start(List.of(map("/s1", s1, "S1", true), map("/s2", reporter(), "S2", false)), List.of());
        assertEquals("false/true", get("/s1"));
    }

    @Test
    void asyncDispatchToAsyncServletStartsNewCycle() throws Exception {
        HttpServlet s1 = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                req.startAsync().dispatch("/s2");
            }
        };
        HttpServlet s2 = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                boolean supported = req.isAsyncSupported();
                var ac = req.startAsync();
                resp.getWriter().write(supported + "/" + req.isAsyncStarted());
                ac.complete();
            }
        };
        start(List.of(map("/s1", s1, "S1", true), map("/s2", s2, "S2", true)), List.of());
        assertEquals("true/true", get("/s1"));
    }

    @Test
    void forwardThroughNonAsyncForwardFilterDisablesAsync() throws Exception {
        Filter pass = (rq, rs, chain) -> chain.doFilter(rq, rs);
        var forwardFilter = new FilterMapping(UrlPatternMatcher.of("/s2"), pass, "F",
                EnumSet.of(DispatcherType.FORWARD), false);
        start(List.of(map("/s1", forwarder(), "S1", true), map("/s2", reporter(), "S2", true)),
                List.of(forwardFilter));
        assertEquals("false/true", get("/s1"));
    }

    @Test
    void forwardBetweenAsyncServletsKeepsAsync() throws Exception {
        start(List.of(map("/s1", forwarder(), "S1", true), map("/s2", reporter(), "S2", true)), List.of());
        assertEquals("true/false", get("/s1"));
    }

    @Test
    void namedDispatcherForwardAndIncludeToNonAsyncServlet() throws Exception {
        HttpServlet s1 = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                var d = req.getServletContext().getNamedDispatcher("S2");
                if (req.getParameter("fwd") != null) {
                    d.forward(req, resp);
                    return;
                }
                resp.getWriter().write("i:");
                d.include(req, resp);
                resp.getWriter().write(";after=" + req.isAsyncSupported());
            }
        };
        start(List.of(map("/s1", s1, "S1", true), map("/s2", reporter(), "S2", false)), List.of());
        assertEquals("i:false/true;after=true", get("/s1"));
        assertEquals("false/true", get("/s1?fwd=1"));
    }

    @Test
    void errorDispatchToAsyncPageKeepsAsync() throws Exception {
        HttpServlet s1 = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.sendError(500);
            }
        };
        errorPages = new io.vidocq.foy.internal.error.ErrorPageRegistry().register(500, "/err");
        start(List.of(map("/s1", s1, "S1", true), map("/err", reporter(), "ERR", true)), List.of());
        assertEquals("true/false", get("/s1"));
    }

    @Test
    void errorDispatchToNonAsyncPageDisablesAsync() throws Exception {
        HttpServlet s1 = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.sendError(500);
            }
        };
        errorPages = new io.vidocq.foy.internal.error.ErrorPageRegistry().register(500, "/err");
        start(List.of(map("/s1", s1, "S1", true), map("/err", reporter(), "ERR", false)), List.of());
        assertEquals("false/true", get("/s1"));
    }

    private io.vidocq.foy.internal.error.ErrorPageRegistry errorPages;

    private void start(List<ServletDispatcher.Mapping> mappings, List<FilterMapping> filters) {
        var ctx = new VidocqServletContext("/");
        if (errorPages != null) ctx.setErrorPages(errorPages);
        var bridge = new ChappeServletBridge(new ServletDispatcher(mappings),
                new FilterRegistry(filters), ctx, null, "/");
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
