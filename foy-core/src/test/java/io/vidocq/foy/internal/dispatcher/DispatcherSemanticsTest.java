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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Servlet 6.1 chapter 9: parameters, attributes, paths and exceptions of forward and include. */
class DispatcherSemanticsTest {

    private static final String[] INCLUDE_ATTRS = {"request_uri", "context_path", "servlet_path", "path_info",
            "query_string", "mapping"};

    private Server server;
    private int port;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @FunctionalInterface
    interface Body {
        void run(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException;
    }

    private static HttpServlet servlet(Body body) {
        return new HttpServlet() {
            @Override protected void service(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                body.run(req, resp);
            }
        };
    }

    private static ServletDispatcher.Mapping map(String path, HttpServlet s, String name) {
        return new ServletDispatcher.Mapping(UrlPatternMatcher.of(path), s, name);
    }

    private static String values(HttpServletRequest req, String name) {
        String[] v = req.getParameterValues(name);
        return v == null ? "null" : String.join(",", v);
    }

    private static String attrs(HttpServletRequest req, String kind) {
        var out = new ArrayList<String>();
        for (String a : INCLUDE_ATTRS) {
            Object v = req.getAttribute("jakarta.servlet." + kind + "." + a);
            out.add(a + "=" + (v instanceof HttpServletMapping m ? m.getServletName() : v));
        }
        return String.join(",", out);
    }

    private static HttpServlet paramReporter() {
        return servlet((req, resp) -> resp.getWriter().write("[" + req.getParameter("testname") + "|"
                + values(req, "testname") + "|" + req.getParameterMap().get("testname").length + "]"));
    }

    // ---- Parameters (section 9.1.1) ----

    @Test
    void includeQueryParametersComeFirstAndRevertAfterTheInclude() throws Exception {
        start(map("/C", servlet((req, resp) -> {
            req.getRequestDispatcher("/T?testname=x").include(req, resp);
            resp.getWriter().write(req.getParameter("testname") + "|" + values(req, "testname"));
        }), "C"), map("/T", paramReporter(), "T"));
        assertEquals("[x|x,caller|2]caller|caller", get("/C?testname=caller"));
    }

    @Test
    void forwardQueryParametersComeFirst() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/T?testname=x").forward(req, resp)), "C"),
                map("/T", paramReporter(), "T"));
        assertEquals("[x|x,caller|2]", get("/C?testname=caller"));
    }

    @Test
    void postFormParametersParticipateAsOriginals() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/T?testname=x").include(req, resp)), "C"),
                map("/T", paramReporter(), "T"));
        assertEquals("[x|x,posted|2]", post("/C", "testname=posted"));
    }

    @Test
    void nestedIncludesStackParametersAndRestoreAttributes() throws Exception {
        start(map("/C", servlet((req, resp) -> {
                    req.getRequestDispatcher("/T1?a=1").include(req, resp);
                    resp.getWriter().write("|C:" + attrs(req, "include"));
                }), "C"),
                map("/T1", servlet((req, resp) -> {
                    req.getRequestDispatcher("/T2?a=2").include(req, resp);
                    resp.getWriter().write("|T1:" + req.getAttribute("jakarta.servlet.include.request_uri")
                            + ":" + values(req, "a"));
                }), "T1"),
                map("/T2", servlet((req, resp) -> resp.getWriter().write("T2:"
                        + req.getAttribute("jakarta.servlet.include.request_uri") + ":"
                        + req.getAttribute("jakarta.servlet.include.query_string") + ":" + values(req, "a"))), "T2"));
        assertEquals("T2:/T2:a=2:2,1,0|T1:/T1:1,0|C:request_uri=null,context_path=null,servlet_path=null,"
                + "path_info=null,query_string=null,mapping=null", get("/C?a=0"));
    }

    @Test
    void includeThenForwardSeesBothQueries() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/I?a=1").include(req, resp)), "C"),
                map("/I", servlet((req, resp) -> req.getRequestDispatcher("/F?a=2").forward(req, resp)), "I"),
                map("/F", servlet((req, resp) -> resp.getWriter().write(values(req, "a")
                        + "|" + req.getDispatcherType())), "F"));
        assertEquals("2,1,0|FORWARD", get("/C?a=0"));
    }

    // ---- Named dispatch (sections 9.3.1 and 9.4.2) ----

    private static HttpServlet pathReporter() {
        return servlet((req, resp) -> resp.getWriter().write(req.getDispatcherType() + "|" + req.getRequestURI()
                + "|" + req.getServletPath() + "|" + req.getPathInfo() + "|" + req.getQueryString()
                + "|" + attrs(req, "include") + "|" + attrs(req, "forward")));
    }

    private static final String NO_ATTRS = "request_uri=null,context_path=null,servlet_path=null,path_info=null,"
            + "query_string=null,mapping=null";

    @Test
    void namedIncludeSetsNoAttributeAndKeepsPaths() throws Exception {
        start(map("/C/*", servlet((req, resp) -> req.getServletContext().getNamedDispatcher("T").include(req, resp)),
                "C"), map("/T", pathReporter(), "T"));
        assertEquals("INCLUDE|/C/p|/C|/p|q=1|" + NO_ATTRS + "|" + NO_ATTRS, get("/C/p?q=1"));
    }

    @Test
    void namedForwardSetsNoAttributeAndKeepsPaths() throws Exception {
        start(map("/C/*", servlet((req, resp) -> req.getServletContext().getNamedDispatcher("T").forward(req, resp)),
                "C"), map("/T", pathReporter(), "T"));
        assertEquals("FORWARD|/C/p|/C|/p|q=1|" + NO_ATTRS + "|" + NO_ATTRS, get("/C/p?q=1"));
    }

    // ---- Forward attributes and paths (section 9.4.2) ----

    @Test
    void nestedForwardKeepsTheOriginalForwardAttributes() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/F1/x?a=1").forward(req, resp)), "C"),
                map("/F1/*", servlet((req, resp) -> req.getRequestDispatcher("/F2").forward(req, resp)), "F1"),
                map("/F2", servlet((req, resp) -> resp.getWriter().write(attrs(req, "forward")
                        + "|" + req.getRequestURI() + "|" + req.getHttpServletMapping().getServletName())), "F2"));
        assertEquals("request_uri=/C,context_path=,servlet_path=/C,path_info=null,query_string=q=0,mapping=C"
                + "|/F2|F2", get("/C?q=0"));
    }

    @Test
    void requestUrlInsideAForwardIsTheTargetUrl() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/T/p").forward(req, resp)), "C"),
                map("/T/*", servlet((req, resp) -> resp.getWriter().write(req.getRequestURL().toString())), "T"));
        assertEquals("http://127.0.0.1:" + port + "/T/p", get("/C"));
    }

    @Test
    void relativeDispatcherInsideAForwardResolvesAgainstTheTarget() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/dir/F").forward(req, resp)), "C"),
                map("/dir/F", servlet((req, resp) -> req.getRequestDispatcher("T").include(req, resp)), "F"),
                map("/dir/T", servlet((req, resp) -> resp.getWriter().write("dirT")), "T"));
        assertEquals("dirT", get("/C"));
    }

    @Test
    void relativeDispatcherFromTheDefaultServlet() throws Exception {
        start(map("/", servlet((req, resp) -> req.getRequestDispatcher("other").include(req, resp)), "default"),
                map("/dir/other", servlet((req, resp) -> resp.getWriter().write("other:"
                        + req.getAttribute("jakarta.servlet.include.servlet_path"))), "O"));
        assertEquals("other:/dir/other", get("/dir/page"));
    }

    // ---- Response closed after a forward (section 9.4) ----

    @Test
    void responseIsCommittedAndClosedAfterForward() throws Exception {
        start(map("/C", servlet((req, resp) -> {
                    req.getRequestDispatcher("/T").forward(req, resp);
                    events.add("committed=" + resp.isCommitted());
                    resp.setHeader("X-After", "1");
                    resp.getWriter().write("after");
                }), "C"),
                map("/T", servlet((req, resp) -> resp.getWriter().write("target")), "T"));
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/C")).GET().build());
        assertEquals("target", r.body());
        assertTrue(r.headers().firstValue("X-After").isEmpty(), "header set after the forward must be ignored");
        assertEquals(List.of("committed=true"), events);
    }

    @Test
    void responseStaysOpenWhenTheForwardTargetDispatchesAsync() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/T").forward(req, resp)), "C"),
                map("/T", servlet((req, resp) -> {
                    resp.getWriter().write(req.getDispatcherType() + ";");
                    if (req.getDispatcherType() == DispatcherType.FORWARD) req.startAsync(req, resp).dispatch();
                }), "T"));
        assertEquals("FORWARD;ASYNC;", get("/C"));
    }

    @Test
    void responseStaysOpenUntilCompleteWhenTheForwardTargetStartsAsync() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/T").forward(req, resp)), "C"),
                map("/T", servlet((req, resp) -> {
                    resp.getWriter().write("early;");
                    var ac = req.startAsync();
                    ac.start(() -> {
                        try {
                            Thread.sleep(50);
                            ac.getResponse().getWriter().write("late;");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        } finally {
                            ac.complete();
                        }
                    });
                }), "T"));
        assertEquals("early;late;", get("/C"));
    }

    /** A wrapper that only hands its content to the wrapped response when its writer is closed. */
    static final class BufferingResponse extends jakarta.servlet.http.HttpServletResponseWrapper {
        private final java.io.StringWriter buffer = new java.io.StringWriter();
        private final java.io.PrintWriter writer = new java.io.PrintWriter(buffer) {
            @Override public void close() {
                super.flush();
                try {
                    BufferingResponse.super.getWriter().write(buffer.toString());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                super.close();
            }
        };
        BufferingResponse(HttpServletResponse r) { super(r); }
        @Override public java.io.PrintWriter getWriter() { return writer; }
    }

    @Test
    void forwardClosesTheResponseObjectItWasGiven() throws Exception {
        start(map("/C", servlet((req, resp) -> req.getRequestDispatcher("/T").forward(req, new BufferingResponse(resp))),
                        "C"),
                map("/T", servlet((req, resp) -> resp.getWriter().write("wrapped")), "T"));
        assertEquals("wrapped", get("/C"));
    }

    @Test
    void forwardInsideAnIncludeHidesTheIncludeAttributes() throws Exception {
        start(map("/C", servlet((req, resp) -> {
                    req.getRequestDispatcher("/I").include(req, resp);
                    resp.getWriter().write("|after");
                }), "C"),
                map("/I", servlet((req, resp) -> req.getRequestDispatcher("/F").forward(req, resp)), "I"),
                map("/F", servlet((req, resp) -> {
                    boolean listed = java.util.Collections.list(req.getAttributeNames()).stream()
                            .anyMatch(n -> n.startsWith("jakarta.servlet.include."));
                    resp.getWriter().write(attrs(req, "include") + "|listed=" + listed
                            + "|fwd=" + req.getAttribute("jakarta.servlet.forward.request_uri"));
                }), "F"));
        assertEquals(NO_ATTRS + "|listed=false|fwd=/C|after", get("/C"));
    }

    @Test
    void forwardAttributesDoNotOutliveTheForward() throws Exception {
        start(map("/C", servlet((req, resp) -> {
                    req.getRequestDispatcher("/T").forward(req, resp);
                    events.add(attrs(req, "forward"));
                }), "C"),
                map("/T", servlet((req, resp) -> resp.getWriter().write(
                        String.valueOf(req.getAttribute("jakarta.servlet.forward.request_uri")))), "T"));
        assertEquals("/C", get("/C"));
        assertEquals(List.of(NO_ATTRS), events);
    }

    // ---- Exceptions propagate unchanged ----

    @Test
    void exceptionsFromTheTargetReachTheCallerUnchanged() throws Exception {
        var runtime = new IllegalStateException("boom");
        var io = new IOException("io");
        var servletEx = new ServletException("se");
        start(map("/C", servlet((req, resp) -> {
                    var sb = new StringBuilder();
                    for (String t : List.of("runtime", "io", "servlet")) {
                        for (String mode : List.of("include", "forward")) {
                            try {
                                var rd = req.getRequestDispatcher("/T?kind=" + t);
                                if (mode.equals("include")) rd.include(req, resp); else rd.forward(req, resp);
                                sb.append(mode).append(':').append(t).append("=none;");
                            } catch (IOException | ServletException | RuntimeException e) {
                                boolean same = e == runtime || e == io || e == servletEx;
                                sb.append(mode).append(':').append(t).append('=')
                                        .append(e.getClass().getSimpleName()).append(same ? "/same;" : "/other;");
                            }
                        }
                    }
                    events.add(sb.toString());
                }), "C"),
                map("/T", servlet((req, resp) -> {
                    switch (req.getParameter("kind")) {
                        case "runtime" -> throw runtime;
                        case "io" -> throw io;
                        default -> throw servletEx;
                    }
                }), "T"));
        get("/C");
        assertEquals(List.of("include:runtime=IllegalStateException/same;forward:runtime=IllegalStateException/same;"
                + "include:io=IOException/same;forward:io=IOException/same;"
                + "include:servlet=ServletException/same;forward:servlet=ServletException/same;"), events);
    }

    // ---- Filters for named dispatch (section 6.2.5) ----

    @Test
    void namedForwardRunsOnlyServletNameFiltersOfTheTarget() throws Exception {
        Filter byUrl = (req, res, chain) -> { events.add("byUrl"); chain.doFilter(req, res); };
        Filter byName = (req, res, chain) -> { events.add("byName"); chain.doFilter(req, res); };
        var filters = List.of(
                new FilterMapping(UrlPatternMatcher.of("/*"), byUrl, "byUrl", EnumSet.of(DispatcherType.FORWARD)),
                FilterMapping.forServletName("T", byName, "byName", EnumSet.of(DispatcherType.FORWARD), false));
        startWith(filters, map("/C", servlet((req, resp) ->
                        req.getServletContext().getNamedDispatcher("T").forward(req, resp)), "C"),
                map("/T", servlet((req, resp) -> resp.getWriter().write("T")), "T"));
        assertEquals("T", get("/C"));
        assertEquals(List.of("byName"), events);
    }

    // ---- helpers ----

    private void start(ServletDispatcher.Mapping... mappings) {
        startWith(List.of(), mappings);
    }

    private void startWith(List<FilterMapping> filters, ServletDispatcher.Mapping... mappings) {
        var ctx = new VidocqServletContext("/");
        var bridge = new ChappeServletBridge(new ServletDispatcher(List.of(mappings)),
                new FilterRegistry(filters), ctx, null, "/");
        var r = TestServerLauncherAccess.start(bridge);
        this.server = r.server();
        this.port = r.port();
    }

    private URI uri(String p) { return URI.create("http://127.0.0.1:" + port + p); }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).timeout(Duration.ofSeconds(5)).GET().build()).body();
    }

    private String post(String p, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build()).body();
    }
}
