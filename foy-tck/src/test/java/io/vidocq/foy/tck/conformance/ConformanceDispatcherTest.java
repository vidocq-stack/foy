package io.vidocq.foy.tck.conformance;

import io.vidocq.foy.tck.ServletTestHarness;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Couverture conformance Servlet 6.1 §9 (RequestDispatcher) et §12 (url-pattern).
 */
class ConformanceDispatcherTest {

    @Test
    void exactUrlPatternMatchesOnlyItself() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("/foo", echo("foo"))
                .start()) {
            assertEquals(200, h.get("/foo").statusCode());
            assertEquals(404, h.get("/foo/").statusCode());
            assertEquals(404, h.get("/foobar").statusCode());
        }
    }

    @Test
    void prefixPatternMatchesSelfAndDescendants() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("/api/*", echo("api"))
                .start()) {
            assertEquals("api", h.get("/api").body());
            assertEquals("api", h.get("/api/users/42").body());
            assertEquals(404, h.get("/other").statusCode());
        }
    }

    @Test
    void extensionPatternMatchesAnyPathEndingWithExtension() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("*.jsp", echo("jsp"))
                .start()) {
            assertEquals("jsp", h.get("/deep/nested/page.jsp").body());
            assertEquals(404, h.get("/deep/nested/page.html").statusCode());
        }
    }

    @Test
    void defaultServletMatchesWhenNoOtherPatternDoes() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("/foo", echo("foo"))
                .servlet("/", echo("default"))
                .start()) {
            assertEquals("foo", h.get("/foo").body());
            assertEquals("default", h.get("/anything/else").body());
        }
    }

    @Test
    void forwardSetsJakartaServletForwardAttributes() throws Exception {
        HttpServlet origin = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                resp.setContentType("text/plain");
                req.getRequestDispatcher("/target").forward(req, resp);
            }
        };
        HttpServlet target = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write((String) req.getAttribute("jakarta.servlet.forward.servlet_path"));
            }
        };
        try (var h = ServletTestHarness.builder()
                .servlet("/origin", origin)
                .servlet("/target", target)
                .start()) {
            assertEquals("/origin", h.get("/origin").body());
        }
    }

    @Test
    void includeMergesBodyWithoutMutatingPrimaryHeaders() throws Exception {
        HttpServlet primary = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {
                resp.setContentType("text/plain");
                resp.setHeader("X-Primary", "kept");
                resp.getWriter().write("A|");
                req.getRequestDispatcher("/inc").include(req, resp);
                resp.getWriter().write("|Z");
            }
        };
        HttpServlet included = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setHeader("X-Included", "ignored");
                resp.getWriter().write("mid");
            }
        };
        try (var h = ServletTestHarness.builder()
                .servlet("/primary", primary)
                .servlet("/inc", included)
                .start()) {
            HttpResponse<String> r = h.get("/primary");
            assertEquals("A|mid|Z", r.body());
            assertEquals("kept", r.headers().firstValue("X-Primary").orElse(null));
            assertTrue(r.headers().firstValue("X-Included").isEmpty());
        }
    }

    private static HttpServlet echo(String label) {
        return new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write(label);
            }
        };
    }
}
