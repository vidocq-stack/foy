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
package io.vidocq.foy.websocket;

import io.vidocq.foy.spi.gen.ComponentInstantiator;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The WebSocket container of one web application: its endpoints, the handshake that opens them
 * (WEBSOCKET.md section 5.3) and the defaults of its sessions. Endpoints may be added during the
 * deployment and afterwards, since Vaadin may start Atmosphere at its first request.
 *
 * <p>Lot 1 serves programmatic endpoints (subclasses of {@link Endpoint}); annotated endpoints
 * come with lot 2 and the client API with lot 3: their entry points throw
 * {@link DeploymentException}.
 */
final class FoyServerContainer implements ServerContainer {

    private static final System.Logger LOG = System.getLogger(FoyServerContainer.class.getName());

    /** The default maximum size of a text or a binary message: 64 KiB. */
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 65_536;

    private static final int SWITCHING_PROTOCOLS = 101;
    private static final int BAD_REQUEST = 400;
    private static final int FORBIDDEN = 403;
    private static final int INTERNAL_SERVER_ERROR = 500;

    private final EndpointRegistry endpoints = new EndpointRegistry();
    private final ServerEndpointConfig.Configurator defaultConfigurator = new DefaultConfigurator();
    private final OriginPolicy origins;
    private final ComponentInstantiator instantiator;
    private final AtomicLong sessionIds = new AtomicLong();
    private final Map<ServerEndpointConfig, Set<FoySession>> openSessions = new ConcurrentHashMap<>();
    private volatile long asyncSendTimeout;
    private volatile long maxSessionIdleTimeout;
    private volatile int maxTextMessageBufferSize = DEFAULT_MAX_MESSAGE_SIZE;
    private volatile int maxBinaryMessageBufferSize = DEFAULT_MAX_MESSAGE_SIZE;

    /**
     * @param origins      the origin policy of the container default configurator
     * @param instantiator the web application's component factory, {@code null} when there is none
     *                     (endpoint instances then fail with 500)
     */
    FoyServerContainer(OriginPolicy origins, ComponentInstantiator instantiator) {
        this.origins = origins;
        this.instantiator = instantiator;
    }

    // ---------------------------------------------------------------- endpoints

    /** Not in lot 1: annotated endpoints come with lot 2. */
    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException {
        throw new DeploymentException("Annotated endpoints are not supported yet (Foy WebSocket lot 2): "
                + endpointClass.getName());
    }

    /**
     * Registers a programmatic endpoint, during the deployment or later.
     *
     * @throws DeploymentException when the class is not an {@link Endpoint}, or the path is invalid
     *                             or equivalent to one already registered
     */
    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException {
        requireEndpoint(config);
        endpoints.add(config);
    }

    /**
     * Runs the handshake of {@code httpServletRequest} for {@code sec}, as the filter does for a
     * registered endpoint.
     *
     * @throws IllegalArgumentException when the request and the response are not HTTP ones
     * @throws DeploymentException      when the class is not an {@link Endpoint}, or the upgrade fails
     */
    @Override
    public void upgradeHttpToWebSocket(Object httpServletRequest, Object httpServletResponse,
                                       ServerEndpointConfig sec, Map<String, String> pathParameters)
            throws IOException, DeploymentException {
        if (!(httpServletRequest instanceof HttpServletRequest request)
                || !(httpServletResponse instanceof HttpServletResponse response)) {
            throw new IllegalArgumentException("upgradeHttpToWebSocket takes an HttpServletRequest and an "
                    + "HttpServletResponse");
        }
        requireEndpoint(sec);
        try {
            handshake(request, response,
                    new EndpointMatch(sec, pathParameters == null ? Map.of() : Map.copyOf(pathParameters)));
        } catch (ServletException e) {
            throw new DeploymentException("The WebSocket upgrade failed", e);
        }
    }

    /** The endpoint of a decoded context-relative path, {@code null} when none matches. */
    EndpointMatch match(String contextRelativePath) {
        return endpoints.match(contextRelativePath);
    }

    private static void requireEndpoint(ServerEndpointConfig config) throws DeploymentException {
        if (!Endpoint.class.isAssignableFrom(config.getEndpointClass())) {
            throw new DeploymentException(config.getEndpointClass().getName() + " does not extend "
                    + Endpoint.class.getName() + ": annotated endpoints are not supported yet (Foy WebSocket lot 2)");
        }
    }

    // ---------------------------------------------------------------- handshake

    /** What the configurator calls of a handshake decided. */
    private record Negotiated(String subprotocol, Endpoint endpoint) {
    }

    /**
     * Answers the handshake of {@code request} for {@code match} (WEBSOCKET.md section 5.3, steps 4
     * to 7). A refusal sets the status, and the header of a 426, with an empty body: no error page
     * runs. An accepted handshake upgrades the request; the transport writes the 101.
     */
    void handshake(HttpServletRequest request, HttpServletResponse response, EndpointMatch match)
            throws IOException, ServletException {
        Handshake.Refusal refusal = Handshake.validate(request);
        if (refusal != null) {
            response.setStatus(refusal.status());
            if (refusal.header() != null) {
                response.setHeader(refusal.header(), refusal.value());
            }
            return;
        }
        HandshakeRequestImpl handshakeRequest;
        try {
            handshakeRequest = new HandshakeRequestImpl(request);
        } catch (IllegalArgumentException e) {
            // A Host that is not host[:port] (RFC 9112 section 3.2), or a target that is not a URI. The
            // line names the path and the reason, never the query.
            LOG.log(System.Logger.Level.DEBUG, () -> "WebSocket handshake on "
                    + OriginPolicy.printable(request.getRequestURI()) + " refused (400): " + e.getMessage());
            response.setStatus(BAD_REQUEST);
            return;
        }
        // This connection's view of the registered config, with its own user properties.
        var config = new ConnectionConfig(match.config());
        String origin = request.getHeader("Origin");
        var handshakeResponse = new HandshakeResponseImpl();
        Negotiated negotiated;
        try {
            negotiated = ScopedValue.where(HandshakeScope.CURRENT, HandshakeScope.of(request, instantiator, origins))
                    .call(() -> negotiate(config, origin, handshakeRequest, handshakeResponse));
        } catch (InstantiationException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "WebSocket handshake on " + config.getPath()
                    + " failed (500): no instance of " + config.getEndpointClass().getName(), e);
            response.setStatus(INTERNAL_SERVER_ERROR);
            return;
        }
        if (negotiated == null) {
            refuseOrigin(request, response, origin, origins.settingName());
            return;
        }
        handshakeResponse.getHeaders()
                .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setStatus(SWITCHING_PROTOCOLS);
        // The upgrade handler owns the session from here on: it is handed over, not closed.
        request.upgrade(FoyUpgradeHandler.class).prepare(new FoySession(this, config, negotiated.endpoint(),
                handshakeRequest, match.pathParameters(), negotiated.subprotocol()));
    }

    /**
     * A 403 and one WARNING naming the origin and the setting that would allow it, so a page
     * silently falling back to long polling shows in the log (WEBSOCKET.md section 5.3.1).
     */
    private static void refuseOrigin(HttpServletRequest request, HttpServletResponse response, String origin,
            String settingName) {
        LOG.log(System.Logger.Level.WARNING, "WebSocket handshake on {0} refused (403): origin {1} is not "
                        + "allowed. Foy allows the server origin and the origins listed in {2} "
                        + "(comma-separated, or * for any)",
                OriginPolicy.printable(request.getRequestURI()),
                origin == null ? "(none)" : OriginPolicy.printable(origin), settingName);
        response.setStatus(FORBIDDEN);
    }

    /**
     * The configurator calls, in the order of WEBSOCKET.md section 5.3, on the request thread and
     * within the handshake scope: Atmosphere pairs {@code modifyHandshake} and
     * {@code getEndpointInstance} through a {@code ThreadLocal}. {@code modifyHandshake} gets the
     * connection's config, so what it stores in the user properties stays with this connection.
     *
     * @return what they decided, {@code null} when the origin is refused
     * @throws InstantiationException when no endpoint instance can be created
     */
    private Negotiated negotiate(ConnectionConfig config, String origin, HandshakeRequestImpl request,
                                 HandshakeResponseImpl response) throws InstantiationException {
        ServerEndpointConfig.Configurator configurator =
                config.getConfigurator() != null ? config.getConfigurator() : defaultConfigurator;
        if (!configurator.checkOrigin(origin)) {
            return null;
        }
        String subprotocol = configurator.getNegotiatedSubprotocol(config.getSubprotocols(),
                request.requestedSubprotocols());
        // Foy installs no extension: whatever the configurator answers, none is negotiated.
        configurator.getNegotiatedExtensions(List.of(), request.requestedExtensions());
        configurator.modifyHandshake(config, request, response);
        Object instance = configurator.getEndpointInstance(config.getEndpointClass());
        if (!(instance instanceof Endpoint endpoint)) {
            throw new InstantiationException("the configurator of " + config.getPath() + " returned "
                    + (instance == null ? "null" : instance.getClass().getName()) + ", not an Endpoint");
        }
        return new Negotiated(subprotocol == null ? "" : subprotocol, endpoint);
    }

    // ---------------------------------------------------------------- sessions

    String nextSessionId() {
        return Long.toString(sessionIds.incrementAndGet());
    }

    void opened(FoySession session) {
        openSessions.compute(session.config(), (config, sessions) -> {
            Set<FoySession> open = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            open.add(session);
            return open;
        });
    }

    void closed(FoySession session) {
        openSessions.computeIfPresent(session.config(), (config, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /** A snapshot of the open sessions of {@code config}. */
    Set<Session> openSessions(ServerEndpointConfig config) {
        return Set.copyOf(openSessions.getOrDefault(config, Set.of()));
    }

    // ---------------------------------------------------------------- WebSocketContainer

    @Override
    public long getDefaultAsyncSendTimeout() {
        return asyncSendTimeout;
    }

    @Override
    public void setAsyncSendTimeout(long timeoutMillis) {
        asyncSendTimeout = timeoutMillis;
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout() {
        return maxSessionIdleTimeout;
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout) {
        maxSessionIdleTimeout = timeout;
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize() {
        return maxBinaryMessageBufferSize;
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max) {
        maxBinaryMessageBufferSize = max;
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize() {
        return maxTextMessageBufferSize;
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max) {
        maxTextMessageBufferSize = max;
    }

    /** None: Foy installs no extension ({@code permessage-deflate} is not supported). */
    @Override
    public Set<Extension> getInstalledExtensions() {
        return Set.of();
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) throws DeploymentException {
        throw clientApiNotSupported();
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException {
        throw clientApiNotSupported();
    }

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig cec, URI path)
            throws DeploymentException {
        throw clientApiNotSupported();
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path)
            throws DeploymentException {
        throw clientApiNotSupported();
    }

    private static DeploymentException clientApiNotSupported() {
        return new DeploymentException("The WebSocket client API is not supported yet (Foy WebSocket lot 3)");
    }
}
