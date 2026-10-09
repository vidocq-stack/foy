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
package io.vidocq.foy.internal.session;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.CookieConfigDef;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Session core (Servlet 6.1 chapter 7): id change, expiry, timeout, URL rewriting, cookie flags. */
class SessionCoreTest {

    /** Routes on the path info: {@code /s/<op>}. */
    public static class Ops extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String op = req.getPathInfo() == null ? "" : req.getPathInfo().substring(1);
            var out = resp.getWriter();
            switch (op) {
                case "create" -> {
                    HttpSession s = req.getSession(true);
                    s.setAttribute("a", "1");
                    out.write("ID=" + s.getId());
                }
                case "change" -> {
                    HttpSession s = req.getSession(false);
                    String old = s.getId();
                    long created = s.getCreationTime();
                    String fresh = req.changeSessionId();
                    out.write("OLD=" + old + "|NEW=" + fresh + "|ID=" + s.getId() + "|A=" + s.getAttribute("a")
                            + "|CT=" + (created == s.getCreationTime())
                            + "|SAME=" + (req.getSession(false) == s));
                }
                case "nochange" -> {
                    try {
                        req.changeSessionId();
                        out.write("NO-ISE");
                    } catch (IllegalStateException e) {
                        out.write("ISE");
                    }
                }
                case "timeout" -> out.write("MAX=" + req.getSession(true).getMaxInactiveInterval());
                case "encode" -> {
                    HttpSession s = req.getSession(true);
                    int port = req.getServerPort();
                    out.write("ID=" + s.getId()
                            + "|REL=" + resp.encodeURL("page?x=1#f")
                            + "|OTHER=" + resp.encodeURL("http://other.example/x")
                            + "|REDIR=" + resp.encodeRedirectURL("/ctx/a")
                            + "|OUT=" + resp.encodeURL("/other/b")
                            + "|ABS=" + resp.encodeURL("http://localhost:" + port + "/ctx/z#q"));
                }
                case "peek" -> {
                    HttpSession s = req.getSession(false);
                    out.write("REQ=" + req.getRequestedSessionId() + "|S=" + (s == null ? null : s.getId()));
                }
                case "source" -> {
                    HttpSession s = req.getSession(false);
                    out.write("REQ=" + req.getRequestedSessionId() + "|S=" + (s == null ? null : s.getId())
                            + "|COOKIE=" + req.isRequestedSessionIdFromCookie()
                            + "|URL=" + req.isRequestedSessionIdFromURL());
                }
                case "last" -> {
                    HttpSession s = req.getSession(true);
                    out.write("LA=" + s.getLastAccessedTime() + "|CT=" + s.getCreationTime());
                }
                default -> out.write("?");
            }
        }
    }

    /** Records id changes and session destructions. */
    public static class Recorder implements HttpSessionIdListener, HttpSessionListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
            events.add("changed:" + oldSessionId + "->" + event.getSession().getId());
        }
        @Override public void sessionDestroyed(HttpSessionEvent se) {
            events.add("destroyed:" + se.getSession().getId());
        }
    }

    record Reply(int status, String body, String head) {
        List<String> headers(String name) {
            var values = new ArrayList<String>();
            for (String line : head.split("\r\n")) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).equalsIgnoreCase(name)) values.add(line.substring(c + 1).trim());
            }
            return values;
        }
        String value(String key) {
            for (String part : body.split("\\|")) {
                if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
            }
            return null;
        }
    }

    private Server server;
    private Deployment deployment;
    private int port;
    private SessionManager manager;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
        if (manager != null) manager.close();
    }

    private static ServletDecl ops() {
        return new ServletDecl("ops", Ops.class, Ops::new, List.of("/s/*"), Map.of(), -1, true);
    }

    private void deploy(WebAppModel.Builder model) {
        deployment = WebAppDeployer.deploy(model.build(), DeployOptions.defaults(getClass().getClassLoader()));
        var r = TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private Reply get(String target, String cookie) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            String cookieLine = cookie == null ? "" : "Cookie: " + cookie + "\r\n";
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n" + cookieLine
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            s.getInputStream().transferTo(buf);
            String raw = buf.toString(StandardCharsets.UTF_8);
            int sp = raw.indexOf(' ');
            int status = Integer.parseInt(raw.substring(sp + 1, sp + 4));
            int bodyAt = raw.indexOf("\r\n\r\n");
            return new Reply(status, bodyAt < 0 ? "" : raw.substring(bodyAt + 4),
                    bodyAt < 0 ? raw : raw.substring(0, bodyAt));
        }
    }

    // ---- changeSessionId ----

    @Test
    void changeSessionIdKeepsAttributesAndFiresTheListener() throws Exception {
        var recorder = new Recorder();
        deploy(WebAppModel.builder("/ctx").servlet(ops())
                .listener(new ListenerDecl(Recorder.class, () -> recorder)));
        String id = get("/ctx/s/create", null).value("ID");

        var r = get("/ctx/s/change", "JSESSIONID=" + id);
        assertEquals(200, r.status(), r.body());
        String fresh = r.value("NEW");
        assertEquals(id, r.value("OLD"));
        assertNotEquals(id, fresh);
        assertEquals(fresh, r.value("ID"));
        assertEquals("1", r.value("A"));
        assertEquals("true", r.value("CT"));
        assertEquals("true", r.value("SAME"));
        assertEquals(List.of("changed:" + id + "->" + fresh), recorder.events);
        assertTrue(r.headers("Set-Cookie").stream().anyMatch(c -> c.startsWith("JSESSIONID=" + fresh)),
                r.head());

        // The new id resolves the same session; the old one is gone.
        assertEquals("1", get("/ctx/s/change", "JSESSIONID=" + fresh).value("A"));
        assertEquals("ISE", get("/ctx/s/nochange", "JSESSIONID=" + id).body());
    }

    @Test
    void changeSessionIdWithoutSessionThrows() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()));
        assertEquals("ISE", get("/ctx/s/nochange", null).body());
    }

    // ---- last accessed time ----

    @Test
    void lastAccessedTimeIsThePreviousRequestTime() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()));
        var first = get("/ctx/s/last", null);
        String cookie = first.headers("Set-Cookie").getFirst().split(";")[0];
        assertEquals(first.value("CT"), first.value("LA"), "a new session was last accessed at creation");
        long afterFirst = System.currentTimeMillis();
        Thread.sleep(30);
        long beforeSecond = System.currentTimeMillis();
        var second = get("/ctx/s/last", cookie);
        long la = Long.parseLong(second.value("LA"));
        assertTrue(la <= afterFirst, "last accessed " + la + " must be the previous request, before " + afterFirst);
        assertTrue(la < beforeSecond);
        Thread.sleep(30);
        var third = get("/ctx/s/last", cookie);
        assertTrue(Long.parseLong(third.value("LA")) >= beforeSecond, "the second request is now the last access");
    }

    // ---- expiry ----

    /** Records destruction with the attribute still readable, and the unbinding. */
    static final class Probe implements HttpSessionListener, HttpSessionBindingListener {
        final CountDownLatch destroyed = new CountDownLatch(1);
        final CountDownLatch unbound = new CountDownLatch(1);
        volatile Object attributeSeenOnDestroy;
        @Override public void sessionDestroyed(HttpSessionEvent se) {
            attributeSeenOnDestroy = se.getSession().getAttribute("probe");
            destroyed.countDown();
        }
        @Override public void valueUnbound(HttpSessionBindingEvent event) { unbound.countDown(); }
    }

    private SessionManager shortLived(Probe probe, InMemorySessionStore store) {
        var registry = new ListenerRegistry();
        registry.register(probe);
        manager = new SessionManager(store, new VidocqServletContext("/"), 1);
        manager.setListenerRegistry(registry);
        return manager;
    }

    @Test
    void reaperFiresSessionDestroyed() throws Exception {
        var probe = new Probe();
        var store = new InMemorySessionStore();
        var m = shortLived(probe, store);
        var s = m.createNew();
        s.setAttribute("probe", probe);
        m.startReaper(Duration.ofMillis(100));
        assertTrue(m.isReaperRunning());
        assertTrue(probe.destroyed.await(5, TimeUnit.SECONDS), "sessionDestroyed not fired by the reaper");
        assertTrue(probe.unbound.await(5, TimeUnit.SECONDS), "valueUnbound not fired by the reaper");
        assertSame(probe, probe.attributeSeenOnDestroy, "attributes are readable in sessionDestroyed");
        assertTrue(s.isInvalidated());
        assertEquals(0, store.size());
    }

    @Test
    void lazyExpiryFiresSessionDestroyed() throws Exception {
        var probe = new Probe();
        var store = new InMemorySessionStore();
        var m = shortLived(probe, store);
        var s = m.createNew();
        s.setAttribute("probe", probe);
        Thread.sleep(1_200);
        assertNull(m.find(s.getId()));
        assertEquals(0, probe.destroyed.getCount());
        assertEquals(0, probe.unbound.getCount());
        assertEquals(0, store.size());
    }

    @Test
    void aSessionInUseIsNotExpired() throws Exception {
        var probe = new Probe();
        var m = shortLived(probe, new InMemorySessionStore());
        var s = m.createNew();
        s.beginAccess();
        Thread.sleep(1_200);
        m.reap();
        assertFalse(s.isInvalidated(), "an in-flight request keeps its session alive");
        s.endAccess();
        Thread.sleep(1_200);
        m.reap();
        assertTrue(s.isInvalidated());
        assertEquals(0, probe.destroyed.getCount());
    }

    @Test
    void reaperPeriodFollowsTheTimeout() {
        assertEquals(Duration.ofSeconds(60), SessionManager.reaperPeriodFor(1800));
        assertEquals(Duration.ofSeconds(5), SessionManager.reaperPeriodFor(10));
        assertEquals(Duration.ofSeconds(1), SessionManager.reaperPeriodFor(1));
        assertEquals(Duration.ofSeconds(60), SessionManager.reaperPeriodFor(0));
    }

    @Test
    void undeployStopsTheReaper() throws Exception {
        var recorder = new Recorder();
        deploy(WebAppModel.builder("/ctx").servlet(ops())
                .listener(new ListenerDecl(Recorder.class, () -> recorder)));
        String id = get("/ctx/s/create", null).value("ID");
        SessionManager sessions = deployment.sessionManager();
        assertTrue(sessions.isReaperRunning());
        server.stop();
        server = null;
        deployment.close();
        deployment = null;
        assertFalse(sessions.isReaperRunning());
        assertEquals(List.of("destroyed:" + id), recorder.events, "live sessions are invalidated on undeploy");
    }

    // ---- session timeout ----

    @Test
    void sciSetSessionTimeoutIsHonoured() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops())
                .initializer((classes, ctx) -> ctx.setSessionTimeout(7)));
        assertEquals("MAX=420", get("/ctx/s/timeout", null).body());
    }

    public static class TimeoutListener implements ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent sce) {
            sce.getServletContext().setSessionTimeout(3);
        }
    }

    @Test
    void listenerSetSessionTimeoutIsHonoured() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()).sessionTimeoutMinutes(10)
                .listener(new ListenerDecl(TimeoutListener.class, TimeoutListener::new)));
        assertEquals("MAX=180", get("/ctx/s/timeout", null).body());
    }

    // ---- tracking modes and URL rewriting ----

    @Test
    void encodeUrlAddsJsessionidWhenUrlTrackingIsOn() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops())
                .trackingModes(EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)));
        var r = get("/ctx/s/encode", null);
        String id = r.value("ID");
        assertEquals("page;jsessionid=" + id + "?x=1#f", r.value("REL"));
        assertEquals("http://other.example/x", r.value("OTHER"));
        assertEquals("/ctx/a;jsessionid=" + id, r.value("REDIR"));
        assertEquals("/other/b", r.value("OUT"));
        assertEquals("http://localhost:" + port + "/ctx/z;jsessionid=" + id + "#q", r.value("ABS"));

        // Session known through the cookie: no rewriting.
        var viaCookie = get("/ctx/s/encode", "JSESSIONID=" + id);
        assertEquals(id, viaCookie.value("ID"));
        assertEquals("page?x=1#f", viaCookie.value("REL"));

        // Session known through the URL: rewriting continues.
        var viaUrl = get("/ctx/s/encode;jsessionid=" + id, null);
        assertEquals(id, viaUrl.value("ID"));
        assertEquals("page;jsessionid=" + id + "?x=1#f", viaUrl.value("REL"));
    }

    @Test
    void encodeUrlIsIdentityWithoutUrlTracking() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()).trackingModes(EnumSet.of(SessionTrackingMode.COOKIE)));
        var r = get("/ctx/s/encode", null);
        assertEquals("page?x=1#f", r.value("REL"));
        assertEquals("/ctx/a", r.value("REDIR"));
    }

    @Test
    void urlOnlyTrackingEmitsNoSessionCookie() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()).trackingModes(EnumSet.of(SessionTrackingMode.URL)));
        var r = get("/ctx/s/encode", null);
        assertNotNull(r.value("ID"));
        assertEquals(List.of(), r.headers("Set-Cookie"));
    }

    // ---- Secure cookie ----

    /** A request as Chappe would hand it, on a TLS connection or not. */
    record FakeRequest(String path, boolean isSecure) implements Request {
        @Override public HttpMethod method() { return HttpMethod.GET; }
        @Override public URI uri() { return URI.create(scheme() + "://localhost" + path); }
        @Override public String query() { return null; }
        @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
        @Override public Headers headers() { return Headers.of("Host", "localhost"); }
        @Override public Body body() { return Body.empty(); }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
        @Override public String scheme() { return isSecure ? "https" : "http"; }
    }

    private List<String> setCookies(boolean secure) throws Exception {
        return deployment.handler().handle(new FakeRequest("/ctx/s/create", secure)).headers().all("Set-Cookie");
    }

    @Test
    void secureRequestGetsSecureCookie() throws Exception {
        deployment = WebAppDeployer.deploy(WebAppModel.builder("/ctx").servlet(ops()).build(),
                DeployOptions.defaults(getClass().getClassLoader()));
        var secure = setCookies(true);
        assertEquals(1, secure.size(), secure.toString());
        assertTrue(secure.getFirst().contains("; Secure"), secure.getFirst());
        var plain = setCookies(false);
        assertEquals(1, plain.size(), plain.toString());
        assertFalse(plain.getFirst().contains("Secure"), plain.getFirst());
    }

    @Test
    void explicitlyInsecureCookieConfigWinsOnSecureRequests() throws Exception {
        var config = new CookieConfigDef(null, null, null, null, null, Boolean.FALSE, null, Map.of());
        deployment = WebAppDeployer.deploy(WebAppModel.builder("/ctx").servlet(ops()).cookieConfig(config).build(),
                DeployOptions.defaults(getClass().getClassLoader()));
        var secure = setCookies(true);
        assertEquals(1, secure.size(), secure.toString());
        assertFalse(secure.getFirst().contains("Secure"), secure.getFirst());
    }

    @Test
    void explicitlySecureCookieConfigAppliesToPlainRequests() throws Exception {
        deployment = WebAppDeployer.deploy(WebAppModel.builder("/ctx").servlet(ops())
                        .initializer((classes, ctx) -> ctx.getSessionCookieConfig().setSecure(true)).build(),
                DeployOptions.defaults(getClass().getClassLoader()));
        var plain = setCookies(false);
        assertTrue(plain.getFirst().contains("; Secure"), plain.getFirst());
    }

    // ---- fix round 1 ----

    @Test
    void defaultTrackingModesAreCookieAndUrl() {
        var ctx = new VidocqServletContext("/");
        assertEquals(EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL),
                ctx.getDefaultSessionTrackingModes());
        assertEquals(ctx.getDefaultSessionTrackingModes(), ctx.getEffectiveSessionTrackingModes());
    }

    @Test
    void defaultModesRewriteUrlsUntilTheClientSendsTheCookie() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()));
        var r = get("/ctx/s/encode", null);
        String id = r.value("ID");
        assertEquals("page;jsessionid=" + id + "?x=1#f", r.value("REL"));
        assertEquals(1, r.headers("Set-Cookie").size(), r.head());
        assertEquals("page?x=1#f", get("/ctx/s/encode", "JSESSIONID=" + id).value("REL"));
    }

    @Test
    void urlSessionIdIsIgnoredWithoutUrlTracking() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()).trackingModes(EnumSet.of(SessionTrackingMode.COOKIE)));
        String id = get("/ctx/s/create", null).value("ID");
        assertEquals("REQ=null|S=null", get("/ctx/s/peek;jsessionid=" + id, null).body());
        assertEquals("REQ=" + id + "|S=" + id, get("/ctx/s/peek", "JSESSIONID=" + id).body());
    }

    @Test
    void sessionCookieIsIgnoredWithoutCookieTracking() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()).trackingModes(EnumSet.of(SessionTrackingMode.URL)));
        String id = get("/ctx/s/create", null).value("ID");
        assertEquals("REQ=null|S=null", get("/ctx/s/peek", "JSESSIONID=" + id).body());
        assertEquals("REQ=" + id + "|S=" + id, get("/ctx/s/peek;jsessionid=" + id, null).body());
    }

    @Test
    void aClosedManagerCreatesNoSession() {
        manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
        manager.close();
        assertThrows(IllegalStateException.class, manager::createNew);
    }

    @Test
    void endingTheCreatingAccessKeepsTheSessionNew() {
        manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
        var s = manager.createNew();
        s.beginAccess();
        s.endAccess();
        assertTrue(s.isNew(), "the client has not joined the session yet");
        assertSame(s, manager.find(s.getId()));
        assertFalse(s.isNew());
    }

    @Test
    void zeroOrNegativeSessionTimeoutNeverExpires() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()).sessionTimeoutMinutes(0));
        assertEquals("MAX=-1", get("/ctx/s/timeout", null).body());
        tearDown();
        server = null;
        deployment = null;
        deploy(WebAppModel.builder("/ctx").servlet(ops()).sessionTimeoutMinutes(-5));
        assertEquals("MAX=-1", get("/ctx/s/timeout", null).body());
    }

    @Test
    void secureAttributeKeepsIsSecureConsistent() {
        var config = new VidocqSessionCookieConfig(new VidocqServletContext("/"));
        assertFalse(config.isSecureExplicit());
        config.setAttribute("Secure", "true");
        assertTrue(config.isSecure());
        assertTrue(config.isSecureExplicit());
        assertEquals("true", config.getAttribute("Secure"));
        config.setAttribute("Secure", "false");
        assertFalse(config.isSecure());
    }

    private SessionManager racing(List<String> events, HttpSessionListener destroyed,
                                  HttpSessionIdListener changed, InMemorySessionStore store) {
        var registry = new ListenerRegistry();
        registry.register(destroyed);
        registry.register(changed);
        manager = new SessionManager(store, new VidocqServletContext("/"), 1800);
        manager.setListenerRegistry(registry);
        return manager;
    }

    @Test
    void changeSessionIdIsRefusedWhileTheSessionIsBeingDestroyed() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var inDestroy = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new InMemorySessionStore();
        var m = racing(events, new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) {
                events.add("destroyed");
                inDestroy.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, (event, oldId) -> events.add("changed"), store);
        var s = m.createNew();
        Thread destroyer = Thread.ofVirtual().start(s::invalidate);
        assertTrue(inDestroy.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> m.changeSessionId(s));
        release.countDown();
        destroyer.join(5_000);
        assertEquals(List.of("destroyed"), events, "no sessionIdChanged on a session being destroyed");
        assertEquals(0, store.size(), "no store entry left under any id");
    }

    @Test
    void anInvalidationDuringSessionIdChangedWaitsForTheListener() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var inChange = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new InMemorySessionStore();
        var m = racing(events, new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) { events.add("destroyed"); }
        }, (event, oldId) -> {
            events.add("changed");
            inChange.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, store);
        var s = m.createNew();
        Thread changer = Thread.ofVirtual().start(() -> m.changeSessionId(s));
        assertTrue(inChange.await(5, TimeUnit.SECONDS));
        Thread destroyer = Thread.ofVirtual().start(s::invalidate);
        Thread.sleep(150);
        assertEquals(List.of("changed"), events, "the invalidation waits for sessionIdChanged");
        release.countDown();
        changer.join(5_000);
        destroyer.join(5_000);
        assertEquals(List.of("changed", "destroyed"), events);
        assertEquals(0, store.size(), "the session is not left under its new id");
    }

    // ---- fix round 2 ----

    @Test
    void theSessionCookieWinsOverAJsessionidPathParameter() throws Exception {
        deploy(WebAppModel.builder("/ctx").servlet(ops()));
        String cookieId = get("/ctx/s/create", null).value("ID");
        String urlId = get("/ctx/s/create", null).value("ID");
        assertNotEquals(cookieId, urlId);
        var r = get("/ctx/s/source;jsessionid=" + urlId, "JSESSIONID=" + cookieId);
        assertEquals("REQ=" + cookieId + "|S=" + cookieId + "|COOKIE=true|URL=false", r.body());
        // Without a session cookie, the path parameter applies.
        assertEquals("REQ=" + urlId + "|S=" + urlId + "|COOKIE=false|URL=true",
                get("/ctx/s/source;jsessionid=" + urlId, null).body());
    }

    @Test
    void closeWaitsForAnInFlightCreationAndInvalidatesIt() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var inCreated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = new ListenerRegistry();
        registry.register(new HttpSessionListener() {
            @Override public void sessionCreated(HttpSessionEvent se) {
                events.add("created");
                inCreated.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            @Override public void sessionDestroyed(HttpSessionEvent se) { events.add("destroyed"); }
        });
        var store = new InMemorySessionStore();
        manager = new SessionManager(store, new VidocqServletContext("/"), 1800);
        manager.setListenerRegistry(registry);
        Thread creator = Thread.ofVirtual().start(manager::createNew);
        assertTrue(inCreated.await(5, TimeUnit.SECONDS));
        Thread closer = Thread.ofVirtual().start(manager::close);
        Thread.sleep(150);
        assertEquals(List.of("created"), events, "close waits for the creation in flight");
        release.countDown();
        creator.join(5_000);
        closer.join(5_000);
        assertEquals(List.of("created", "destroyed"), events);
        assertEquals(0, store.size(), "no session survives the close");
        assertThrows(IllegalStateException.class, manager::createNew);
    }
}
