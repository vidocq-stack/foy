package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import jakarta.servlet.AsyncContext;
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

class ServletAsyncEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @Test
    void asyncWriteFromAnotherThread() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.start(() -> {
                    try {
                        Thread.sleep(30);
                        HttpServletResponse r = (HttpServletResponse) ac.getResponse();
                        r.setContentType("text/plain");
                        r.getWriter().write("async-ok");
                        ac.complete();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        };
        start(s, "/a");

        HttpResponse<String> r = get("/a");
        assertEquals(200, r.statusCode());
        assertEquals("async-ok", r.body());
    }

    @Test
    void asyncTimeoutYields503() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(50);
                // Volontairement, rien n'appelle complete() — laisser expirer.
            }
        };
        start(s, "/timeout");

        HttpResponse<String> r = get("/timeout");
        assertEquals(503, r.statusCode());
    }

    @Test
    void asyncDispatchRedirectsInternally() throws Exception {
        HttpServlet origin = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.dispatch("/target");
            }
        };
        HttpServlet target = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write("target:" + req.getDispatcherType());
            }
        };
        startMany(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/origin"), origin, "O"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/target"), target, "T")
        ));

        HttpResponse<String> r = get("/origin");
        assertEquals(200, r.statusCode());
        assertEquals("target:ASYNC", r.body());
    }

    @Test
    void isAsyncStartedReportsTrue() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                resp.setContentType("text/plain");
                resp.getWriter().write(Boolean.toString(req.isAsyncStarted())
                        + "/" + Boolean.toString(req.isAsyncSupported()));
                ac.complete();
            }
        };
        start(s, "/status");

        HttpResponse<String> r = get("/status");
        assertEquals("true/true", r.body());
    }

    private void start(HttpServlet servlet, String pattern) {
        startMany(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of(pattern), servlet, "S")));
    }

    private void startMany(List<ServletDispatcher.Mapping> mappings) {
        var ctx = new VidocqServletContext("/");
        var bridge = new ChappeServletBridge(new ServletDispatcher(mappings),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private HttpResponse<String> get(String p) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
