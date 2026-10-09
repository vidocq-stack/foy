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
package io.vidocq.foy.internal.container;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Container-level guards of the request path, end to end over HTTP: the protected
 * {@code WEB-INF}/{@code META-INF} trees, context-relative dispatch paths, the session cookie on
 * early exits and the generic error body.
 */
class ContainerGuardsEndToEndTest {

    /** Reports the paths it was reached through. */
    public static class Who extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("WHO SP=" + req.getServletPath() + "|PI=" + req.getPathInfo()
                    + "|type=" + req.getDispatcherType());
        }
    }

    /** Forwards to {@code ?p=} through the servlet context ({@code ctx=1}) or the request. */
    public static class Forwarder extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws IOException, ServletException {
            String p = req.getParameter("p");
            var rd = "1".equals(req.getParameter("ctx"))
                    ? getServletContext().getRequestDispatcher(p) : req.getRequestDispatcher(p);
            if (rd == null) {
                resp.getWriter().write("NO DISPATCHER");
                return;
            }
            rd.forward(req, resp);
        }
    }

    /** Starts async processing and dispatches to {@code ?p=}. */
    public static class AsyncDispatcher extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            req.startAsync().dispatch(req.getParameter("p"));
        }
    }

    /** Creates a session, then throws with a secret message. */
    public static class Thrower extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            if (req.getParameter("session") != null) req.getSession(true);
            throw new IllegalStateException("SECRET-DETAIL");
        }
    }

    /** Writes the id of a fresh session. */
    public static class MakeSession extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write(req.getSession(true).getId());
        }
    }

    /** Creates a session as soon as the request is initialised. */
    public static class SessionOnInit implements ServletRequestListener {
        @Override public void requestInitialized(ServletRequestEvent sre) {
            ((HttpServletRequest) sre.getServletRequest()).getSession(true);
        }
    }

    @TempDir Path root;
    private Server server;
    private Deployment deployment;
    private int port;

    @BeforeEach
    void files() throws IOException {
        Files.createDirectories(root.resolve("WEB-INF/views"));
        Files.writeString(root.resolve("WEB-INF/views/x.jsp"), "SECRET JSP");
        Files.writeString(root.resolve("WEB-INF/web.xml"), "SECRET XML");
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/MANIFEST.MF"), "SECRET MF");
        Files.createDirectories(root.resolve("foo"));
        Files.writeString(root.resolve("foo/index.html"), "INDEX");
        Files.createDirectories(root.resolve("a;b"));
        Files.writeString(root.resolve("a;b/z.txt"), "z");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
        server = null;
        deployment = null;
    }

    private static ServletDecl decl(String name, Class<? extends HttpServlet> type,
                                    java.util.function.Supplier<? extends HttpServlet> f, String... patterns) {
        return new ServletDecl(name, type, f, List.of(patterns), Map.of(), -1, true);
    }

    private static ServletDecl who(String name, String... patterns) {
        return decl(name, Who.class, Who::new, patterns);
    }

    private void deploy(String contextPath, Consumer<WebAppModel.Builder> config) {
        var b = WebAppModel.builder(contextPath);
        config.accept(b);
        deployment = WebAppDeployer.deploy(b.build(), DeployOptions.defaults(getClass().getClassLoader())
                .withResourceProvider(new WelcomeFilesTest.Provider(root)));
        var r = TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ---- Finding 2: protected trees are refused to every client request ----

    @Test
    void aClientRequestUnderWebInfIsRefusedEvenWhenAServletMatches() throws Exception {
        deploy("/ctx", b -> b.servlet(who("jsp", "*.jsp")));
        for (String p : List.of("/ctx/WEB-INF/views/x.jsp", "/ctx/web-inf/views/x.jsp",
                "/ctx/WEB-INF./views/x.jsp", "/ctx/WEB-INF%20/views/x.jsp", "/ctx/META-INF/MANIFEST.MF",
                "/ctx/meta-inf/x.jsp", "/ctx/WEB-INF;x=1/views/x.jsp")) {
            var r = get(p);
            assertEquals(404, r.statusCode(), p);
            assertFalse(r.body().contains("WHO") || r.body().contains("SECRET"), p + " -> " + r.body());
        }
    }

    @Test
    void theProtectedDirectoryWithoutSlashIs404NotARedirect() throws Exception {
        deploy("/ctx", b -> b.welcomeFiles(List.of("index.html")));
        for (String p : List.of("/ctx/WEB-INF", "/ctx/WEB-INF/", "/ctx/META-INF", "/ctx/WEB-INF/views")) {
            var r = get(p);
            assertEquals(404, r.statusCode(), p);
            assertTrue(r.headers().firstValue("Location").isEmpty(), p);
        }
    }

    @Test
    void aWelcomeFileUnderWebInfIsNotResolvedEvenThroughAServlet() throws Exception {
        Files.writeString(root.resolve("WEB-INF/x.jsp"), "SECRET");
        deploy("/ctx", b -> b.welcomeFiles(List.of("WEB-INF/x.jsp")).servlet(who("jsp", "*.jsp")));
        var r = get("/ctx/");
        assertEquals(404, r.statusCode(), r.body());
        assertFalse(r.body().contains("WHO"));
    }

    @Test
    void aForwardUnderWebInfIsStillAllowed() throws Exception {
        deploy("/ctx", b -> b.servlet(who("jsp", "*.jsp"))
                .servlet(decl("fwd", Forwarder.class, Forwarder::new, "/fwd")));
        var r = get("/ctx/fwd?p=/WEB-INF/views/x.jsp");
        assertEquals(200, r.statusCode());
        assertEquals("WHO SP=/WEB-INF/views/x.jsp|PI=null|type=FORWARD", r.body());
        var stat = get("/ctx/fwd?p=/WEB-INF/web.xml");
        assertEquals("SECRET XML", stat.body());
    }

    // ---- Finding 3: dispatch paths are context-relative ----

    @Test
    void aDispatchPathStartingLikeTheContextPathIsNotStripped() throws Exception {
        deploy("/app", b -> b.servlet(who("jsp", "*.jsp"))
                .servlet(decl("fwd", Forwarder.class, Forwarder::new, "/fwd")));
        assertEquals("WHO SP=/apple.jsp|PI=null|type=FORWARD", get("/app/fwd?ctx=1&p=/apple.jsp").body());
        assertEquals("WHO SP=/apple.jsp|PI=null|type=FORWARD", get("/app/fwd?p=/apple.jsp").body());
        assertEquals("WHO SP=/app/x.jsp|PI=null|type=FORWARD", get("/app/fwd?ctx=1&p=/app/x.jsp").body());
        assertEquals("WHO SP=/sub/x.jsp|PI=null|type=FORWARD", get("/app/fwd?p=sub/x.jsp").body());
    }

    @Test
    void aDispatchPathEqualToTheContextPathPrefixIsKept() throws Exception {
        deploy("/views", b -> b.servlet(who("jsp", "*.jsp"))
                .servlet(decl("fwd", Forwarder.class, Forwarder::new, "/fwd")));
        assertEquals("WHO SP=/views/x.jsp|PI=null|type=FORWARD", get("/views/fwd?ctx=1&p=/views/x.jsp").body());
        assertEquals("WHO SP=/views/x.jsp|PI=null|type=FORWARD", get("/views/fwd?p=/views/x.jsp").body());
    }

    @Test
    void anAsyncDispatchPathIsContextRelative() throws Exception {
        deploy("/app", b -> b.servlet(who("w", "/application/*"))
                .servlet(decl("async", AsyncDispatcher.class, AsyncDispatcher::new, "/async")));
        var r = get("/app/async?p=/application/x");
        assertEquals(200, r.statusCode());
        assertEquals("WHO SP=/application|PI=/x|type=ASYNC", r.body());
    }

    // ---- Finding 5 ----

    @Test
    void theWelcomeRedirectEncodesTheSemicolon() throws Exception {
        deploy("/ctx", b -> b.welcomeFiles(List.of("index.html")));
        var r = get("/ctx/a%3Bb");
        assertEquals(302, r.statusCode());
        assertTrue(r.headers().firstValue("Location").orElseThrow().endsWith("/ctx/a%3Bb/"),
                r.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void theWelcomeRedirectKeepsAUrlTrackedSession() throws Exception {
        deploy("/ctx", b -> b.welcomeFiles(List.of("index.html"))
                .servlet(decl("mk", MakeSession.class, MakeSession::new, "/mk")));
        String id = get("/ctx/mk").body();
        var r = get("/ctx/foo;jsessionid=" + id);
        assertEquals(302, r.statusCode());
        String location = r.headers().firstValue("Location").orElseThrow();
        assertTrue(location.endsWith("/ctx/foo/;jsessionid=" + id), location);
    }

    @Test
    void anUnhandledExceptionAnswersAGenericBody() throws Exception {
        deploy("/ctx", b -> b.servlet(decl("t", Thrower.class, Thrower::new, "/t")));
        var r = get("/ctx/t");
        assertEquals(500, r.statusCode());
        assertFalse(r.body().contains("SECRET-DETAIL"), r.body());
    }

    @Test
    void aFailingErrorPageStillEmitsTheSessionCookie() throws Exception {
        deploy("/ctx", b -> b.servlet(decl("t", Thrower.class, Thrower::new, "/t"))
                .errorPages(new ErrorPageRegistry().register(500, "/t")));
        var r = get("/ctx/t?session=1");
        assertEquals(500, r.statusCode());
        assertFalse(r.body().contains("SECRET-DETAIL"), r.body());
        assertTrue(r.headers().firstValue("Set-Cookie").orElse("").startsWith("JSESSIONID="),
                r.headers().map().toString());
    }

    @Test
    void aSecurityRefusalStillEmitsTheSessionCookie() throws Exception {
        var denied = new ServletSecurityElement(new HttpConstraintElement(ServletSecurity.EmptyRoleSemantic.DENY));
        deploy("/ctx", b -> b.listener(new WebAppModel.ListenerDecl(SessionOnInit.class, SessionOnInit::new))
                .servlet(new ServletDecl("sec", Who.class, Who::new, List.of("/sec"), Map.of(), -1, true, denied)));
        var r = get("/ctx/sec");
        assertEquals(403, r.statusCode());
        assertTrue(r.headers().firstValue("Set-Cookie").orElse("").startsWith("JSESSIONID="),
                r.headers().map().toString());
    }
}
