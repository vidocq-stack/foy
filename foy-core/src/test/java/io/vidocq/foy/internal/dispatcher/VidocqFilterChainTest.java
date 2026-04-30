package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class VidocqFilterChainTest {

    @Test
    void emptyChainDelegatesToServlet() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        Servlet servlet = stubServlet(called);
        var chain = new VidocqFilterChain(List.of(), servlet);
        chain.doFilter(null, null);
        assertTrue(called.get());
    }

    @Test
    void emptyChainWithNullServletIsNoop() throws Exception {
        var chain = new VidocqFilterChain(List.of(), null);
        chain.doFilter(null, null); // no throw
    }

    @Test
    void singleFilterPassesThroughToServlet() throws Exception {
        List<String> trace = new ArrayList<>();
        Filter f = (req, res, c) -> { trace.add("f1-before"); c.doFilter(req, res); trace.add("f1-after"); };
        Servlet s = recordingServlet(trace, "servlet");
        new VidocqFilterChain(List.of(f), s).doFilter(null, null);
        assertEquals(List.of("f1-before", "servlet", "f1-after"), trace);
    }

    @Test
    void shortCircuitFilterSkipsServlet() throws Exception {
        AtomicBoolean servletCalled = new AtomicBoolean();
        Filter blocking = (req, res, c) -> {
            /* does not call c.doFilter */
        };
        var chain = new VidocqFilterChain(List.of(blocking), stubServlet(servletCalled));
        chain.doFilter(null, null);
        assertFalse(servletCalled.get());
    }

    @Test
    void multipleFiltersExecutedInOrder() throws Exception {
        List<String> trace = new ArrayList<>();
        Filter f1 = (req, res, c) -> { trace.add("f1>"); c.doFilter(req, res); trace.add("<f1"); };
        Filter f2 = (req, res, c) -> { trace.add("f2>"); c.doFilter(req, res); trace.add("<f2"); };
        Servlet s = recordingServlet(trace, "s");
        new VidocqFilterChain(List.of(f1, f2), s).doFilter(null, null);
        assertEquals(List.of("f1>", "f2>", "s", "<f2", "<f1"), trace);
    }

    @Test
    void filterExposedForDiagnostic() {
        Filter f = (req, res, c) -> {};
        var chain = new VidocqFilterChain(List.of(f), null);
        assertEquals(1, chain.filters().size());
    }

    private static Servlet stubServlet(AtomicBoolean called) {
        return new Servlet() {
            @Override public void init(ServletConfig c) {}
            @Override public ServletConfig getServletConfig() { return null; }
            @Override public void service(ServletRequest r, ServletResponse s) { called.set(true); }
            @Override public String getServletInfo() { return ""; }
            @Override public void destroy() {}
        };
    }

    private static Servlet recordingServlet(List<String> trace, String label) {
        return new Servlet() {
            @Override public void init(ServletConfig c) {}
            @Override public ServletConfig getServletConfig() { return null; }
            @Override public void service(ServletRequest r, ServletResponse s) { trace.add(label); }
            @Override public String getServletInfo() { return ""; }
            @Override public void destroy() {}
        };
    }

    @SuppressWarnings("unused")
    private static FilterChain wrap(VidocqFilterChain c) { return c; }
}
