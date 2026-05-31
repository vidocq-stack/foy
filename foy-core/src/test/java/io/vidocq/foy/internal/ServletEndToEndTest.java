package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
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

/**
 * Starts a real Chappe Server which dispatches to an HttpServlet via the bridge.
 * Bypass WebAppDiscovery (no CDI in this test) — we build the dispatcher
 * manuellement.
 */
class ServletEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private void startWith(io.vidocq.chappe.api.Handler handler) {
        var r = TestServerLauncher.start(handler);
        this.server = r.server;
        this.port = r.port;
    }

    @Test
    void helloServletServesGet() throws Exception {
        HttpServlet hello = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain;charset=utf-8");
                String name = req.getParameter("name");
                resp.getWriter().write("Hello, " + (name == null ? "world" : name));
            }
        };

        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/hello"), hello, "HelloServlet")));
        var bridge = new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/");

        startWith(bridge);

        assertEquals("Hello, world", body(get("http://127.0.0.1:" + port + "/hello")));
        assertEquals("Hello, alice", body(get("http://127.0.0.1:" + port + "/hello?name=alice")));
    }

    @Test
    void unmatchedPathReturns404() throws Exception {
        HttpServlet s = new HttpServlet() {};
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/known"), s, "K")));
        var bridge = new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/");

        startWith(bridge);

        HttpResponse<String> resp = get("http://127.0.0.1:" + port + "/unknown");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void prefixServletReceivesStrippedServletPathAndPathInfo() throws Exception {
        HttpServlet echo = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain;charset=utf-8");
                resp.getWriter().write(req.getServletPath() + "|" + req.getPathInfo());
            }
        };
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/api/*"), echo, "Echo")));
        var bridge = new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/");

        startWith(bridge);

        assertEquals("/api|/users/42",
                body(get("http://127.0.0.1:" + port + "/api/users/42")));
    }

    @Test
    void statusAndHeadersPropagated() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                resp.setStatus(418);
                resp.setHeader("X-Custom", "teapot");
            }
        };
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/t"), s, "T")));
        var bridge = new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/");

        startWith(bridge);

        HttpResponse<String> r = get("http://127.0.0.1:" + port + "/t");
        assertEquals(418, r.statusCode());
        assertEquals("teapot", r.headers().firstValue("X-Custom").orElse(null));
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private static String body(HttpResponse<String> r) {
        return r.body();
    }
}
