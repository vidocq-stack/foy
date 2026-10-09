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
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Request path canonicalisation (Servlet 6.1 section 3.5.2), end to end over HTTP. */
class RequestPathCanonicalisationTest {

    /** Reports the request's path accessors. */
    public static class Paths extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write("SP=" + req.getServletPath() + "|PI=" + req.getPathInfo()
                    + "|URI=" + req.getRequestURI() + "|URL=" + req.getRequestURL());
        }
    }

    /** Forwards to the {@code to} parameter; reports a missing dispatcher. */
    public static class Forwarder extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            var rd = req.getRequestDispatcher(req.getParameter("to"));
            if (rd == null) { resp.getWriter().write("NO-DISPATCHER"); return; }
            rd.forward(req, resp);
        }
    }

    public static class BadRequestPage extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("BAD-REQUEST-PAGE " + req.getAttribute("jakarta.servlet.error.status_code"));
        }
    }

    /** A plain directory provider. */
    record DirProvider(Path root) implements VidocqServletContext.ResourceProvider {
        @Override public Set<String> listPaths(String path) { return null; }
        @Override public InputStream openStream(String path) {
            Path p = root.resolve(path.substring(1));
            try { return Files.isRegularFile(p) ? Files.newInputStream(p) : null; }
            catch (IOException e) { return null; }
        }
        @Override public URL toUrl(String path) {
            Path p = root.resolve(path.substring(1));
            try { return Files.exists(p) ? p.toUri().toURL() : null; }
            catch (IOException e) { return null; }
        }
    }

    record Reply(int status, String body) {}

    @TempDir Path root;
    private Server server;
    private Deployment deployment;
    private int port;

    @BeforeEach
    void files() throws IOException {
        Files.writeString(root.resolve("my file.txt"), "spaced");
        Files.writeString(root.resolve("café.txt"), "accented");
        Files.writeString(root.resolve("x.html"), "<html>x</html>");
        Files.createDirectories(root.resolve("WEB-INF"));
        Files.writeString(root.resolve("WEB-INF/web.xml"), "<web-app/>");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
    }

    private static ServletDecl servlet(String name, Class<? extends HttpServlet> type,
                                       java.util.function.Supplier<? extends HttpServlet> f, String... patterns) {
        return new ServletDecl(name, type, f, List.of(patterns), Map.of(), -1, true);
    }

    private void deploy(ErrorPageRegistry errors) {
        var b = WebAppModel.builder("/ctx")
                .servlet(servlet("exact", Paths.class, Paths::new, "/a b", "/a/b"))
                .servlet(servlet("prefix", Paths.class, Paths::new, "/p/*"))
                .servlet(servlet("fwd", Forwarder.class, Forwarder::new, "/fwd"))
                .servlet(servlet("bad", BadRequestPage.class, BadRequestPage::new, "/bad"));
        if (errors != null) b.errorPages(errors);
        deployment = WebAppDeployer.deploy(b.build(),
                DeployOptions.defaults(getClass().getClassLoader()).withResourceProvider(new DirProvider(root)));
        var r = TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    /** Sends the request target verbatim (no client-side normalisation or validation). */
    private Reply get(String target) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            s.getInputStream().transferTo(buf);
            String raw = buf.toString(StandardCharsets.UTF_8);
            int sp = raw.indexOf(' ');
            int status = Integer.parseInt(raw.substring(sp + 1, sp + 4));
            int bodyAt = raw.indexOf("\r\n\r\n");
            return new Reply(status, bodyAt < 0 ? "" : raw.substring(bodyAt + 4));
        }
    }

    @Test
    void defaultServletServesPercentEncodedAndUtf8Names() throws Exception {
        deploy(null);
        var spaced = get("/ctx/my%20file.txt");
        assertEquals(200, spaced.status(), spaced.body());
        assertEquals("spaced", spaced.body());
        var accented = get("/ctx/caf%C3%A9.txt");
        assertEquals(200, accented.status(), accented.body());
        assertEquals("accented", accented.body());
    }

    @Test
    void servletPathIsDecodedWhileTheRequestUriStaysRaw() throws Exception {
        deploy(null);
        var r = get("/ctx/a%20b");
        assertEquals(200, r.status(), r.body());
        assertEquals("SP=/a b|PI=null|URI=/ctx/a%20b|URL=http://localhost:" + port + "/ctx/a%20b", r.body());
        var prefix = get("/ctx/p/caf%C3%A9;v=1/x");
        assertEquals("SP=/p|PI=/café/x|URI=/ctx/p/caf%C3%A9;v=1/x|URL=http://localhost:" + port
                + "/ctx/p/caf%C3%A9;v=1/x", prefix.body());
    }

    @Test
    void dotSegmentsAndRepeatedSlashesAreNormalisedBeforeMapping() throws Exception {
        deploy(null);
        for (String p : new String[] {"/ctx/a/./b", "/ctx/a/x/../b", "/ctx//a//b", "/ctx/a;x=1/b"}) {
            var r = get(p);
            assertEquals(200, r.status(), p);
            assertTrue(r.body().startsWith("SP=/a/b|PI=null|URI=" + p + "|"), p + " -> " + r.body());
        }
    }

    @Test
    void invalidPathsAre400BeforeAnyServletRuns() throws Exception {
        deploy(null);
        for (String p : new String[] {"/ctx/..%2fWEB-INF/web.xml", "/ctx/WEB-INF%2fweb.xml", "/ctx/a%00b",
                "/ctx/a%5Cb", "/ctx/a\\b", "/ctx/%zz", "/ctx/../x", "/ctx/a/../../x", "/ctx/%2e%2e/WEB-INF/web.xml",
                "/ctx/a%C3%28"}) {
            var r = get(p);
            assertEquals(400, r.status(), p + " -> " + r.body());
            assertFalse(r.body().contains("web-app"), p);
        }
    }

    @Test
    void a400HonoursAStatusErrorPage() throws Exception {
        deploy(new ErrorPageRegistry().register(400, "/bad"));
        var r = get("/ctx/a%2fb");
        assertEquals(400, r.status(), r.body());
        assertEquals("BAD-REQUEST-PAGE 400", r.body());
    }

    @Test
    void pathParametersDoNotHideTheProtectedFirstSegment() throws Exception {
        deploy(null);
        for (String p : new String[] {"/ctx/WEB-INF;x=1/web.xml", "/ctx/WEB-INF/web.xml;x=1",
                "/ctx/%57EB-INF/web.xml", "/ctx/WEB-INF/./web.xml"}) {
            var r = get(p);
            assertEquals(404, r.status(), p);
            assertFalse(r.body().contains("web-app"), p);
        }
        // the canonical path is what the default servlet looks up: a parameter on the file is ignored
        assertEquals("<html>x</html>", get("/ctx/x.html;v=2").body());
    }

    @Test
    void forwardPathsAreNormalisedButNotDecoded() throws Exception {
        deploy(null);
        var r = get("/ctx/fwd?to=/views/../x.html");
        assertEquals(200, r.status(), r.body());
        assertEquals("<html>x</html>", r.body());
        assertEquals("NO-DISPATCHER", get("/ctx/fwd?to=/../x.html").body());
        var spaced = get("/ctx/fwd?to=/my%2520file.txt");
        assertEquals(404, spaced.status(), "the dispatch path is not decoded again: " + spaced.body());
        assertEquals("spaced", get("/ctx/fwd?to=/my%20file.txt").body());
    }
}
