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
package io.vidocq.foy.internal.error;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end error-page dispatch (Servlet 6.1 section 10.9). */
class ErrorPageDispatchTest {

    static class TestException extends Exception {
        TestException() { super("test-exception-message"); }
    }

    private Server server;
    private int port;
    private final ErrorPageRegistry pages = new ErrorPageRegistry();

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @FunctionalInterface
    interface Thrower { void run(HttpServletRequest req, HttpServletResponse res) throws Exception; }

    private static HttpServlet thrower(Thrower t) {
        return new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse res)
                    throws ServletException, IOException {
                try { t.run(req, res); }
                catch (IOException | ServletException | RuntimeException e) { throw e; }
                catch (Exception e) { throw new ServletException(e); }
            }
        };
    }

    private static HttpServlet reporter() {
        return new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                var type = (Class<?>) req.getAttribute("jakarta.servlet.error.exception_type");
                res.getWriter().print("type=" + (type == null ? null : type.getSimpleName())
                        + ";msg=" + req.getAttribute("jakarta.servlet.error.message")
                        + ";qs=" + req.getAttribute("jakarta.servlet.error.query_string")
                        + ";code=" + req.getAttribute("jakarta.servlet.error.status_code"));
            }
        };
    }

    private void start(HttpServlet errorServlet, HttpServlet thrown) {
        var ctx = new VidocqServletContext("/");
        ctx.setErrorPages(pages);
        var bridge = new ChappeServletBridge(new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of("/t"),
                        thrown, "T", true),
                new ServletDispatcher.Mapping(io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of("/err"),
                        errorServlet, "ERR", true))),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncherAccess.start(bridge);
        this.server = r.server();
        this.port = r.port();
    }

    private HttpResponse<String> get(String p) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void statusErrorPageGetsTheMessage() throws Exception {
        pages.register(411, "/err");
        start(reporter(), thrower((q, r) -> r.sendError(411, "my msg")));
        var res = get("/t");
        assertEquals(411, res.statusCode());
        assertTrue(res.body().contains("msg=my msg"), res.body());
    }

    @Test
    void statusErrorPageWithoutMessageGetsEmptyString() throws Exception {
        pages.register(411, "/err");
        start(reporter(), thrower((q, r) -> r.sendError(411)));
        assertTrue(get("/t").body().contains("msg=;"));
    }

    @Test
    void wrappedExceptionMatchesTheRootCause() throws Exception {
        pages.register(TestException.class, "/err");
        start(reporter(), thrower((q, r) -> { throw new ServletException(new TestException()); }));
        var body = get("/t").body();
        assertTrue(body.contains("type=TestException"), body);
        assertTrue(body.contains("msg=test-exception-message"), body);
    }

    @Test
    void hierarchyMatch() throws Exception {
        pages.register(RuntimeException.class, "/err");
        start(reporter(), thrower((q, r) -> { throw new IllegalThreadStateException("x"); }));
        assertTrue(get("/t").body().contains("type=IllegalThreadStateException"));
    }

    @Test
    void nonServletExceptionCauseIsNotUnwrapped() throws Exception {
        pages.register(500, "/err");
        pages.register(TestException.class, "/never");
        start(reporter(), thrower((q, r) -> { throw new RuntimeException("outer", new TestException()); }));
        var body = get("/t").body();
        assertTrue(body.contains("type=RuntimeException;msg=outer"), body);
    }

    @Test
    void unmatchedExceptionGoesToStatus500PageWithOriginal() throws Exception {
        pages.register(500, "/err");
        start(reporter(), thrower((q, r) -> { throw new ServletException("wrapper", new TestException()); }));
        var body = get("/t").body();
        assertTrue(body.contains("type=ServletException;msg=wrapper;"), body);
    }

    @Test
    void queryStringAttribute() throws Exception {
        pages.register(404, "/err");
        start(reporter(), thrower((q, r) -> r.sendError(404)));
        assertTrue(get("/t?a=b").body().contains("qs=a=b"));
        assertTrue(get("/t").body().contains("qs=null"));
    }

    @Test
    void throwingErrorPageGivesPlain500() throws Exception {
        pages.register(404, "/err");
        start(thrower((q, r) -> { throw new IllegalStateException("page boom"); }),
                thrower((q, r) -> r.sendError(404)));
        var res = get("/t");
        assertEquals(500, res.statusCode());
        assertTrue(!res.body().contains("type="), res.body());
    }

    @Test
    void exceptionAfterCommitKeepsCommittedContentAndDispatchesNoPage() throws Exception {
        pages.register(500, "/err");
        pages.register(RuntimeException.class, "/err");
        start(reporter(), thrower((q, r) -> {
            r.getWriter().print("committed-content");
            r.flushBuffer();
            throw new IllegalStateException("late");
        }));
        var res = get("/t");
        assertEquals(200, res.statusCode());
        assertEquals("committed-content", res.body());
    }

    @Test
    void setStatusDoesNotDispatchTheErrorPage() throws Exception {
        pages.register(404, "/err");
        start(reporter(), thrower((q, r) -> { r.setStatus(404); r.getWriter().print("plain"); }));
        var res = get("/t");
        assertEquals(404, res.statusCode());
        assertEquals("plain", res.body());
    }

    @Test
    void exceptionInsideForwardReportsOriginalRequestUriAndServletName() throws Exception {
        pages.register(RuntimeException.class, "/err");
        var ctx = new VidocqServletContext("/");
        ctx.setErrorPages(pages);
        HttpServlet boom = thrower((q, r) -> { throw new IllegalStateException("fwd"); });
        HttpServlet fwd = thrower((q, r) -> q.getRequestDispatcher("/boom").forward(q, r));
        HttpServlet err = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                res.getWriter().print(req.getAttribute("jakarta.servlet.error.request_uri") + "|"
                        + req.getAttribute("jakarta.servlet.error.servlet_name"));
            }
        };
        var bridge = new ChappeServletBridge(new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of("/t"),
                        fwd, "FWD", true),
                new ServletDispatcher.Mapping(io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of("/boom"),
                        boom, "BOOM", true),
                new ServletDispatcher.Mapping(io.vidocq.foy.internal.dispatcher.UrlPatternMatcher.of("/err"),
                        err, "ERR", true))),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncherAccess.start(bridge);
        this.server = r.server();
        this.port = r.port();
        assertEquals("/t|FWD", get("/t").body());
    }
}
