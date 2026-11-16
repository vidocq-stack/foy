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
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP sessions across two deployments of the same application, through a real Chappe server: the first
 * deployment exports its sessions at undeploy, the second imports them before it serves a request.
 */
@Timeout(30)
class SessionSurvivesSwapEndToEndTest {

    private static final String PATH = "/s";
    /**
     * The events of an undeploy that exports one session, in order: the servlet is destroyed, then the session is
     * passivated (and leaves without {@code sessionDestroyed}), then {@code contextDestroyed}.
     */
    private static final List<String> EXPORTED_EVENTS =
            List.of("sessionCreated", "destroy", "willPassivate", "contextDestroyed");

    /** One deployment under test: its events, its server and its port. */
    private final class App {
        final List<String> events = new CopyOnWriteArrayList<>();
        final Deployment deployment;
        final Server server;
        final int port;

        App() {
            this(SessionSurvivesSwapEndToEndTest.class.getClassLoader(), () -> { });
        }

        /**
         * @param loader the application's class loader, {@code null} for none
         * @param onDestroy what the servlet's {@code destroy()} runs after it recorded itself
         */
        App(ClassLoader loader, Runnable onDestroy) {
            var servlet = new ServletDecl("s", Counter.class, () -> new Counter(events, onDestroy), List.of(PATH),
                    Map.of(), 1, true);
            var model = WebAppModel.builder("/").servlet(servlet)
                    .listener(new ListenerDecl(Recorder.class, () -> new Recorder(events))).build();
            deployment = WebAppDeployer.deploy(model, DeployOptions
                    .defaults(SessionSurvivesSwapEndToEndTest.class.getClassLoader()).withApplicationLoader(loader));
            var started = TestServerLauncherAccess.start(deployment.handler());
            server = started.server();
            port = started.port();
            apps.add(this);
        }

        HttpResponse<String> get(String cookie) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH)).GET();
            if (cookie != null) request.header("Cookie", cookie);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Stores an attribute on the first request of a session and reports what the session holds. */
    public static final class Counter extends HttpServlet {
        private final transient List<String> events;
        private final transient Runnable onDestroy;

        Counter(List<String> events, Runnable onDestroy) {
            this.events = events;
            this.onDestroy = onDestroy;
        }

        @Override
        public void destroy() {
            events.add("destroy");
            onDestroy.run();
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            HttpSession session = req.getSession();
            boolean fresh = session.isNew();
            if (session.getAttribute("v") == null) {
                session.setAttribute("v", "kept");
                session.setAttribute("p", new Passivating(events));
            }
            resp.getWriter().write("new=" + fresh + ";v=" + session.getAttribute("v") + ";id=" + session.getId());
        }
    }

    /** An attribute that records its passivation; it moves with the session, without its recorder. */
    public static final class Passivating implements HttpSessionActivationListener, Serializable {
        private static final long serialVersionUID = 1L;
        private final transient List<String> events;

        Passivating(List<String> events) {
            this.events = events;
        }

        @Override
        public void sessionWillPassivate(HttpSessionEvent event) {
            if (events != null) events.add("willPassivate");
        }
    }

    /** Records the context and session events of its deployment. */
    public static final class Recorder implements ServletContextListener, HttpSessionListener {
        private final transient List<String> events;

        Recorder(List<String> events) {
            this.events = events;
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
            events.add("contextDestroyed");
        }

        @Override
        public void sessionCreated(HttpSessionEvent event) {
            events.add("sessionCreated");
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent event) {
            events.add("sessionDestroyed");
        }
    }

    private final List<App> apps = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        for (App app : apps) {
            app.deployment.close();
            app.server.stop();
        }
        client.close();
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the latch was never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private static String cookieOf(HttpResponse<?> response) {
        String header = response.headers().firstValue("set-cookie").orElseThrow();
        return header.substring(0, header.indexOf(';'));
    }

    @Test
    void aSessionKeepsItsIdAndItsAttributeAcrossTheSwap() throws Exception {
        App a = new App();
        HttpResponse<String> first = a.get(null);
        String cookie = cookieOf(first);
        assertTrue(first.body().startsWith("new=true;v=kept"), first.body());

        byte[] export = a.deployment.closeExportingSessions();
        a.server.stop();
        App b = new App();
        assertEquals(1, b.deployment.importSessions(export).imported());

        HttpResponse<String> second = b.get(cookie);
        assertTrue(second.body().startsWith("new=false;v=kept"), second.body());
        assertTrue(second.body().endsWith("id=" + cookie.substring("JSESSIONID=".length())), second.body());
        assertTrue(second.headers().firstValue("set-cookie").isEmpty(), "a new session id came back");
        assertEquals(EXPORTED_EVENTS, a.events, "the session ended on the old side");
        assertEquals(List.of(), b.events, "a session was created on the new side");
    }

    @Test
    void closeExportingSessionsUndeploysLikeClose() throws Exception {
        App a = new App();
        a.get(null);

        byte[] export = a.deployment.closeExportingSessions();

        assertEquals(EXPORTED_EVENTS, a.events, "no sessionDestroyed for an export");
        assertEquals(0, a.deployment.sessionManager().store().size());
        assertEquals(503, a.get(null).statusCode());
        App b = new App();
        assertEquals(1, b.deployment.importSessions(export).imported());
    }

    @Test
    void aSecondCallExportsNoSessionAndCloseAfterItIsANoOp() throws Exception {
        App a = new App();
        a.get(null);
        a.deployment.closeExportingSessions();

        byte[] again = a.deployment.closeExportingSessions();
        a.deployment.close();

        App b = new App();
        assertEquals(0, b.deployment.importSessions(again).imported());
        assertEquals(EXPORTED_EVENTS, a.events, "the undeploy ran twice");
    }

    @Test
    void closeExportingSessionsDuringACloseExportsNoSessionAndLeavesThemToTheClose() throws Exception {
        var destroying = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        App a = new App(SessionSurvivesSwapEndToEndTest.class.getClassLoader(), () -> {
            destroying.countDown();
            awaitOrFail(release);
        });
        a.get(null);
        // A stop during a swap: the close is still destroying the servlets when the export is asked for.
        Thread closing = Thread.ofVirtual().start(a.deployment::close);
        byte[] export;
        try {
            awaitOrFail(destroying);
            export = a.deployment.closeExportingSessions();
        } finally {
            release.countDown();
        }
        closing.join();

        App b = new App();
        assertEquals(new SessionManager.SessionImport(0, 0, 0), b.deployment.importSessions(export));
        assertEquals(List.of("sessionCreated", "destroy", "sessionDestroyed", "contextDestroyed"), a.events,
                "the close ends the sessions");
    }

    @Test
    void aDeploymentWithoutAnApplicationLoaderImportsThroughItsContextClassLoader() throws Exception {
        App a = new App();
        a.get(null);
        byte[] export = a.deployment.closeExportingSessions();

        App b = new App(null, () -> { });
        assertEquals(1, b.deployment.importSessions(export).imported());
    }

    @Test
    void closeThenCloseExportingSessionsExportsNoSession() throws Exception {
        App a = new App();
        a.get(null);
        a.deployment.close();

        byte[] export = a.deployment.closeExportingSessions();

        App b = new App();
        assertEquals(0, b.deployment.importSessions(export).imported());
    }
}
