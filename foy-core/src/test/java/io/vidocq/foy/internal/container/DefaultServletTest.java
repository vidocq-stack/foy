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
import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The container default servlet (Servlet 6.1 section 12.2), end to end over HTTP. */
class DefaultServletTest {

    /** Forwards to the {@code to} parameter. */
    public static class Forwarder extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.getRequestDispatcher(req.getParameter("to")).forward(req, resp);
        }
    }

    /** Includes the {@code to} parameter between two markers; reports a missing include target. */
    public static class Includer extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.getWriter().write("before|");
            try {
                req.getRequestDispatcher(req.getParameter("to")).include(req, resp);
            } catch (FileNotFoundException e) {
                resp.getWriter().write("FNF");
            }
            resp.getWriter().write("|after");
        }
    }

    /** Reports the registrations visible to the application. */
    public static class Probe extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var ctx = req.getServletContext();
            resp.getWriter().write(new TreeSet<>(ctx.getServletRegistrations().keySet()) + "|"
                    + ctx.getServletRegistration(DefaultServlet.NAME));
        }
    }

    public static class Boom extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            throw new IllegalStateException("boom");
        }
    }

    public static class App extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("APP " + req.getServletPath());
        }
    }

    /** Writes its name, then continues the chain. */
    public static class Marker implements Filter {
        private final String mark;
        public Marker(String mark) { this.mark = mark; }
        @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            res.getWriter().write(mark + "|");
            chain.doFilter(req, res);
        }
    }

    /** A plain directory provider without any path check: the default servlet must apply them. */
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

    private static final Instant MTIME = Instant.parse("2026-01-02T03:04:05Z");
    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
    private static final String MTIME_HTTP = IMF_FIXDATE.format(MTIME.atOffset(ZoneOffset.UTC));

    @TempDir Path root;
    private Server server;
    private Deployment deployment;
    private int port;

    @BeforeEach
    void files() throws IOException {
        Files.writeString(root.resolve("dummy.html"), "<html>dummy</html>");
        Files.writeString(root.resolve("digits.txt"), "0123456789");
        Files.writeString(root.resolve("HTMLErrorPage.html"), "<html>error page</html>");
        Files.createDirectories(root.resolve("WEB-INF"));
        Files.writeString(root.resolve("WEB-INF/web.xml"), "<web-app/>");
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0");
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir/a.txt"), "a");
        Files.setLastModifiedTime(root.resolve("digits.txt"), FileTime.from(MTIME));
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

    private void deployStandard() {
        var errors = new ErrorPageRegistry().register(IllegalStateException.class, "/HTMLErrorPage.html");
        var b = WebAppModel.builder("/ctx")
                .servlet(servlet("fwd", Forwarder.class, Forwarder::new, "/fwd"))
                .servlet(servlet("inc", Includer.class, Includer::new, "/inc"))
                .servlet(servlet("probe", Probe.class, Probe::new, "/probe"))
                .servlet(servlet("boom", Boom.class, Boom::new, "/boom"))
                .filter(new FilterDecl("req", Marker.class, () -> new Marker("REQ"), Map.of(), true))
                .filter(new FilterDecl("fwdf", Marker.class, () -> new Marker("FWD"), Map.of(), true))
                .filter(new FilterDecl("incf", Marker.class, () -> new Marker("INC"), Map.of(), true))
                .filterMapping(new FilterMappingDecl("req", "/dummy.html", null, Set.of(DispatcherType.REQUEST)))
                .filterMapping(new FilterMappingDecl("fwdf", "/dummy.html", null, Set.of(DispatcherType.FORWARD)))
                .filterMapping(new FilterMappingDecl("incf", "/dummy.html", null, Set.of(DispatcherType.INCLUDE)))
                .errorPages(errors);
        deploy(b.build());
    }

    private void deploy(WebAppModel model) {
        deployment = WebAppDeployer.deploy(model,
                DeployOptions.defaults(getClass().getClassLoader()).withResourceProvider(new DirProvider(root)));
        var r = TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private HttpResponse<String> send(String method, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) b.headers(headers);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        return send("GET", path, headers);
    }

    @Test
    void unmappedStaticFileIsServedAfterTheRequestFilter() throws Exception {
        deployStandard();
        var r = get("/ctx/dummy.html");
        assertEquals(200, r.statusCode());
        assertEquals("REQ|<html>dummy</html>", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                r.headers().map().toString());
    }

    @Test
    void plainStaticFileCarriesLengthTypeAndValidators() throws Exception {
        deployStandard();
        var r = get("/ctx/digits.txt");
        assertEquals(200, r.statusCode());
        assertEquals("0123456789", r.body());
        assertEquals("10", r.headers().firstValue("Content-Length").orElse(null));
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertEquals(MTIME_HTTP, r.headers().firstValue("Last-Modified").orElse(null));
        assertEquals("W/\"10-" + MTIME.toEpochMilli() + "\"", r.headers().firstValue("ETag").orElse(null));
        assertEquals("bytes", r.headers().firstValue("Accept-Ranges").orElse(null));
    }

    @Test
    void forwardAndIncludeRunTheirOwnFilters() throws Exception {
        deployStandard();
        var fwd = get("/ctx/fwd?to=/dummy.html");
        assertEquals(200, fwd.statusCode());
        assertEquals("FWD|<html>dummy</html>", fwd.body());
        var inc = get("/ctx/inc?to=/dummy.html");
        assertEquals(200, inc.statusCode());
        assertEquals("before|INC|<html>dummy</html>|after", inc.body());
    }

    @Test
    void missingResourceIs404OnRequestAndForwardAndFileNotFoundOnInclude() throws Exception {
        deployStandard();
        assertEquals(404, get("/ctx/missing.html").statusCode());
        assertEquals(404, get("/ctx/fwd?to=/missing.html").statusCode());
        var inc = get("/ctx/inc?to=/missing.html");
        assertEquals(200, inc.statusCode());
        assertEquals("before|FNF|after", inc.body());
    }

    @Test
    void protectedTreesAre404() throws Exception {
        deployStandard();
        for (String p : new String[] {"/WEB-INF/web.xml", "/web-inf/web.xml", "/META-INF/MANIFEST.MF",
                "/meta-inf/MANIFEST.MF", "/WEB-INF/", "/fwd?to=/WEB-INF/web.xml"}) {
            var r = get("/ctx" + p);
            assertEquals(404, r.statusCode(), p);
            assertFalse(r.body().contains("web-app") || r.body().contains("Manifest"), p);
        }
    }

    @Test
    void directoriesAre404WithoutListing() throws Exception {
        deployStandard();
        for (String p : new String[] {"/dir", "/dir/", "/"}) {
            var r = get("/ctx" + p);
            assertEquals(404, r.statusCode(), p);
            assertFalse(r.body().contains("a.txt"), p);
        }
    }

    @Test
    void conditionalGetAnswers304ByDateAndByEtag() throws Exception {
        deployStandard();
        String etag = get("/ctx/digits.txt").headers().firstValue("ETag").orElseThrow();
        var byEtag = get("/ctx/digits.txt", "If-None-Match", etag);
        assertEquals(304, byEtag.statusCode());
        assertEquals("", byEtag.body());
        assertEquals(304, get("/ctx/digits.txt", "If-None-Match", "\"other\", " + etag.substring(2)).statusCode(),
                "weak comparison");
        assertEquals(304, get("/ctx/digits.txt", "If-None-Match", "*").statusCode());
        assertEquals(304, get("/ctx/digits.txt", "If-Modified-Since", MTIME_HTTP).statusCode());
        String earlier = IMF_FIXDATE.format(MTIME.minusSeconds(60).atOffset(ZoneOffset.UTC));
        assertEquals(200, get("/ctx/digits.txt", "If-Modified-Since", earlier).statusCode());
        assertEquals(200, get("/ctx/digits.txt", "If-None-Match", "\"other\"", "If-Modified-Since", MTIME_HTTP)
                .statusCode(), "If-None-Match wins over If-Modified-Since");
    }

    @Test
    void rangeRequests() throws Exception {
        deployStandard();
        var mid = get("/ctx/digits.txt", "Range", "bytes=2-4");
        assertEquals(206, mid.statusCode());
        assertEquals("234", mid.body());
        assertEquals("bytes 2-4/10", mid.headers().firstValue("Content-Range").orElse(null));
        assertEquals("3", mid.headers().firstValue("Content-Length").orElse(null));
        assertEquals("789", get("/ctx/digits.txt", "Range", "bytes=7-").body());
        assertEquals("789", get("/ctx/digits.txt", "Range", "bytes=-3").body());
        assertEquals("89", get("/ctx/digits.txt", "Range", "bytes=8-99").body());
        var bad = get("/ctx/digits.txt", "Range", "bytes=20-");
        assertEquals(416, bad.statusCode());
        assertEquals("bytes */10", bad.headers().firstValue("Content-Range").orElse(null));
        var multi = get("/ctx/digits.txt", "Range", "bytes=0-1,4-5");
        assertEquals(200, multi.statusCode());
        assertEquals("0123456789", multi.body());
        assertEquals(206, get("/ctx/digits.txt", "Range", "bytes=0-0", "If-Range", MTIME_HTTP).statusCode());
        String earlier = IMF_FIXDATE.format(MTIME.minusSeconds(60).atOffset(ZoneOffset.UTC));
        var stale = get("/ctx/digits.txt", "Range", "bytes=0-0", "If-Range", earlier);
        assertEquals(200, stale.statusCode());
        assertEquals("0123456789", stale.body());
    }

    @Test
    void headHasTheHeadersButNoBody() throws Exception {
        deployStandard();
        var r = send("HEAD", "/ctx/digits.txt");
        assertEquals(200, r.statusCode());
        assertEquals("", r.body());
        assertEquals("10", r.headers().firstValue("Content-Length").orElse(null));
        assertTrue(r.headers().firstValue("ETag").isPresent());
    }

    @Test
    void otherMethodsAre405WithAllow() throws Exception {
        deployStandard();
        var post = send("POST", "/ctx/digits.txt");
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD, OPTIONS", post.headers().firstValue("Allow").orElse(null));
        var options = send("OPTIONS", "/ctx/digits.txt");
        assertEquals(200, options.statusCode());
        assertEquals("GET, HEAD, OPTIONS", options.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void staticErrorPageLocationIsServed() throws Exception {
        deployStandard();
        var r = get("/ctx/boom");
        assertEquals(500, r.statusCode());
        assertEquals("<html>error page</html>", r.body());
    }

    @Test
    void containerDefaultIsNotARegistration() throws Exception {
        deployStandard();
        assertEquals("[boom, fwd, inc, probe]|null", get("/ctx/probe").body());
    }

    @Test
    void applicationMappingOfSlashWins() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(servlet("app", App.class, App::new, "/")).build());
        var r = get("/ctx/dummy.html");
        assertEquals(200, r.statusCode());
        assertEquals("APP /dummy.html", r.body());
    }

    @Test
    void servablePathsRejectUnsafeAndProtectedPaths() {
        for (String bad : new String[] {"/WEB-INF/web.xml", "/web-inf/x", "/Meta-Inf/x", "/WEB-INF", "/a/../b",
                "/./WEB-INF/web.xml", "/a//b", "/a\\b", "/a\0b", "/%2e%2e/WEB-INF/web.xml", "/a%2Fb", "/a%5cb",
                "/%2E/x", "/a%00b", "relative", "", null}) {
            assertFalse(ResourcePaths.isServable(bad), String.valueOf(bad));
        }
        for (String ok : new String[] {"/dummy.html", "/css/META-INF.css", "/a/b/c.txt", "/100%25.txt"}) {
            assertTrue(ResourcePaths.isServable(ok), ok);
        }
    }
}
