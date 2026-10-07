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
import jakarta.servlet.*;
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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class WebAppDeployerEndToEndTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private Server server;
    private int port;
    private Deployment deployment;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
        EVENTS.clear();
    }

    private void deploy(WebAppModel model) {
        deploy(model, DeployOptions.defaults(getClass().getClassLoader()));
    }

    private void deploy(WebAppModel model, DeployOptions options) {
        deployment = WebAppDeployer.deploy(model, options);
        var r = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public static class Recording extends HttpServlet {
        final String id;
        public Recording() { this("default"); }
        Recording(String id) { this.id = id; }
        @Override public void init() { EVENTS.add("init:" + id); }
        @Override public void destroy() { EVENTS.add("destroy:" + id); }
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write(id + ":" + getServletConfig().getInitParameter("k"));
        }
    }

    static ServletDecl decl(String id, int los, String... patterns) {
        return new ServletDecl(id, Recording.class, () -> new Recording(id), List.of(patterns),
                Map.of("k", "v-" + id), los, true);
    }

    @Test
    void oneInstancePerDeclaration() throws Exception {
        deploy(WebAppModel.builder("/").servlet(decl("a", Integer.MIN_VALUE, "/x", "/y")).build());
        assertEquals("a:v-a", get("/x").body());
        assertEquals("a:v-a", get("/y").body());
        assertEquals(List.of("init:a"), EVENTS);
    }

    @Test
    void loadOnStartupOrdersInitThenDeclarationOrder() {
        deploy(WebAppModel.builder("/")
                .servlet(decl("lazy", Integer.MIN_VALUE, "/l"))
                .servlet(decl("two", 2, "/2"))
                .servlet(decl("zero", 0, "/0"))
                .servlet(decl("alsoTwo", 2, "/22"))
                .build());
        assertEquals(List.of("init:zero", "init:two", "init:alsoTwo", "init:lazy"), EVENTS);
    }

    public static class FailingInit extends HttpServlet {
        final ServletException failure;
        FailingInit(ServletException failure) { this.failure = failure; }
        @Override public void init() throws ServletException { throw failure; }
    }

    static ServletDecl failing(String name, String pattern, ServletException e) {
        return new ServletDecl(name, FailingInit.class, () -> new FailingInit(e), List.of(pattern),
                Map.of(), Integer.MIN_VALUE, true);
    }

    @Test
    void initFailuresAnswer500Or404Or503() throws Exception {
        deploy(WebAppModel.builder("/")
                .servlet(failing("plain", "/plain", new ServletException("x")))
                .servlet(failing("perm", "/perm", new UnavailableException("gone")))
                .servlet(failing("temp", "/temp", new UnavailableException("later", 30)))
                .build());
        assertEquals(500, get("/plain").statusCode());
        assertEquals(404, get("/perm").statusCode());
        assertEquals(503, get("/temp").statusCode());
    }

    @Test
    void sciDynamicServletIsServedAndListenerSeesContextInitialized() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> {
            EVENTS.add("sci:" + classes);
            ctx.addServlet("dyn", new Recording("dyn")).addMapping("/dyn");
            ctx.addListener(new ServletContextListener() {
                @Override public void contextInitialized(ServletContextEvent e) { EVENTS.add("ctxInit"); }
                @Override public void contextDestroyed(ServletContextEvent e) { EVENTS.add("ctxDestroyed"); }
            });
        };
        deploy(WebAppModel.builder("/").initializer(sci).build());
        assertEquals("dyn:null", get("/dyn").body());
        assertEquals(List.of("sci:null", "ctxInit", "init:dyn"), EVENTS);
    }

    @Test
    void addServletAfterDeployThrows() {
        deploy(WebAppModel.builder("/").build());
        assertThrows(IllegalStateException.class,
                () -> deployment.servletContext().addServlet("late", new Recording("late")));
    }

    public static class ThrowingDestroy extends Recording {
        public ThrowingDestroy() { super("throwing"); }
        @Override public void destroy() { EVENTS.add("destroy:throwing"); throw new IllegalStateException(); }
    }

    @Test
    void closeDestroysInReverseOrder() {
        ServletContainerInitializer sci = (c, ctx) -> ctx.addListener(new ServletContextListener() {
            @Override public void contextDestroyed(ServletContextEvent e) { EVENTS.add("ctxDestroyed"); }
        });
        deploy(WebAppModel.builder("/")
                .servlet(decl("first", 1, "/1"))
                .servlet(new ServletDecl("throwing", ThrowingDestroy.class, ThrowingDestroy::new,
                        List.of("/t"), Map.of(), 2, true))
                .initializer(sci)
                .build());
        EVENTS.clear();
        deployment.close();
        deployment.close();
        assertEquals(List.of("destroy:throwing", "destroy:first", "ctxDestroyed"), EVENTS);
    }

    @Test
    void visibilityRestrictsDynamicRegistrationsOnly() throws Exception {
        ServletContainerInitializer sci = (c, ctx) -> ctx.addServlet("byClass", Recording.class).addMapping("/dyn");
        var cl = getClass().getClassLoader();
        deploy(WebAppModel.builder("/").servlet(decl("static", Integer.MIN_VALUE, "/s")).initializer(sci).build(),
                DeployOptions.defaults(cl).withComponentFactory(ComponentFactory.reflective(cl, Set.of())));
        assertEquals("static:v-static", get("/s").body());
        assertEquals(404, get("/dyn").statusCode());
    }

    @Test
    void tempDirAttributeIsSet() {
        deploy(WebAppModel.builder("/").build());
        assertInstanceOf(java.io.File.class,
                deployment.servletContext().getAttribute(ServletContext.TEMPDIR));
    }
}
