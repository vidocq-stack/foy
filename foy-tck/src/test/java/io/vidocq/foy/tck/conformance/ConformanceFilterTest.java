package io.vidocq.foy.tck.conformance;

import io.vidocq.foy.tck.ServletTestHarness;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Couverture conformance Servlet 6.1 §6 (Filters). */
class ConformanceFilterTest {

    @Test
    void filterMayShortCircuitServletInvocation() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("servlet-reached");
            }
        };
        jakarta.servlet.Filter guard = (req, res, chain) -> {
            HttpServletResponse h = (HttpServletResponse) res;
            h.setStatus(401);
            h.getWriter().write("blocked");
        };
        try (var h = ServletTestHarness.builder()
                .servlet("/r", s).filter("/r", guard).start()) {
            var r = h.get("/r");
            assertEquals(401, r.statusCode());
            assertEquals("blocked", r.body());
        }
    }

    @Test
    void chainOfFiltersExecutesInDeclarationOrder() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write((String) req.getAttribute("trace"));
            }
        };
        jakarta.servlet.Filter f1 = (req, res, chain) -> {
            req.setAttribute("trace", "1");
            chain.doFilter(req, res);
        };
        jakarta.servlet.Filter f2 = (req, res, chain) -> {
            req.setAttribute("trace", req.getAttribute("trace") + "-2");
            chain.doFilter(req, res);
        };
        try (var h = ServletTestHarness.builder()
                .servlet("/t", s).filter("/*", f1).filter("/*", f2).start()) {
            assertEquals("1-2", h.get("/t").body());
        }
    }
}
