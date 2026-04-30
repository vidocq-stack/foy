package io.vidocq.foy.internal.listener;

import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListenerRegistryTest {

    @Test
    void registerAddsToMultipleTypedLists() {
        class MultiListener implements ServletContextListener, HttpSessionListener {
            @Override public void contextInitialized(ServletContextEvent e) {}
            @Override public void sessionCreated(HttpSessionEvent e) {}
        }
        var reg = new ListenerRegistry();
        reg.register(new MultiListener());
        assertEquals(1, reg.contextListeners().size());
        assertEquals(1, reg.sessionListeners().size());
    }

    @Test
    void contextInitializedAndDestroyedFireInCorrectOrder() {
        List<String> trace = new ArrayList<>();
        ServletContextListener a = new ServletContextListener() {
            @Override public void contextInitialized(ServletContextEvent e) { trace.add("a-init"); }
            @Override public void contextDestroyed(ServletContextEvent e) { trace.add("a-destroy"); }
        };
        ServletContextListener b = new ServletContextListener() {
            @Override public void contextInitialized(ServletContextEvent e) { trace.add("b-init"); }
            @Override public void contextDestroyed(ServletContextEvent e) { trace.add("b-destroy"); }
        };
        var reg = new ListenerRegistry();
        reg.register(a);
        reg.register(b);
        var ctx = new VidocqServletContext("/");
        reg.fireContextInitialized(ctx);
        reg.fireContextDestroyed(ctx);
        assertEquals(List.of("a-init", "b-init", "b-destroy", "a-destroy"), trace);
    }

    @Test
    void requestInitializedFiresForAllListeners() {
        int[] counter = {0};
        ServletRequestListener l = new ServletRequestListener() {
            @Override public void requestInitialized(ServletRequestEvent e) { counter[0]++; }
            @Override public void requestDestroyed(ServletRequestEvent e) { counter[0]++; }
        };
        var reg = new ListenerRegistry();
        reg.register(l);
        reg.register(l);
        reg.fireRequestInitialized(new VidocqServletContext("/"), null);
        assertEquals(2, counter[0]);
    }

    @Test
    void emptyRegistryIsNoop() {
        var reg = new ListenerRegistry();
        reg.fireContextInitialized(new VidocqServletContext("/"));
        reg.fireSessionCreated(null);
        reg.fireRequestInitialized(new VidocqServletContext("/"), null);
        // no throw
    }

    @Test
    void registerAllRegistersEachListener() {
        var a = new ServletContextListener() {};
        var b = new ServletContextListener() {};
        var reg = new ListenerRegistry();
        reg.registerAll(List.of(a, b));
        assertEquals(2, reg.contextListeners().size());
    }
}
