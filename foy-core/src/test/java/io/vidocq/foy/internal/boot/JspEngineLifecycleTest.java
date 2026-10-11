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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JspEngineLifecycleTest {
    @Test
    void deploymentWithoutAnEngineStillAcceptsJspFileDeclarations() {
        var model = WebAppModel.builder("/pages").servlet(new WebAppModel.ServletDecl("named", null, null,
                List.of("/named"), java.util.Map.of(), 1, false, null, null, true, "/hello.jsp")).build();
        try (var deployed = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()), ModuleLayer.boot(), null)) {
            assertTrue(deployed.initializedServlets().isEmpty());
            assertNull(deployed.servletContext().getServletRegistration("jsp"));
        }
    }

    @Test
    void engineIsReadyBeforeInitializersAndClosesAfterServlets() {
        var events = new ArrayList<String>();
        JspEngine engine = (context, layer) -> {
            events.add("open");
            return new JspEngine.Application() {
                public Servlet servlet() { return new CountingServlet("default", events); }
                public Servlet servletFor(String path) { events.add("factory:" + path); return new CountingServlet("alias", events); }
                public void close() { events.add("close"); }
            };
        };
        var model = WebAppModel.builder("/pages").initializer((types, context) -> {
            assertEquals(List.of("open"), events);
            var registration = context.addJspFile("alias", "/hello.jsp");
            assertNotNull(registration); registration.addMapping("/alias");
        }).build();
        var deployed = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()), ModuleLayer.boot(), engine);
        assertEquals(java.util.Set.of("*.jsp", "*.jspx"), java.util.Set.copyOf(deployed.servletContext().getServletRegistration("jsp").getMappings()));
        assertEquals(2, deployed.initializedServlets().size());
        assertThrows(IllegalStateException.class, () -> deployed.servletContext().addJspFile("late", "/hello.jsp"));
        deployed.close(); deployed.close();
        assertEquals("close", events.getLast());
        assertEquals(1, events.stream().filter("close"::equals).count());
        assertTrue(events.contains("destroy:alias")); assertTrue(events.contains("destroy:default"));
    }

    @Test
    void failureDuringInitializationStillClosesTheEngine() {
        int[] closed = {0};
        JspEngine engine = (context, layer) -> new JspEngine.Application() {
            public Servlet servlet() { return new HttpServlet() { }; }
            public Servlet servletFor(String path) { return new HttpServlet() { }; }
            public void close() { closed[0]++; }
        };
        var model = WebAppModel.builder("/pages").initializer((types, context) -> { throw new IllegalStateException("broken initializer"); }).build();
        assertThrows(IllegalStateException.class, () -> WebAppDeployer.deploy(model,
                DeployOptions.defaults(getClass().getClassLoader()), ModuleLayer.boot(), engine));
        assertEquals(1, closed[0]);
    }

    @Test
    void descriptorJspFilesUseEngineFactoriesWithoutLoadingServletClasses() {
        var events = new ArrayList<String>();
        var model = WebAppModel.builder("/pages").servlet(new WebAppModel.ServletDecl("named", null, null,
                List.of("/named"), java.util.Map.of("key", "value"), 1, false, null, null, true, "/hello.jsp")).build();
        JspEngine engine = (context, layer) -> new JspEngine.Application() {
            public Servlet servlet() { return new CountingServlet("default", events); }
            public Servlet servletFor(String path) { events.add("factory:" + path); return new CountingServlet("named", events); }
            public void close() { events.add("close"); }
        };
        try (var deployed = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()), ModuleLayer.boot(), engine)) {
            assertTrue(events.contains("factory:/hello.jsp"));
            assertEquals("value", deployed.servletContext().getServletRegistration("named").getInitParameter("key"));
        }
    }

    public static final class CountingServlet extends HttpServlet {
        private final String name; private final List<String> events;
        public CountingServlet(String name, List<String> events) { this.name = name; this.events = events; }
        @Override public void init() { events.add("init:" + name); }
        @Override public void destroy() { events.add("destroy:" + name); }
    }
}
