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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class ServletSecurityRegistrationEndToEndTest {

    public static class Hello extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("hello " + req.getRemoteUser());
        }
    }

    /** A descriptor-declared listener: {@code declareRoles} is allowed from it (not added programmatically). */
    public static class DeclaringListener implements ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent e) {
            e.getServletContext().declareRoles("operator");
        }
    }

    @Test
    void setServletSecurityAppliesToTheMappingsTheDescriptorDoesNotConstrain() throws Exception {
        var conflicts = new AtomicReference<Set<String>>();
        var nullThrows = new AtomicReference<Boolean>();
        ServletContainerInitializer sci = (classes, ctx) -> {
            var reg = ctx.addServlet("dyn", new Hello());
            reg.addMapping("/dyn", "/dd");
            try { reg.setServletSecurity(null); nullThrows.set(false); }
            catch (IllegalArgumentException e) { nullThrows.set(true); }
            conflicts.set(reg.setServletSecurity(
                    new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "admin"))));
            ctx.declareRoles("auditor");
        };
        var model = WebAppModel.builder("/").initializer(sci)
                .securityConstraints(List.of(constraint(List.of("/dd"), List.of("GET"), List.of(), null, null),
                        constraint(List.of("/star"), List.of(), List.of(), List.of("*"), null)))
                .servlet(new ServletDecl("star", Hello.class, Hello::new, List.of("/star"), Map.of(), -1, false))
                .build();
        var store = InMemoryIdentityStore.builder().user("ann", "x".toCharArray(), "auditor").build();
        try (var app = new SecureApp(model, SecuritySettings.DEFAULTS.withIdentityStore(store))) {
            assertTrue(nullThrows.get());
            assertEquals(Set.of("/dd"), conflicts.get());
            assertEquals(401, app.get("/dyn").statusCode());
            assertEquals(200, app.get("/dd").statusCode(), "the descriptor wins on its exact pattern");
            assertEquals(200, app.send("GET", "/star", "ann:x").statusCode(), "* covers a role declared by declareRoles");
            var ctx = app.deployment.servletContext();
            var reg = (jakarta.servlet.ServletRegistration.Dynamic) ctx.getServletRegistration("dyn");
            assertThrows(IllegalStateException.class, () -> reg.setServletSecurity(
                    new ServletSecurityElement(new HttpConstraintElement())));
            assertThrows(IllegalStateException.class, () -> ctx.declareRoles("late"));
        }
    }

    @Test
    void setServletSecurityOnAStaticServletReachesTheConstraintTable() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> ((jakarta.servlet.ServletRegistration.Dynamic)
                ctx.getServletRegistration("plain")).setServletSecurity(
                        new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "admin")));
        var model = WebAppModel.builder("/").initializer(sci)
                .servlet(new ServletDecl("plain", Hello.class, Hello::new, List.of("/plain"), Map.of(), -1, false))
                .build();
        try (var app = new SecureApp(model)) {
            assertEquals(401, app.get("/plain").statusCode());
        }
    }

    @Test
    void starCoversARoleDeclaredByAContextListener() throws Exception {
        var model = WebAppModel.builder("/")
                .listener(new ListenerDecl(DeclaringListener.class, DeclaringListener::new))
                .securityConstraints(List.of(constraint(List.of("/star"), List.of(), List.of(), List.of("*"), null)))
                .servlet(new ServletDecl("star", Hello.class, Hello::new, List.of("/star"), Map.of(), -1, false))
                .build();
        var store = InMemoryIdentityStore.builder().user("olga", "x".toCharArray(), "operator")
                .user("bob", "x".toCharArray(), "nobody").build();
        try (var app = new SecureApp(model, SecuritySettings.DEFAULTS.withIdentityStore(store))) {
            assertEquals(200, app.send("GET", "/star", "olga:x").statusCode());
            assertEquals(403, app.send("GET", "/star", "bob:x").statusCode(), "an undeclared role is not in *");
        }
    }

    @Test
    void runAsIsReportedByTheRegistration() throws Exception {
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("ra", Hello.class, Hello::new, List.of("/ra"), Map.of(), -1, false))
                .runAsRole("ra", "admin").build();
        try (var app = new SecureApp(model)) {
            assertEquals("admin", app.deployment.servletContext().getServletRegistration("ra").getRunAsRole());
        }
    }
}
