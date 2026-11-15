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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.WebAppModel.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BUG-20261114-01: dynamic filter mappings honour {@code isMatchAfter}. */
class DynamicFilterOrderTest {

    static final List<String> TRACE = new CopyOnWriteArrayList<>();

    private Server server;
    private Deployment deployment;

    @AfterEach
    void tearDown() {
        if (deployment != null) deployment.close();
        if (server != null) server.stop();
        TRACE.clear();
    }

    public static class Tracing implements Filter {
        private final String id;
        public Tracing() { this("?"); }
        Tracing(String id) { this.id = id; }
        @Override public void doFilter(ServletRequest q, ServletResponse r, FilterChain c)
                throws IOException, ServletException {
            TRACE.add(id);
            c.doFilter(q, r);
        }
    }

    public static class Target extends HttpServlet {
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write(String.join(",", TRACE));
        }
    }

    private String get(WebAppModel.Builder model) throws Exception {
        deployment = WebAppDeployer.deploy(model.build(), DeployOptions.defaults(getClass().getClassLoader()));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + started.port() + "/t")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static WebAppModel.Builder base(ServletContainerInitializer sci) {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("t", Target.class, Target::new, List.of("/t"), Map.of(),
                        Integer.MIN_VALUE, true))
                .filter(new FilterDecl("m", Tracing.class, () -> new Tracing("M"), Map.of(), false))
                .initializer(sci);
    }

    @Test
    void dynamicFilterMappingsHonourIsMatchAfter() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> {
            ctx.addFilter("a", new Tracing("A")).addMappingForUrlPatterns(null, true, "/*");
            ctx.addFilter("b", new Tracing("B")).addMappingForUrlPatterns(null, false, "/*");
        };
        var model = base(sci)
                .filterMapping(new FilterMappingDecl("m", "/*", null, Set.of(DispatcherType.REQUEST)));
        assertEquals("B,M,A", get(model));
    }

    @Test
    void dynamicServletNameMappingsHonourIsMatchAfter() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> {
            ctx.addFilter("a", new Tracing("A")).addMappingForServletNames(null, true, "t");
            ctx.addFilter("b", new Tracing("B")).addMappingForServletNames(null, false, "t");
        };
        var model = base(sci)
                .filterMapping(new FilterMappingDecl("m", null, "t", Set.of(DispatcherType.REQUEST)));
        assertEquals("B,M,A", get(model));
    }
}
