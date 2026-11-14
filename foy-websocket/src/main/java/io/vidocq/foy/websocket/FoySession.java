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

import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketPeer;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpointConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One WebSocket connection seen through the Jakarta API, and the listener of its transport
 * events. The handshake data is captured when the handshake runs (WEBSOCKET.md section 5.4);
 * the transport delivers one event at a time, so the endpoint never sees two threads at once.
 *
 * <p>This first version carries what the handshake needs: the session data, whole text messages
 * to one text handler, blocking text sends and the close. Other handlers, the asynchronous remote,
 * partial sends and the timers are not supported yet and throw
 * {@link UnsupportedOperationException}.
 */
final class FoySession implements Session, WebSocketListener {

    private static final String PROTOCOL_VERSION = "13";

    private final FoyServerContainer container;
    /** This connection's config: the registered one with this connection's own user properties. */
    private final ConnectionConfig config;
    private final Endpoint endpoint;
    private final String id;
    private final URI requestUri;
    private final String queryString;
    private final Map<String, List<String>> requestParameters;
    private final Map<String, String> pathParameters;
    private final Principal userPrincipal;
    private final String subprotocol;
    private final RemoteEndpoint.Basic basicRemote = new BasicRemote();
    private final AtomicReference<WebSocketPeer> peer = new AtomicReference<>();
    private final AtomicReference<MessageHandler.Whole<String>> textHandler = new AtomicReference<>();
    private volatile boolean open;
    private volatile long maxIdleTimeout;
    private volatile int maxTextMessageBufferSize;
    private volatile int maxBinaryMessageBufferSize;

    /**
     * A session for the handshake of {@code request}, run while the handshake does.
     *
     * @param config      the connection's config, whose user properties {@code modifyHandshake} filled
     * @param subprotocol the negotiated subprotocol, {@code ""} for none
     */
    FoySession(FoyServerContainer container, ConnectionConfig config, Endpoint endpoint,
               HandshakeRequestImpl request, Map<String, String> pathParameters, String subprotocol) {
        this.container = container;
        this.config = config;
        this.endpoint = endpoint;
        this.id = container.nextSessionId();
        this.requestUri = request.getRequestURI();
        this.queryString = request.getQueryString();
        this.requestParameters = request.getParameterMap();
        this.pathParameters = pathParameters;
        this.userPrincipal = request.getUserPrincipal();
        this.subprotocol = subprotocol;
        this.maxIdleTimeout = container.getDefaultMaxSessionIdleTimeout();
        this.maxTextMessageBufferSize = container.getDefaultMaxTextMessageBufferSize();
        this.maxBinaryMessageBufferSize = container.getDefaultMaxBinaryMessageBufferSize();
    }

    /** The registered config of the endpoint: the scope of the open sessions. */
    ServerEndpointConfig config() {
        return config.registered();
    }

    // ---------------------------------------------------------------- transport events

    @Override
    public void onOpen(WebSocketPeer opened) {
        peer.set(opened);
        open = true;
        container.opened(this);
        endpoint.onOpen(this, config);
    }

    @Override
    public void onText(String text) {
        MessageHandler.Whole<String> handler = textHandler.get();
        if (handler != null) {
            handler.onMessage(text);
        }
    }

    @Override
    public void onError(Throwable error) {
        endpoint.onError(this, error);
    }

    @Override
    public void onClose(int code, String reason) {
        open = false;
        container.closed(this);
        endpoint.onClose(this, new CloseReason(closeCode(code), reason));
    }

    // ---------------------------------------------------------------- Session

    @Override
    public WebSocketContainer getContainer() {
        return container;
    }

    @Override
    public void addMessageHandler(MessageHandler handler) {
        throw notSupportedYet("addMessageHandler(MessageHandler)");
    }

    /**
     * Registers the text handler.
     *
     * @throws IllegalStateException when a text handler is already registered
     */
    @Override
    @SuppressWarnings("unchecked") // the type is checked: T is String
    public <T> void addMessageHandler(Class<T> type, MessageHandler.Whole<T> handler) {
        if (type != String.class) {
            throw notSupportedYet("a message handler for " + type.getName());
        }
        if (!textHandler.compareAndSet(null, (MessageHandler.Whole<String>) handler)) {
            throw new IllegalStateException("a text message handler is already registered");
        }
    }

    @Override
    public <T> void addMessageHandler(Class<T> type, MessageHandler.Partial<T> handler) {
        throw notSupportedYet("a partial message handler");
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        MessageHandler handler = textHandler.get();
        return handler == null ? Set.of() : Set.of(handler);
    }

    @Override
    public void removeMessageHandler(MessageHandler handler) {
        textHandler.updateAndGet(current -> current == handler ? null : current);
    }

    @Override
    public String getProtocolVersion() {
        return PROTOCOL_VERSION;
    }

    @Override
    public String getNegotiatedSubprotocol() {
        return subprotocol;
    }

    @Override
    public List<Extension> getNegotiatedExtensions() {
        return List.of();
    }

    @Override
    public boolean isSecure() {
        return "wss".equals(requestUri.getScheme());
    }

    @Override
    public boolean isOpen() {
        WebSocketPeer current = peer.get();
        return open && current != null && current.isOpen();
    }

    @Override
    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }

    @Override
    public void setMaxIdleTimeout(long milliseconds) {
        maxIdleTimeout = milliseconds;
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int length) {
        maxBinaryMessageBufferSize = length;
    }

    @Override
    public int getMaxBinaryMessageBufferSize() {
        return maxBinaryMessageBufferSize;
    }

    @Override
    public void setMaxTextMessageBufferSize(int length) {
        maxTextMessageBufferSize = length;
    }

    @Override
    public int getMaxTextMessageBufferSize() {
        return maxTextMessageBufferSize;
    }

    @Override
    public RemoteEndpoint.Async getAsyncRemote() {
        throw notSupportedYet("the asynchronous remote");
    }

    @Override
    public RemoteEndpoint.Basic getBasicRemote() {
        return basicRemote;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void close() throws IOException {
        close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, ""));
    }

    /** Starts the closing handshake; no effect once the session is closed. */
    @Override
    public void close(CloseReason closeReason) throws IOException {
        WebSocketPeer current = peer.get();
        if (current != null && current.isOpen()) {
            current.close(closeReason.getCloseCode().getCode(),
                    Objects.requireNonNullElse(closeReason.getReasonPhrase(), ""));
        }
    }

    /** The handshake's absolute request URI, context path and query string included. */
    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        return requestParameters;
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    @Override
    public Map<String, String> getPathParameters() {
        return pathParameters;
    }

    /**
     * This connection's user properties: the copy of the registered config's ones that
     * {@code modifyHandshake} filled, the same map as the config given to {@code onOpen}.
     */
    @Override
    public Map<String, Object> getUserProperties() {
        return config.getUserProperties();
    }

    @Override
    public Principal getUserPrincipal() {
        return userPrincipal;
    }

    /** The open sessions of the same registered endpoint configuration, this one included while it is open. */
    @Override
    public Set<Session> getOpenSessions() {
        return container.openSessions(config.registered());
    }

    // ---------------------------------------------------------------- helpers

    /** The code as the API knows it; a code outside its list (none reaches here from the transport) as is. */
    private static CloseReason.CloseCode closeCode(int code) {
        try {
            return CloseReason.CloseCodes.getCloseCode(code);
        } catch (IllegalArgumentException _) {
            return () -> code;
        }
    }

    private static UnsupportedOperationException notSupportedYet(String what) {
        return new UnsupportedOperationException(what + " is not supported yet by Foy's WebSocket session");
    }

    /** The transport, once the connection is open. */
    private WebSocketPeer peer() throws IOException {
        WebSocketPeer current = peer.get();
        if (current == null) {
            throw new IOException("the WebSocket session is not open");
        }
        return current;
    }

    /** Blocking sends: each returns once its frame is written, or throws {@link IOException}. */
    private final class BasicRemote implements RemoteEndpoint.Basic {

        @Override
        public void sendText(String text) throws IOException {
            peer().sendText(text);
        }

        @Override
        public void sendBinary(ByteBuffer data) {
            throw notSupportedYet("sendBinary");
        }

        @Override
        public void sendText(String partialMessage, boolean isLast) {
            throw notSupportedYet("a partial text send");
        }

        @Override
        public void sendBinary(ByteBuffer partialByte, boolean isLast) {
            throw notSupportedYet("a partial binary send");
        }

        @Override
        public OutputStream getSendStream() {
            throw notSupportedYet("getSendStream");
        }

        @Override
        public Writer getSendWriter() {
            throw notSupportedYet("getSendWriter");
        }

        @Override
        public void sendObject(Object data) {
            throw notSupportedYet("sendObject");
        }

        @Override
        public void setBatchingAllowed(boolean allowed) {
            // no batching: every send is written at once
        }

        @Override
        public boolean getBatchingAllowed() {
            return false;
        }

        @Override
        public void flushBatch() {
            // nothing is ever batched
        }

        @Override
        public void sendPing(ByteBuffer applicationData) {
            throw notSupportedYet("sendPing");
        }

        @Override
        public void sendPong(ByteBuffer applicationData) {
            throw notSupportedYet("sendPong");
        }
    }
}
