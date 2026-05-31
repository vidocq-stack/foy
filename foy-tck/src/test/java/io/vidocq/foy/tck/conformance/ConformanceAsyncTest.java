package io.vidocq.foy.tck.conformance;

import io.vidocq.foy.tck.ServletTestHarness;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 conformance coverage §2.3.3.3 (async processing). */
class ConformanceAsyncTest {

    @Test
    void asyncContextCompleteFlushesResponseFromAnotherThread() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.start(() -> {
                    try {
                        Thread.sleep(20);
                        ((HttpServletResponse) ac.getResponse()).getWriter().write("async");
                        ac.complete();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        };
        try (var h = ServletTestHarness.builder().servlet("/a", s).start()) {
            assertEquals("async", h.get("/a").body());
        }
    }

    @Test
    void timeoutWithoutCompleteYields503() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(30);
            }
        };
        try (var h = ServletTestHarness.builder().servlet("/t", s).start()) {
            assertEquals(503, h.get("/t").statusCode());
        }
    }
}
