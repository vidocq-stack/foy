package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterMapping;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
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

class ServletFilterEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void filterAddsHeaderBeforeServletExecutes() throws Exception {
        HttpServlet hello = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write("hi");
            }
        };
        jakarta.servlet.Filter marker = (req, res, chain) -> {
            ((HttpServletResponse) res).setHeader("X-Filtered", "yes");
            chain.doFilter(req, res);
        };
        server = startServer(hello, marker, "/hello", "/*");

        HttpResponse<String> r = get("http://127.0.0.1:" + port + "/hello");
        assertEquals(200, r.statusCode());
        assertEquals("hi", r.body());
        assertEquals("yes", r.headers().firstValue("X-Filtered").orElse(null));
    }

    @Test
    void filterShortCircuitsWith401WhenUnauthorized() throws Exception {
        HttpServlet secret = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("SECRET");
            }
        };
        jakarta.servlet.Filter auth = (req, res, chain) -> {
            HttpServletRequest hreq = (HttpServletRequest) req;
            HttpServletResponse hres = (HttpServletResponse) res;
            if (!"letmein".equals(hreq.getParameter("token"))) {
                hres.setStatus(401);
                hres.setContentType("text/plain");
                hres.getWriter().write("Unauthorized");
                return;
            }
            chain.doFilter(req, res);
        };
        server = startServer(secret, auth, "/secret", "/secret");

        HttpResponse<String> denied = get("http://127.0.0.1:" + port + "/secret");
        assertEquals(401, denied.statusCode());
        assertEquals("Unauthorized", denied.body());

        HttpResponse<String> ok = get("http://127.0.0.1:" + port + "/secret?token=letmein");
        assertEquals(200, ok.statusCode());
        assertEquals("SECRET", ok.body());
    }

    @Test
    void twoFiltersExecuteInDeclarationOrder() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write((String) req.getAttribute("trace"));
            }
        };
        jakarta.servlet.Filter f1 = (req, res, chain) -> {
            req.setAttribute("trace", "f1");
            chain.doFilter(req, res);
        };
        jakarta.servlet.Filter f2 = (req, res, chain) -> {
            req.setAttribute("trace", req.getAttribute("trace") + "|f2");
            chain.doFilter(req, res);
        };

        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/t"), servlet, "S")));
        var reg = new FilterRegistry(List.of(
                FilterMapping.onRequest(UrlPatternMatcher.of("/*"), f1, "F1"),
                FilterMapping.onRequest(UrlPatternMatcher.of("/*"), f2, "F2")
        ));
        var bridge = new ChappeServletBridge(dispatcher, reg, new VidocqServletContext("/"), "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;

        assertEquals("f1|f2", get("http://127.0.0.1:" + port + "/t").body());
    }

    private Server startServer(HttpServlet servlet, jakarta.servlet.Filter filter,
                               String servletPattern, String filterPattern) {
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of(servletPattern), servlet, "S")));
        var reg = new FilterRegistry(List.of(
                FilterMapping.onRequest(UrlPatternMatcher.of(filterPattern), filter, "F")));
        var bridge = new ChappeServletBridge(dispatcher, reg, new VidocqServletContext("/"), "/");
        var r = TestServerLauncher.start(bridge);
        this.port = r.port;
        return r.server;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
