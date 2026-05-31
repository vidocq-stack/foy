package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
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

import static org.junit.jupiter.api.Assertions.*;

/** E2E dispatcher: forward, include, error pages via real Chappe. */
class ServletDispatcherEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @Test
    void forwardReinitializesResponseAndExposesForwardAttributes() throws Exception {
        HttpServlet origin = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                resp.setContentType("text/plain");
                resp.getWriter().write("should be discarded");
                req.getRequestDispatcher("/target").forward(req, resp);
            }
        };
        HttpServlet target = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write(req.getServletPath() + "|"
                        + req.getAttribute("jakarta.servlet.forward.servlet_path") + "|"
                        + req.getDispatcherType());
            }
        };
        start(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/origin"), origin, "Origin"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/target"), target, "Target")
        ), new ErrorPageRegistry());

        HttpResponse<String> r = get("/origin");
        assertEquals(200, r.statusCode());
        assertEquals("/target|/origin|FORWARD", r.body());
    }

    @Test
    void includeMergesBodyAndPreservesPrimaryStatusAndHeaders() throws Exception {
        HttpServlet primary = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                resp.setStatus(201);
                resp.setHeader("X-Primary", "yes");
                resp.setContentType("text/plain");
                resp.getWriter().write("before|");
                req.getRequestDispatcher("/fragment").include(req, resp);
                resp.getWriter().write("|after");
            }
        };
        HttpServlet fragment = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                // Les setters d'état sont no-op dans l'IncludedResponse.
                resp.setStatus(999);
                resp.setHeader("X-Ignored", "nope");
                resp.getWriter().write(req.getAttribute("jakarta.servlet.include.servlet_path").toString()
                        + "(" + req.getDispatcherType() + ")");
            }
        };
        start(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/primary"), primary, "P"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/fragment"), fragment, "F")
        ), new ErrorPageRegistry());

        HttpResponse<String> r = get("/primary");
        assertEquals(201, r.statusCode());
        assertEquals("yes", r.headers().firstValue("X-Primary").orElse(null));
        assertTrue(r.headers().firstValue("X-Ignored").isEmpty());
        assertEquals("before|/fragment(INCLUDE)|after", r.body());
    }

    @Test
    void errorPageInvokedOnSendError() throws Exception {
        HttpServlet failing = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.sendError(404);
            }
        };
        HttpServlet notFound = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write("custom-404:"
                        + req.getAttribute("jakarta.servlet.error.status_code"));
            }
        };
        var pages = new ErrorPageRegistry().register(404, "/404-page");
        start(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/bad"), failing, "Bad"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/404-page"), notFound, "NF")
        ), pages);

        HttpResponse<String> r = get("/bad");
        assertEquals(404, r.statusCode());
        assertEquals("custom-404:404", r.body());
    }

    @Test
    void errorPageInvokedOnUnhandledException() throws Exception {
        HttpServlet boom = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                throw new IllegalStateException("oh no");
            }
        };
        HttpServlet trap = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                Throwable t = (Throwable) req.getAttribute("jakarta.servlet.error.exception");
                resp.getWriter().write("trapped:" + (t == null ? "?" : t.getMessage()));
            }
        };
        var pages = new ErrorPageRegistry().register(RuntimeException.class, "/trap");
        start(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/boom"), boom, "Boom"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/trap"), trap, "Trap")
        ), pages);

        HttpResponse<String> r = get("/boom");
        assertEquals(500, r.statusCode());
        assertEquals("trapped:oh no", r.body());
    }

    private void start(List<ServletDispatcher.Mapping> mappings, ErrorPageRegistry pages) {
        var ctx = new VidocqServletContext("/");
        ctx.setErrorPages(pages);
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(mappings), new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
