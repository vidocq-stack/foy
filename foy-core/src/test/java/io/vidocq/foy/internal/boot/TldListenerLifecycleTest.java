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

package io.vidocq.foy.internal.boot;

import io.vidocq.foy.spi.jsp.JspEngine;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServlet;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TldListenerLifecycleTest {
    @Test
    void invalidFactoriesFailDeploymentAndStillCloseTheEngine() {
        var events = new ArrayList<String>();
        var descriptor = new JspEngine.Listener(TestListener.class, () -> null);
        assertThrows(NullPointerException.class, () -> WebAppDeployer.deploy(WebAppModel.builder("/tld").build(),
                DeployOptions.defaults(getClass().getClassLoader()), ModuleLayer.boot(), engine(List.of(descriptor), events)));
        assertEquals(List.of("close"), events);
    }

    @Test
    void engineListenersInitializeOnceWithRestrictedConfigurationAndDestroyBeforeClose() {
        var events = new ArrayList<String>();
        var created = new AtomicInteger();
        var descriptor = new JspEngine.Listener(TestListener.class, () -> {
            created.incrementAndGet(); return new TestListener(events, true);
        });
        var model = WebAppModel.builder("/tld").build();
        try (var deployed = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()),
                ModuleLayer.boot(), engine(List.of(descriptor, descriptor), events))) {
            assertEquals(1, created.get());
            assertEquals(List.of("init"), events);
            assertEquals(1, deployed.listeners().contextListeners().size());
        }
        assertEquals(List.of("init", "destroy", "close"), events);
    }

    @Test
    void descriptorListenerTakesPrecedenceOverTheSameTldListenerClass() {
        var events = new ArrayList<String>();
        var model = WebAppModel.builder("/tld").listener(new WebAppModel.ListenerDecl(
                TestListener.class, () -> new TestListener(events, false))).build();
        var descriptor = new JspEngine.Listener(TestListener.class, () -> { throw new AssertionError("Duplicate listener constructed"); });
        try (var ignored = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()),
                ModuleLayer.boot(), engine(List.of(descriptor), events))) {
            assertEquals(List.of("init"), events);
        }
        assertEquals(List.of("init", "destroy", "close"), events);
    }

    private static JspEngine engine(List<JspEngine.Listener> descriptors, List<String> events) {
        return (context, layer) -> new JspEngine.Application() {
            public Servlet servlet() { return new HttpServlet() { }; }
            public Servlet servletFor(String path) { return new HttpServlet() { }; }
            public List<JspEngine.Listener> listeners() { return descriptors; }
            public void close() { events.add("close"); }
        };
    }

    public static final class TestListener implements ServletContextListener {
        private final List<String> events;
        private final boolean restricted;
        TestListener(List<String> events, boolean restricted) { this.events = events; this.restricted = restricted; }
        public void contextInitialized(ServletContextEvent event) {
            events.add("init");
            var context = event.getServletContext();
            context.setAttribute("tld", "ready");
            if (restricted) assertThrows(UnsupportedOperationException.class,
                    () -> context.addServlet("forbidden", new HttpServlet() { }));
            else assertNotNull(context.addServlet("allowed", new HttpServlet() { }));
        }
        public void contextDestroyed(ServletContextEvent event) { events.add("destroy"); }
    }
}
