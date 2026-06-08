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
package io.vidocq.foy.internal;

import io.vidocq.foy.spi.session.SessionStore;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServletListenerEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void requestListenerFiresPerRequest() throws Exception {
        AtomicInteger init = new AtomicInteger();
        AtomicInteger destroy = new AtomicInteger();
        ServletRequestListener rl = new ServletRequestListener() {
            @Override public void requestInitialized(ServletRequestEvent e) { init.incrementAndGet(); }
            @Override public void requestDestroyed(ServletRequestEvent e) { destroy.incrementAndGet(); }
        };

        startServer("/*", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("ok");
            }
        }, rl);

        get("http://127.0.0.1:" + port + "/a");
        get("http://127.0.0.1:" + port + "/b");
        assertEquals(2, init.get());
        assertEquals(2, destroy.get());
    }

    @Test
    void httpSessionListenerReceivesCreatedAndDestroyed() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger destroyed = new AtomicInteger();
        HttpSessionListener sl = new HttpSessionListener() {
            @Override public void sessionCreated(HttpSessionEvent e) { created.incrementAndGet(); }
            @Override public void sessionDestroyed(HttpSessionEvent e) { destroyed.incrementAndGet(); }
        };
        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if ("kill".equals(req.getParameter("op"))) {
                    req.getSession().invalidate();
                } else {
                    req.getSession();
                }
                resp.getWriter().write("ok");
            }
        };
        startServer("/s", s, sl);

        String sid = extractSessionId(get("http://127.0.0.1:" + port + "/s"));
        assertEquals(1, created.get());
        assertEquals(0, destroyed.get());

        getWithCookie("http://127.0.0.1:" + port + "/s?op=kill", sid);
        assertEquals(1, destroyed.get());
    }

    @Test
    void httpSessionBindingListenerInvokedOnSetAndRemove() throws Exception {
        AtomicInteger bound = new AtomicInteger();
        AtomicInteger unbound = new AtomicInteger();
        class Tracking implements HttpSessionBindingListener {
            @Override public void valueBound(HttpSessionBindingEvent e) { bound.incrementAndGet(); }
            @Override public void valueUnbound(HttpSessionBindingEvent e) { unbound.incrementAndGet(); }
        }

        HttpServlet s = new HttpServlet() {
            @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String op = req.getParameter("op");
                var session = req.getSession();
                switch (op == null ? "set" : op) {
                    case "set"     -> session.setAttribute("t", new Tracking());
                    case "replace" -> session.setAttribute("t", new Tracking());
                    case "remove"  -> session.removeAttribute("t");
                    case "kill"    -> session.invalidate();
                }
                resp.getWriter().write("ok");
            }
        };
        startServer("/b", s, (ServletRequestListener) new ServletRequestListener() {});

        String sid = extractSessionId(get("http://127.0.0.1:" + port + "/b?op=set"));
        assertEquals(1, bound.get());

        getWithCookie("http://127.0.0.1:" + port + "/b?op=replace", sid);
        assertEquals(2, bound.get());
        assertEquals(1, unbound.get(), "previous value should have been unbound on replace");

        getWithCookie("http://127.0.0.1:" + port + "/b?op=kill", sid);
        assertEquals(2, unbound.get(), "remaining binding must be unbound on invalidate");
    }

    // ---- helpers ----

    private void startServer(String pattern, HttpServlet servlet, java.util.EventListener listener) {
        var ctx = new VidocqServletContext("/");
        var reg = new ListenerRegistry();
        reg.register(listener);
        ctx.setListenerRegistry(reg);
        var sessionManager = new SessionManager(new InMemorySessionStore(), ctx, 1800);
        sessionManager.setListenerRegistry(reg);
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of(pattern), servlet, "S")));
        var bridge = new ChappeServletBridge(
                dispatcher, new FilterRegistry(List.of()), ctx, sessionManager, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getWithCookie(String url, String sid) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create(url))
                        .header("Cookie", "JSESSIONID=" + sid)
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private static String extractSessionId(HttpResponse<?> r) {
        return r.headers().firstValue("set-cookie").map(h -> {
            int eq = h.indexOf('=');
            int semi = h.indexOf(';', eq);
            return h.substring(eq + 1, semi < 0 ? h.length() : semi);
        }).orElse(null);
    }
}
