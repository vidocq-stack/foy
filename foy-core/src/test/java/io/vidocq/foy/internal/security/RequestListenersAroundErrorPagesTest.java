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
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-20261010-08 and BUG-20261011-07: an error page, and a security denial on every branch, run
 * inside the request listeners, as the CDI request and session scope listeners need. The listener
 * here binds a thread-local in {@code requestInitialized} and clears it in {@code requestDestroyed},
 * as those listeners do.
 */
class RequestListenersAroundErrorPagesTest {

    /** Stands for the CDI context listeners: a per-thread binding between the two events. */
    public static class Scope implements ServletRequestListener {
        static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
        static final AtomicInteger INITIALIZED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();
        @Override public void requestInitialized(ServletRequestEvent sre) {
            INITIALIZED.incrementAndGet();
            ACTIVE.set(Boolean.TRUE);
        }
        @Override public void requestDestroyed(ServletRequestEvent sre) {
            DESTROYED.incrementAndGet();
            ACTIVE.remove();
        }
    }

    public static class App extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            switch (req.getServletPath()) {
                case "/status" -> resp.sendError(410);
                case "/throw" -> throw new IllegalStateException("boom");
                default -> resp.getWriter().write("ok");
            }
        }
    }

    /** The error page: reports whether the request listeners' binding is in place. */
    public static class Page extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("scope=" + Boolean.TRUE.equals(Scope.ACTIVE.get()));
        }
    }

    @TempDir Path root;

    @BeforeEach
    void reset() {
        Scope.INITIALIZED.set(0);
        Scope.DESTROYED.set(0);
    }

    private WebAppModel model() {
        return WebAppModel.builder("/")
                .welcomeFiles(List.of("index.html"))
                .servlet(new ServletDecl("app", App.class, App::new, List.of("/status", "/throw", "/admin/*"),
                        Map.of(), -1, false))
                .servlet(new ServletDecl("page", Page.class, Page::new, List.of("/page"), Map.of(), -1, false))
                .listener(new ListenerDecl(Scope.class, Scope::new))
                .securityConstraints(List.of(
                        constraint(List.of("/admin/*", "/secret/*"), List.of(), List.of(), List.of("admin"), null)))
                .errorPages(new ErrorPageRegistry()
                        .register(410, "/page").register(404, "/page").register(401, "/page").register(403, "/page")
                        .register(IllegalStateException.class, "/page"))
                .build();
    }

    private void assertOneRequestCycle() {
        assertEquals(1, Scope.INITIALIZED.get(), "requestInitialized");
        assertEquals(1, Scope.DESTROYED.get(), "requestDestroyed");
    }

    @Test
    void anErrorPageAfterSendErrorRunsInsideTheRequestListeners() throws Exception {
        try (var app = new SecureApp(model(), root)) {
            var r = app.get("/status");
            assertEquals(410, r.statusCode());
            assertEquals("scope=true", r.body());
        }
        assertOneRequestCycle();
    }

    @Test
    void anExceptionErrorPageRunsInsideTheRequestListeners() throws Exception {
        try (var app = new SecureApp(model(), root)) {
            var r = app.get("/throw");
            assertEquals(500, r.statusCode());
            assertEquals("scope=true", r.body());
        }
        assertOneRequestCycle();
    }

    @Test
    void aDenialErrorPageRunsInsideTheRequestListeners() throws Exception {
        try (var app = new SecureApp(model(), root)) {
            var r = app.send("GET", "/admin/x", "bob:b-secret");
            assertEquals(403, r.statusCode());
            assertEquals("scope=true", r.body());
        }
        assertOneRequestCycle();
    }

    @Test
    void aProtectedPathErrorPageRunsInsideTheRequestListeners() throws Exception {
        try (var app = new SecureApp(model(), root)) {
            var r = app.get("/WEB-INF/web.xml");
            assertEquals(404, r.statusCode());
            assertEquals("scope=true", r.body());
        }
        assertOneRequestCycle();
    }

    @Test
    void aDenialOnTheDirectoryRedirectRunsInsideTheRequestListeners() throws Exception {
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve("secret/index.html"), "secret index");
        try (var app = new SecureApp(model(), root)) {
            var r = app.get("/secret");
            assertEquals(401, r.statusCode(), "not the 302 to /secret/");
            assertEquals("scope=true", r.body());
        }
        assertOneRequestCycle();
    }
}
