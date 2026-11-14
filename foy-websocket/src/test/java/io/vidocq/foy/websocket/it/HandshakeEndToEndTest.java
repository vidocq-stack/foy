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
package io.vidocq.foy.websocket.it;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployment and the handshake of WEBSOCKET.md section 5.3 on a real Foy and Chappe: the
 * container published by the initializer, the filter on {@code /*}, the configurator calls, the
 * origin policy and the session data, with the JDK client (or a raw socket where the test reads
 * the response head itself).
 */
@Timeout(60)
class HandshakeEndToEndTest {

    private static final String ALLOWED_ORIGINS = "io.vidocq.foy.websocket.allowed-origins";
    private static final String SETTING_NAME = ALLOWED_ORIGINS + ".setting-name";
    private static final String HTTP_SESSION_ID = "test.httpSessionId";
    private static final long WAIT = 5;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String SECURED = """
            <security-constraint>
              <web-resource-collection><web-resource-name>rooms</web-resource-name>
                <url-pattern>/secured/*</url-pattern></web-resource-collection>
              <auth-constraint><role-name>member</role-name></auth-constraint>
            </security-constraint>
            <login-config><auth-method>BASIC</auth-method></login-config>
            <security-role><role-name>member</role-name></security-role>""";

    /** The sessions {@link RecordingEndpoint} opened, in order. */
    static final BlockingQueue<Session> SESSIONS = new LinkedBlockingQueue<>();
    /** How many {@link EchoEndpoint} instances were created. */
    static final AtomicInteger INSTANCES = new AtomicInteger();

    private WebSocketTestServer server;

    @BeforeEach
    void reset() {
        SESSIONS.clear();
        INSTANCES.set(0);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    // ---------------------------------------------------------------- endpoints and configurators

    /** Echoes every text message; counts its instances. */
    public static class EchoEndpoint extends Endpoint {
        public EchoEndpoint() {
            INSTANCES.incrementAndGet();
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(String.class, text -> send(session, text));
        }
    }

    /** An echo endpoint that hands its session to the test. */
    public static final class RecordingEndpoint extends EchoEndpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            super.onOpen(session, config);
            SESSIONS.add(session);
        }
    }

    /** Sends what its configurator paired with it, as soon as it opens. */
    public static final class PairedEndpoint extends Endpoint {
        private final String paired;

        PairedEndpoint(String paired) {
            this.paired = paired;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            send(session, paired);
        }
    }

    /**
     * Pairs {@code modifyHandshake} and {@code getEndpointInstance} through a {@code ThreadLocal}, as
     * Atmosphere's configurator does: both calls must run on the same thread, for the same handshake.
     */
    public static final class PairingConfigurator extends ServerEndpointConfig.Configurator {
        private static final ThreadLocal<String> PAIRED = new ThreadLocal<>();

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            PAIRED.set(request.getRequestURI().getPath() + " on " + Thread.currentThread().threadId());
        }

        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) {
            String paired = PAIRED.get();
            PAIRED.remove();
            return endpointClass.cast(new PairedEndpoint(paired + " / " + Thread.currentThread().threadId()));
        }
    }

    /**
     * Reports the HTTP session id its configurator stored, as the configuration given to
     * {@code onOpen} and the session's user properties hold it: when it opens, then in reply to
     * every message ({@code "<config value>|<session value>"}).
     */
    public static final class UserPropertiesEndpoint extends Endpoint {
        private EndpointConfig openConfig;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            openConfig = config;
            send(session, report(session));
            session.addMessageHandler(String.class, text -> send(session, report(session)));
        }

        private String report(Session session) {
            return openConfig.getUserProperties().get(HTTP_SESSION_ID) + "|"
                    + session.getUserProperties().get(HTTP_SESSION_ID);
        }
    }

    /** Hands the HTTP session of the handshake to its endpoint; the endpoint instance is the container's. */
    public static final class HttpSessionConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            var httpSession = (HttpSession) request.getHttpSession();
            sec.getUserProperties().put(HTTP_SESSION_ID, httpSession == null ? "no session" : httpSession.getId());
        }
    }

    /** Adds a header to the 101. */
    public static final class HeaderConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            response.getHeaders().put("X-Handshake", List.of("modified"));
        }
    }

    /** Accepts every origin for its endpoint. */
    public static final class AnyOriginConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public boolean checkOrigin(String originHeaderValue) {
            return true;
        }
    }

    /** Not an {@code Endpoint}: an annotated endpoint, which lot 2 brings. */
    public static final class NotAnEndpoint {
    }

    // ---------------------------------------------------------------- servlets and filters

    /** Answers 200 with a fixed body. */
    public static final class PlainServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("plain");
        }
    }

    /** Creates the HTTP session and answers its id. */
    public static final class LoginServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write(req.getSession(true).getId());
        }
    }

    /** Answers from an async cycle, as Atmosphere's long-polling fallback does. */
    public static final class AsyncServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            AsyncContext async = req.startAsync();
            async.getResponse().getWriter().write("async");
            async.complete();
        }
    }

    /** Upgrades its request itself, through {@code ServerContainer.upgradeHttpToWebSocket}. */
    public static final class UpgradingServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            var config = ServerEndpointConfig.Builder.create(RecordingEndpoint.class, "/upgrade").build();
            try {
                container(getServletContext()).upgradeHttpToWebSocket(req, resp, config, Map.of("room", "servlet"));
            } catch (DeploymentException e) {
                throw new ServletException(e);
            }
        }
    }

    /** Creates the HTTP session of every request, before the WebSocket filter runs. */
    public static final class SessionFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            ((HttpServletRequest) request).getSession(true);
            chain.doFilter(request, response);
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void anEndpointAddedByAListenerEchoesThroughAFiveLevelTemplate() throws Exception {
        server = WebSocketTestServer.start(endpoints(config(EchoEndpoint.class,
                "/{path0}/{path1}/{path2}/{path3}/{path4}")));
        var client = new Client();

        WebSocket ws = client.connect("/a/b/c/d/e");
        ws.sendText("hello", true).get(WAIT, SECONDS);

        assertEquals("hello", client.next());
    }

    @Test
    void anAtmosphereStyleConfiguratorPairsItsCallsThroughAThreadLocal() throws Exception {
        server = WebSocketTestServer.start(endpoints(ServerEndpointConfig.Builder
                .create(PairedEndpoint.class, "/paired").configurator(new PairingConfigurator()).build()));
        var client = new Client();

        client.connect("/paired");

        String paired = client.next();
        assertTrue(paired.matches("/paired on (\\d+) / \\1"), paired);
    }

    @Test
    void theHandshakeSeesTheHttpSession() throws Exception {
        server = WebSocketTestServer.start(sessionAwareEndpoint(httpSessionConfig()));
        HttpResponse<String> login = login();
        var client = new Client();

        client.connect("/session", "Cookie", cookie(login));

        assertEquals(login.body() + "|" + login.body(), client.next());
    }

    @Test
    void theUserPropertiesArePerConnection() throws Exception {
        ServerEndpointConfig registered = httpSessionConfig();
        server = WebSocketTestServer.start(sessionAwareEndpoint(registered));
        HttpResponse<String> alice = login();
        HttpResponse<String> bob = login();
        var first = new Client();
        var second = new Client();

        WebSocket aliceSocket = first.connect("/session", "Cookie", cookie(alice));
        String aliceAtOpen = first.next();
        second.connect("/session", "Cookie", cookie(bob));
        String bobAtOpen = second.next();
        aliceSocket.sendText("after the second handshake", true).get(WAIT, SECONDS);

        assertEquals(alice.body() + "|" + alice.body(), aliceAtOpen);
        assertEquals(bob.body() + "|" + bob.body(), bobAtOpen);
        assertEquals(alice.body() + "|" + alice.body(), first.next(), "the first connection keeps its own value");
        assertEquals(Map.of(), registered.getUserProperties(), "a handshake never writes the registered config");
    }

    @Test
    void aContextPathAndAQueryStringReachTheSession() throws Exception {
        server = WebSocketTestServer.builder(onStart(ctx -> {
            // As Vaadin: a servlet on /*, so the servlet path is empty and the path info holds the path.
            ctx.addServlet("vaadin", new PlainServlet()).addMapping("/*");
            container(ctx).addEndpoint(config(RecordingEndpoint.class, "/{path0}/{path1}"));
        })).contextPath("/ctx").start();

        new Client().connect("/ctx/VAADIN/push?v-uiId=0&X-Atmosphere-Transport=websocket");

        Session session = nextSession();
        URI uri = session.getRequestURI();
        assertEquals("ws", uri.getScheme());
        assertEquals("/ctx/VAADIN/push", uri.getPath());
        assertEquals("v-uiId=0&X-Atmosphere-Transport=websocket", uri.getQuery());
        assertEquals("v-uiId=0&X-Atmosphere-Transport=websocket", session.getQueryString());
        assertEquals(List.of("0"), session.getRequestParameterMap().get("v-uiId"));
        assertEquals(Map.of("path0", "VAADIN", "path1", "push"), session.getPathParameters());
    }

    @Test
    void anAsyncServletBehindTheFilterStillStartsAsync() throws Exception {
        server = WebSocketTestServer.start(onStart(ctx -> {
            var registration = ctx.addServlet("async", new AsyncServlet());
            registration.setAsyncSupported(true);
            registration.addMapping("/async");
        }));

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(server.uri("http", "/async")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("async", response.body());
    }

    @Test
    void aRequestMatchingNoEndpointPassesThrough() throws Exception {
        server = WebSocketTestServer.start(onStart(ctx -> {
            ctx.addServlet("plain", new PlainServlet()).addMapping("/plain");
            container(ctx).addEndpoint(config(EchoEndpoint.class, "/ws"));
        }));

        assertEquals(200, refusedStatus(new Client().attempt("/plain")));
    }

    @Test
    void anotherOriginIs403AndLogged() throws Exception {
        server = WebSocketTestServer.start(endpoints(config(EchoEndpoint.class, "/ws")));

        try (var warnings = new LogLines(Level.WARNING)) {
            String head = RawHandshake.send(server.port(), "/ws", "Origin: http://evil.example");

            assertTrue(head.startsWith("HTTP/1.1 403 "), head);
            assertTrue(head.toLowerCase(Locale.ROOT).contains("\r\ncontent-length: 0\r\n"), head);
            assertTrue(warnings.messages().stream()
                    .anyMatch(m -> m.contains("http://evil.example") && m.contains(ALLOWED_ORIGINS)),
                    warnings.messages()::toString);
        }
        assertEquals(0, INSTANCES.get());
    }

    @Test
    void theRefusalNamesTheSettingTheRuntimeReports() throws Exception {
        server = WebSocketTestServer.builder(endpoints(config(EchoEndpoint.class, "/ws")))
                .initParameter(ALLOWED_ORIGINS, "https://admin.example.com")
                .initParameter(SETTING_NAME, "app.ws.origins").start();

        try (var warnings = new LogLines(Level.WARNING)) {
            String head = RawHandshake.send(server.port(), "/ws", "Origin: http://evil.example");

            assertTrue(head.startsWith("HTTP/1.1 403 "), head);
            assertTrue(warnings.messages().stream()
                    .anyMatch(m -> m.contains("http://evil.example") && m.contains("app.ws.origins")
                            && !m.contains(ALLOWED_ORIGINS)),
                    warnings.messages()::toString);
        }
    }

    @Test
    void aMalformedEntryNamesTheSettingTheRuntimeReports() {
        var builder = WebSocketTestServer.builder(endpoints())
                .initParameter(ALLOWED_ORIGINS, "ftp://files.example").initParameter(SETTING_NAME, "app.ws.origins");

        var failure = assertThrows(ServletException.class, builder::start);

        assertTrue(messages(failure).contains("(in app.ws.origins)"), messages(failure));
        assertFalse(messages(failure).contains(ALLOWED_ORIGINS), messages(failure));
    }

    @Test
    void noOriginIsAccepted() throws Exception {
        server = WebSocketTestServer.start(endpoints(config(EchoEndpoint.class, "/ws")));

        String head = RawHandshake.send(server.port(), "/ws");

        assertTrue(head.startsWith("HTTP/1.1 101 "), head);
    }

    /**
     * A {@code Host} that is not {@code host[:port]} would move the path of {@code Session.getRequestURI()}
     * away from the one matched and authorized: 400, logged at DEBUG without the query.
     */
    @Test
    void aHostThatIsNotAnAuthorityIs400() throws Exception {
        server = WebSocketTestServer.start(endpoints(config(EchoEndpoint.class, "/ws")));

        try (var debug = new LogLines(Level.FINE)) {
            String head = RawHandshake.sendWithHost(server.port(), "x/admin?", "/ws?v-pushId=secret");

            assertTrue(head.startsWith("HTTP/1.1 400 "), head);
            assertTrue(head.toLowerCase(Locale.ROOT).contains("\r\ncontent-length: 0\r\n"), head);
            assertTrue(debug.messages().stream().anyMatch(m -> m.contains("\"/ws\"") && m.contains("x/admin?")),
                    debug.messages()::toString);
            assertTrue(debug.messages().stream().noneMatch(m -> m.contains("secret")), debug.messages()::toString);
        }
        assertEquals(0, INSTANCES.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.example.com:8443", "[::1]:8080", "[2001:db8::1]"})
    void aHostWithAPortOrAnIpv6LiteralIsAccepted(String host) throws Exception {
        server = WebSocketTestServer.start(endpoints(config(RecordingEndpoint.class, "/ws")));

        String head = RawHandshake.sendWithHost(server.port(), host, "/ws");

        assertTrue(head.startsWith("HTTP/1.1 101 "), head);
        assertEquals("/ws", nextSession().getRequestURI().getPath());
    }

    /** A request target {@code java.net.URI} refuses is 400, logged at DEBUG with the reason and without the query. */
    @Test
    void aRequestTargetThatIsNotAUriIs400() throws Exception {
        server = WebSocketTestServer.start(endpoints(config(EchoEndpoint.class, "/ws")));

        try (var debug = new LogLines(Level.FINE)) {
            String head = RawHandshake.send(server.port(), "/ws?v-pushId=se|cret");

            assertTrue(head.startsWith("HTTP/1.1 400 "), head);
            assertTrue(debug.messages().stream()
                            .anyMatch(m -> m.contains("\"/ws\"") && m.contains("Illegal character in query")),
                    debug.messages()::toString);
            assertTrue(debug.messages().stream().noneMatch(m -> m.contains("cret")), debug.messages()::toString);
        }
        assertEquals(0, INSTANCES.get());
    }

    @Test
    void theInitParameterOpensAListedOriginAndStar() throws Exception {
        server = WebSocketTestServer.builder(endpoints(config(EchoEndpoint.class, "/ws")))
                .initParameter(ALLOWED_ORIGINS, "https://admin.example.com, http://localhost:5173").start();

        assertNotNull(new Client().connect("/ws", "Origin", "https://admin.example.com"));
        assertEquals(403, refusedStatus(new Client().attempt("/ws", "Origin", "https://other.example.com")));

        server.close();
        server = WebSocketTestServer.builder(endpoints(config(EchoEndpoint.class, "/ws")))
                .initParameter(ALLOWED_ORIGINS, "*").start();

        assertNotNull(new Client().connect("/ws", "Origin", "https://other.example.com"));
    }

    @Test
    void aMalformedInitParameterStopsTheDeployment() {
        var builder = WebSocketTestServer.builder(endpoints()).initParameter(ALLOWED_ORIGINS, "ftp://files.example");

        var failure = assertThrows(ServletException.class, builder::start);

        assertTrue(messages(failure).contains(ALLOWED_ORIGINS), messages(failure));
    }

    @Test
    void theSessionCookieAndAModifyHandshakeHeaderReachThe101() throws Exception {
        server = WebSocketTestServer.start(onStart(ctx -> {
            ctx.addFilter("session", new SessionFilter()).addMappingForUrlPatterns(null, false, "/*");
            container(ctx).addEndpoint(ServerEndpointConfig.Builder.create(EchoEndpoint.class, "/ws")
                    .configurator(new HeaderConfigurator()).build());
        }));

        String head = RawHandshake.send(server.port(), "/ws");

        assertTrue(head.startsWith("HTTP/1.1 101 "), head);
        String lower = head.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("\r\nset-cookie: jsessionid="), head);
        assertTrue(lower.contains("\r\nx-handshake: modified\r\n"), head);
    }

    @Test
    void aSecurityConstraintRefusesTheHandshake() throws Exception {
        server = WebSocketTestServer.builder(endpoints(config(EchoEndpoint.class, "/secured/rooms/{room}")))
                .descriptor(SECURED).user("alice", "secret", "member").start();

        int status = refusedStatus(new Client().attempt("/secured/rooms/r1"));

        assertTrue(status == 401 || status == 403, "status " + status);
        assertEquals(0, INSTANCES.get());
    }

    @Test
    void aDuplicatePathThrowsDeploymentException() throws Exception {
        server = WebSocketTestServer.start(endpoints(config(EchoEndpoint.class, "/rooms/{room}")));
        ServerContainer container = container(server.context());
        var duplicate = config(EchoEndpoint.class, "/rooms/{name}");

        assertThrows(DeploymentException.class, () -> container.addEndpoint(duplicate));
    }

    @Test
    void annotatedEndpointsAndTheClientApiThrowDeploymentException() throws Exception {
        server = WebSocketTestServer.start(endpoints());
        ServerContainer container = container(server.context());
        var notAnEndpoint = config(NotAnEndpoint.class, "/annotated");
        var endpoint = new EchoEndpoint();
        var clientConfig = ClientEndpointConfig.Builder.create().build();
        URI uri = server.uri("ws", "/ws");

        assertTrue(assertThrows(DeploymentException.class, () -> container.addEndpoint(notAnEndpoint))
                .getMessage().contains("lot 2"));
        assertTrue(assertThrows(DeploymentException.class, () -> container.addEndpoint(EchoEndpoint.class))
                .getMessage().contains("lot 2"));
        assertTrue(assertThrows(DeploymentException.class, () -> container.connectToServer(endpoint, uri))
                .getMessage().contains("lot 3"));
        assertTrue(assertThrows(DeploymentException.class, () -> container.connectToServer(EchoEndpoint.class, uri))
                .getMessage().contains("lot 3"));
        assertTrue(assertThrows(DeploymentException.class,
                () -> container.connectToServer(endpoint, clientConfig, uri)).getMessage().contains("lot 3"));
        assertTrue(assertThrows(DeploymentException.class,
                () -> container.connectToServer(EchoEndpoint.class, clientConfig, uri)).getMessage().contains("lot 3"));
        assertEquals(Set.of(), container.getInstalledExtensions());
    }

    @Test
    void addEndpointWorksAfterDeployment() throws Exception {
        server = WebSocketTestServer.start(endpoints());
        container(server.context()).addEndpoint(config(EchoEndpoint.class, "/late"));
        var client = new Client();

        WebSocket ws = client.connect("/late");
        ws.sendText("late", true).get(WAIT, SECONDS);

        assertEquals("late", client.next());
    }

    @Test
    void anApplicationConfiguratorDecidesTheOrigin() throws Exception {
        server = WebSocketTestServer.start(endpoints(ServerEndpointConfig.Builder.create(EchoEndpoint.class, "/ws")
                .configurator(new AnyOriginConfigurator()).build()));
        var client = new Client();

        WebSocket ws = client.connect("/ws", "Origin", "https://elsewhere.example");
        ws.sendText("open", true).get(WAIT, SECONDS);

        assertEquals("open", client.next());
    }

    @Test
    void theSessionReportsTheHandshakeData() throws Exception {
        var rooms = ServerEndpointConfig.Builder.create(RecordingEndpoint.class, "/secured/rooms/{room}")
                .subprotocols(List.of("chat")).build();
        server = WebSocketTestServer.builder(endpoints(rooms))
                .descriptor(SECURED).user("alice", "secret", "member").start();
        String basic = "Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));

        WebSocket first = new Client().connect(HTTP.newWebSocketBuilder().header("Authorization", basic)
                .subprotocols("other", "chat"), "/secured/rooms/r1");
        Session one = nextSession();
        new Client().connect(HTTP.newWebSocketBuilder().header("Authorization", basic)
                .subprotocols("chat"), "/secured/rooms/r2");
        Session two = nextSession();

        assertEquals("chat", first.getSubprotocol());
        assertEquals(Map.of("room", "r1"), one.getPathParameters());
        assertEquals(Map.of("room", "r2"), two.getPathParameters());
        assertEquals("alice", one.getUserPrincipal().getName());
        assertEquals("13", one.getProtocolVersion());
        assertNotEquals(one.getId(), two.getId());
        assertEquals("chat", one.getNegotiatedSubprotocol());
        assertEquals(List.of(), one.getNegotiatedExtensions());
        assertTrue(one.isOpen());
        assertEquals(Set.of(one, two), one.getOpenSessions());
    }

    @Test
    void upgradeHttpToWebSocketFromAServlet() throws Exception {
        server = WebSocketTestServer.start(onStart(ctx ->
                ctx.addServlet("upgrade", new UpgradingServlet()).addMapping("/upgrade")));
        var client = new Client();

        WebSocket ws = client.connect("/upgrade");
        ws.sendText("upgraded", true).get(WAIT, SECONDS);

        assertEquals("upgraded", client.next());
        assertEquals(Map.of("room", "servlet"), nextSession().getPathParameters());
    }

    // ---------------------------------------------------------------- helpers

    /** What a test does when the application starts. */
    @FunctionalInterface
    interface Setup {
        void run(ServletContext context) throws DeploymentException;
    }

    private static ServletContextListener onStart(Setup setup) {
        return new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                try {
                    setup.run(event.getServletContext());
                } catch (DeploymentException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static ServletContextListener endpoints(ServerEndpointConfig... configs) {
        return onStart(ctx -> {
            for (ServerEndpointConfig config : configs) {
                container(ctx).addEndpoint(config);
            }
        });
    }

    /** {@code /session}: a {@link UserPropertiesEndpoint} whose configurator stores the HTTP session id. */
    private static ServerEndpointConfig httpSessionConfig() {
        return ServerEndpointConfig.Builder.create(UserPropertiesEndpoint.class, "/session")
                .configurator(new HttpSessionConfigurator()).build();
    }

    /** A login servlet on {@code /login}, which creates the HTTP session, and the endpoint {@code config}. */
    private static ServletContextListener sessionAwareEndpoint(ServerEndpointConfig config) {
        return onStart(ctx -> {
            ctx.addServlet("login", new LoginServlet()).addMapping("/login");
            container(ctx).addEndpoint(config);
        });
    }

    /** A new HTTP session: its id is the body. */
    private HttpResponse<String> login() throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(server.uri("http", "/login")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The {@code name=value} of the session cookie {@code login} set. */
    private static String cookie(HttpResponse<String> login) {
        return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static ServerEndpointConfig config(Class<?> endpointClass, String path) {
        return ServerEndpointConfig.Builder.create(endpointClass, path).build();
    }

    private static ServerContainer container(ServletContext context) {
        return (ServerContainer) context.getAttribute(ServerContainer.class.getName());
    }

    private static void send(Session session, String text) {
        try {
            session.getBasicRemote().sendText(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Session nextSession() throws InterruptedException {
        Session session = SESSIONS.poll(WAIT, SECONDS);
        assertNotNull(session, "no session opened within " + WAIT + " s");
        return session;
    }

    /** The status of a handshake the JDK client saw refused. */
    private static int refusedStatus(CompletableFuture<WebSocket> attempt) {
        var failure = assertThrows(ExecutionException.class, () -> attempt.get(WAIT, SECONDS));
        return assertInstanceOf(WebSocketHandshakeException.class, failure.getCause()).getResponse().statusCode();
    }

    /** Every message of the cause chain, for a check that must not depend on the wrapping. */
    private static String messages(Throwable failure) {
        var all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t).append('\n');
        }
        return all.toString();
    }

    /** A JDK client connection that queues the text messages it receives. */
    private final class Client implements WebSocket.Listener {
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        /** Starts a handshake with the given header name and value pairs. */
        CompletableFuture<WebSocket> attempt(String path, String... headerPairs) {
            WebSocket.Builder builder = HTTP.newWebSocketBuilder();
            for (int i = 0; i < headerPairs.length; i += 2) {
                builder.header(headerPairs[i], headerPairs[i + 1]);
            }
            return builder.buildAsync(server.uri("ws", path), this);
        }

        WebSocket connect(String path, String... headerPairs) throws Exception {
            return attempt(path, headerPairs).get(WAIT, SECONDS);
        }

        WebSocket connect(WebSocket.Builder builder, String path) throws Exception {
            return builder.buildAsync(server.uri("ws", path), this).get(WAIT, SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                texts.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        String next() throws InterruptedException {
            String text = texts.poll(WAIT, SECONDS);
            assertNotNull(text, "no message within " + WAIT + " s");
            return text;
        }
    }

    /**
     * Collects the records of one level of the {@code io.vidocq.foy.websocket} loggers, the logger
     * opened to that level meanwhile ({@code DEBUG} is {@code FINE}).
     */
    private static final class LogLines extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger("io.vidocq.foy.websocket");
        private final Level previous = logger.getLevel();
        private final Level level;
        private final List<String> messages = new CopyOnWriteArrayList<>();

        LogLines(Level level) {
            this.level = level;
            logger.setLevel(level);
            logger.addHandler(this);
        }

        List<String> messages() {
            return messages;
        }

        @Override
        public void publish(LogRecord logRecord) {
            if (level.equals(logRecord.getLevel())) {
                messages.add(new SimpleFormatter().formatMessage(logRecord));
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(previous);
        }
    }
}
