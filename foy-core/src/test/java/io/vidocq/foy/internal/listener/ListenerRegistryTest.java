/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
    void contextDestroyedReachesEveryListenerEvenWhenOneThrows() {
        List<String> trace = new ArrayList<>();
        ServletContextListener a = new ServletContextListener() {
            @Override public void contextDestroyed(ServletContextEvent e) { trace.add("a"); }
        };
        ServletContextListener b = new ServletContextListener() {
            @Override public void contextDestroyed(ServletContextEvent e) {
                trace.add("b");
                throw new IllegalStateException("b fails");
            }
        };
        ServletContextListener c = new ServletContextListener() {
            @Override public void contextDestroyed(ServletContextEvent e) {
                trace.add("c");
                throw new Error("c fails");
            }
        };
        var reg = new ListenerRegistry();
        reg.registerAll(List.of(a, b, c));
        assertDoesNotThrow(() -> reg.fireContextDestroyed(new VidocqServletContext("/")));
        assertEquals(List.of("c", "b", "a"), trace, "reverse order, every listener called");
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
