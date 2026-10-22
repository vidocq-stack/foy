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

import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.ServletException;
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

import static org.junit.jupiter.api.Assertions.*;

class RoleRefEndToEndTest {

    private Deployment deployment;
    private io.vidocq.chappe.api.Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
    }

    /** Logs in as alice (role admin), prints MGR, includes /plain, prints MGR again. */
    public static class Linked extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.login("alice", "a-secret");
            resp.getWriter().write("linked:" + req.isUserInRole("MGR") + ";");
            req.getRequestDispatcher("/plain").include(req, resp);
            resp.getWriter().write("after:" + req.isUserInRole("MGR"));
        }
    }

    public static class Plain extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("plain:" + req.isUserInRole("MGR") + ";");
        }
    }

    /** Synchronises the AsyncContext.start thread with the dispatch thread of {@link AsyncLinked}. */
    static final class AsyncProbe {
        static volatile java.util.concurrent.CountDownLatch insidePlain;
        static volatile java.util.concurrent.CountDownLatch probed;
        static volatile java.util.concurrent.CountDownLatch dispatchReturned;
    }

    /**
     * Logs in as alice, starts an async cycle whose start() Runnable probes {@code MGR} while the
     * dispatch thread is inside an include of /plain-wait, and again after the dispatch returned.
     */
    public static class AsyncLinked extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.login("alice", "a-secret");
            var ac = req.startAsync();
            ac.start(() -> {
                try {
                    AsyncProbe.insidePlain.await();
                    boolean during = req.isUserInRole("MGR");
                    AsyncProbe.probed.countDown();
                    AsyncProbe.dispatchReturned.await();
                    boolean after = req.isUserInRole("MGR");
                    resp.getWriter().write("start-during:" + during + ";start-after:" + after);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    ac.complete();
                }
            });
            req.getRequestDispatcher("/plain-wait").include(req, resp);
            AsyncProbe.dispatchReturned.countDown();
        }
    }

    /** Reports MGR on the dispatch thread, then holds the include until the start() thread probed. */
    public static class PlainWait extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("plain:" + req.isUserInRole("MGR") + ";");
            AsyncProbe.insidePlain.countDown();
            try {
                AsyncProbe.probed.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void roleRefsOnAnAsyncStartThreadFollowTheServletThatStartedTheCycle() throws Exception {
        AsyncProbe.insidePlain = new java.util.concurrent.CountDownLatch(1);
        AsyncProbe.probed = new java.util.concurrent.CountDownLatch(1);
        AsyncProbe.dispatchReturned = new java.util.concurrent.CountDownLatch(1);
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("linked", AsyncLinked.class, AsyncLinked::new, List.of("/linked"), Map.of(), -1, true))
                .servlet(new ServletDecl("plain", PlainWait.class, PlainWait::new, List.of("/plain-wait"), Map.of(), -1, true))
                .securityRoleRefs("linked", Map.of("MGR", "admin"))
                .securityRoles(List.of("admin"))
                .build();
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader())
                .withIdentityStore(InMemoryIdentityStore.builder().user("alice", "a-secret".toCharArray(), "admin").build()));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        port = started.port();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/linked"))
                        .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        // The dispatch thread keeps the include scoping (plain has no ref); the start() thread
        // resolves refs against the servlet executing when startAsync was called (linked).
        assertEquals("plain:false;start-during:true;start-after:true", r.body());
    }

    @Test
    void roleRefsFollowTheExecutingServlet() throws Exception {
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("linked", Linked.class, Linked::new, List.of("/linked"), Map.of(), -1, false))
                .servlet(new ServletDecl("plain", Plain.class, Plain::new, List.of("/plain"), Map.of(), -1, false))
                .securityRoleRefs("linked", Map.of("MGR", "admin"))
                .securityRoles(List.of("admin"))
                .build();
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader())
                .withIdentityStore(InMemoryIdentityStore.builder().user("alice", "a-secret".toCharArray(), "admin").build()));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        port = started.port();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/linked")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("linked:true;plain:false;after:true", r.body());
    }
}
