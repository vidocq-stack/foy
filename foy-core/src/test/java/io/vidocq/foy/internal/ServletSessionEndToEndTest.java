package io.vidocq.foy.internal;

import io.vidocq.foy.spi.session.SessionStore;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServletSessionEndToEndTest {

    private Server server;
    private int port;
    private SessionManager sessionManager;
    private VidocqServletContext ctx;

    @BeforeEach
    void setUp() {
        this.ctx = new VidocqServletContext("/");
        this.sessionManager = new SessionManager(new InMemorySessionStore(), ctx, 1800);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void newSessionEmitsSetCookieJsessionid() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                HttpSession session = req.getSession();
                resp.setContentType("text/plain");
                resp.getWriter().write(session.getId());
            }
        };
        startServer(s, "/session");

        HttpResponse<String> r = get("http://127.0.0.1:" + port + "/session", null);
        assertEquals(200, r.statusCode());
        String setCookie = r.headers().firstValue("set-cookie").orElse(null);
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("JSESSIONID=" + r.body() + ";"), setCookie);
        assertTrue(setCookie.contains("HttpOnly"));
    }

    @Test
    void sessionAttributePersistsBetweenRequests() throws Exception {
        HttpServlet counter = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                HttpSession session = req.getSession();
                Integer v = (Integer) session.getAttribute("count");
                int next = (v == null ? 0 : v) + 1;
                session.setAttribute("count", next);
                resp.setContentType("text/plain");
                resp.getWriter().write(Integer.toString(next));
            }
        };
        startServer(counter, "/count");

        HttpResponse<String> r1 = get("http://127.0.0.1:" + port + "/count", null);
        assertEquals("1", r1.body());
        String sid = extractSessionId(r1);
        assertNotNull(sid);

        HttpResponse<String> r2 = get("http://127.0.0.1:" + port + "/count", sid);
        assertEquals("2", r2.body());
        assertTrue(r2.headers().firstValue("set-cookie").isEmpty(),
                "no new Set-Cookie expected when session already known");

        HttpResponse<String> r3 = get("http://127.0.0.1:" + port + "/count", sid);
        assertEquals("3", r3.body());
    }

    @Test
    void invalidateClearsSession() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if ("invalidate".equals(req.getParameter("op"))) {
                    req.getSession().invalidate();
                    resp.getWriter().write("bye");
                    return;
                }
                HttpSession ses = req.getSession();
                ses.setAttribute("k", "v");
                resp.getWriter().write(ses.getId());
            }
        };
        startServer(s, "/s");

        HttpResponse<String> r1 = get("http://127.0.0.1:" + port + "/s", null);
        String sid = extractSessionId(r1);
        assertNotNull(sid);

        HttpResponse<String> drop = get("http://127.0.0.1:" + port + "/s?op=invalidate", sid);
        assertEquals("bye", drop.body());

        // Next request with the (now invalid) JSESSIONID creates a new session.
        HttpResponse<String> r2 = get("http://127.0.0.1:" + port + "/s", sid);
        String newSid = extractSessionId(r2);
        assertNotNull(newSid);
        assertNotEquals(sid, newSid);
    }

    @Test
    void getSessionFalseReturnsNullWhenNoSession() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                HttpSession ses = req.getSession(false);
                resp.setContentType("text/plain");
                resp.getWriter().write(ses == null ? "none" : ses.getId());
            }
        };
        startServer(s, "/maybe");

        HttpResponse<String> r = get("http://127.0.0.1:" + port + "/maybe", null);
        assertEquals("none", r.body());
        assertTrue(r.headers().firstValue("set-cookie").isEmpty());
    }

    private void startServer(HttpServlet servlet, String pattern) {
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of(pattern), servlet, "S")));
        var bridge = new ChappeServletBridge(
                dispatcher, new FilterRegistry(List.of()), ctx, sessionManager, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private static String extractSessionId(HttpResponse<?> r) {
        return r.headers().firstValue("set-cookie")
                .map(h -> {
                    int eq = h.indexOf('=');
                    int semi = h.indexOf(';', eq);
                    return h.substring(eq + 1, semi < 0 ? h.length() : semi);
                })
                .orElse(null);
    }

    private static HttpResponse<String> get(String url, String jsessionid) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3)).GET();
        if (jsessionid != null) b.header("Cookie", "JSESSIONID=" + jsessionid);
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
