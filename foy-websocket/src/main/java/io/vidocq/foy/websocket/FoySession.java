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
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpointConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One WebSocket connection seen through the Jakarta API, and the listener of its transport events
 * (WEBSOCKET.md section 5.4). The handshake data is captured when the handshake runs; the transport
 * delivers one event at a time, so the endpoint and its message handlers never see two threads at
 * once.
 *
 * <p>Every frame in or out counts as activity for the idle timeout. A message larger than the
 * session's maximum closes it with 1009, unread; a message handler that throws is reported to
 * {@code Endpoint.onError} and the session stays open; an {@code Endpoint.onOpen} that throws is
 * reported, then the session closes with 1011 (an {@code Error} is not reported: the session closes,
 * then it propagates). {@code Endpoint.onClose} runs once, with the reason of
 * the side that closed first; then the session leaves the open sessions, its timer stops and its
 * queued asynchronous sends fail. No {@code Endpoint.onError} follows it.
 */
final class FoySession implements Session, WebSocketListener {

    private static final System.Logger LOG = System.getLogger(FoySession.class.getName());

    /** The user property holding the remote address, an {@link InetSocketAddress}. */
    static final String REMOTE_ADDRESS = "jakarta.websocket.endpoint.remoteAddress";
    /** The user property holding the local address of the handshake request, an {@link InetSocketAddress}. */
    static final String LOCAL_ADDRESS = "jakarta.websocket.endpoint.localAddress";

    private static final String PROTOCOL_VERSION = "13";
    private static final int MAX_CONTROL_PAYLOAD = 125;
    private static final CloseReason ON_OPEN_FAILED =
            new CloseReason(CloseCodes.UNEXPECTED_CONDITION, "onOpen failed");

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
    private final InetSocketAddress localAddress;
    private final MessageHandlers handlers = new MessageHandlers();
    private final SessionTimer timer;
    private final BasicRemote basicRemote;
    private final AsyncRemote asyncRemote;
    private final AtomicReference<WebSocketPeer> peer = new AtomicReference<>();
    /** The reason this side closed with, when it closed first. */
    private final AtomicReference<CloseReason> localClose = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** The context class loader of the transport's callbacks: the application's. */
    private volatile ClassLoader applicationLoader;
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
        this.localAddress = request.localAddress();
        this.maxIdleTimeout = container.getDefaultMaxSessionIdleTimeout();
        this.maxTextMessageBufferSize = container.getDefaultMaxTextMessageBufferSize();
        this.maxBinaryMessageBufferSize = container.getDefaultMaxBinaryMessageBufferSize();
        this.timer = new SessionTimer(this::idleTimedOut, task -> applicationThread("foy-websocket-timer-", task));
        this.basicRemote = new BasicRemote(this);
        this.asyncRemote = new AsyncRemote(this, container.getDefaultAsyncSendTimeout());
    }

    /** The registered config of the endpoint: the scope of the open sessions. */
    ServerEndpointConfig config() {
        return config.registered();
    }

    // ---------------------------------------------------------------- transport events

    /**
     * Opens the session: the addresses join the user properties, then {@code Endpoint.onOpen} runs.
     * When it throws an exception, the endpoint hears it and the session closes with 1011. When it
     * throws an {@code Error} (a failed static initializer), the session closes with 1011 and
     * {@code onClose} runs before the {@code Error} propagates to the transport, which may end the
     * connection without another event: neither the session, its timer nor its sends outlive it.
     */
    @Override
    public void onOpen(WebSocketPeer opened) {
        applicationLoader = Thread.currentThread().getContextClassLoader();
        peer.set(opened);
        Map<String, Object> properties = config.getUserProperties();
        if (opened.remoteAddress() != null) {
            properties.put(REMOTE_ADDRESS, opened.remoteAddress());
        }
        if (localAddress != null) {
            properties.put(LOCAL_ADDRESS, localAddress);
        }
        open = true;
        container.opened(this);
        if (maxIdleTimeout > 0) {
            timer.idle(maxIdleTimeout);
        }
        boolean returned = false;
        try {
            openEndpoint();
            returned = true;
        } finally {
            if (!returned) {
                closeQuietly(ON_OPEN_FAILED);
                onClose(ON_OPEN_FAILED.getCloseCode().getCode(), ON_OPEN_FAILED.getReasonPhrase());
            }
        }
    }

    /** {@code Endpoint.onOpen}; an exception it throws is reported, then the session closes with 1011. */
    private void openEndpoint() {
        try {
            endpoint.onOpen(this, config);
        } catch (RuntimeException e) {
            endpoint.onError(this, e);
            closeQuietly(ON_OPEN_FAILED);
        }
    }

    @Override
    public void onText(String text) {
        timer.touch();
        if (text.length() > maxTextMessageBufferSize) {
            closeQuietly(new CloseReason(CloseCodes.TOO_BIG, "text message of " + text.length()
                    + " characters, the maximum is " + maxTextMessageBufferSize));
            return;
        }
        deliver(() -> handlers.text(text));
    }

    @Override
    public void onBinary(ByteBuffer data) {
        timer.touch();
        if (data.remaining() > maxBinaryMessageBufferSize) {
            closeQuietly(new CloseReason(CloseCodes.TOO_BIG, "binary message of " + data.remaining()
                    + " bytes, the maximum is " + maxBinaryMessageBufferSize));
            return;
        }
        deliver(() -> handlers.binary(data));
    }

    /** Activity only: the transport answered the ping itself. */
    @Override
    public void onPing(ByteBuffer data) {
        timer.touch();
    }

    @Override
    public void onPong(ByteBuffer data) {
        timer.touch();
        deliver(() -> handlers.pong(data));
    }

    /**
     * Runs {@code Endpoint.onError}, unless {@code Endpoint.onClose} already ran: {@code onError} precedes
     * {@code onClose}, never follows it (WEBSOCKET.md section 5.4), so a transport that reports an error
     * after the session closed, an {@code Error} from {@code onOpen} for one, only has it logged.
     */
    @Override
    public void onError(Throwable error) {
        if (closed.get()) {
            LOG.log(System.Logger.Level.DEBUG, () -> "The WebSocket session " + id
                    + " is closed: its endpoint does not hear this error", error);
            return;
        }
        endpoint.onError(this, error);
    }

    /**
     * Runs {@code Endpoint.onClose} once, with the local reason when this side closed first, else the
     * peer's code; then the session leaves the open sessions, its timer stops and its queued sends fail.
     */
    @Override
    public void onClose(int code, String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        open = false;
        CloseReason local = localClose.get();
        try {
            endpoint.onClose(this, local != null ? local : new CloseReason(closeCode(code), reason));
        } finally {
            container.closed(this);
            timer.stop();
            asyncRemote.sessionClosed();
        }
    }

    // ---------------------------------------------------------------- Session

    @Override
    public WebSocketContainer getContainer() {
        return container;
    }

    /**
     * Registers a handler whose class file tells its message type.
     *
     * @throws IllegalStateException when the type cannot be told (a lambda), a handler of the same
     *                               native type is registered, or the type needs a decoder (lot 2)
     */
    @Override
    public void addMessageHandler(MessageHandler handler) {
        handlers.add(handler);
    }

    /**
     * Registers a whole-message handler.
     *
     * @throws IllegalStateException when a handler of the same native type is registered, or the type
     *                               needs a decoder (lot 2)
     */
    @Override
    public <T> void addMessageHandler(Class<T> type, MessageHandler.Whole<T> handler) {
        handlers.add(type, handler);
    }

    /**
     * Registers a partial-message handler, which receives each whole message as its last part.
     *
     * @throws IllegalStateException when a handler of the same native type is registered, or the type
     *                               is not {@code String}, {@code ByteBuffer} or {@code byte[]}
     */
    @Override
    public <T> void addMessageHandler(Class<T> type, MessageHandler.Partial<T> handler) {
        handlers.add(type, handler);
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        return handlers.all();
    }

    @Override
    public void removeMessageHandler(MessageHandler handler) {
        handlers.remove(handler);
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

    /** No frame in or out for {@code milliseconds} closes the session with 1001; {@code 0} or less for none. */
    @Override
    public void setMaxIdleTimeout(long milliseconds) {
        maxIdleTimeout = milliseconds;
        if (open) {
            timer.idle(milliseconds);
        }
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
        return asyncRemote;
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
        close(new CloseReason(CloseCodes.NORMAL_CLOSURE, ""));
    }

    /**
     * Starts the closing handshake with {@code closeReason}, which {@code Endpoint.onClose} then
     * reports; a code the protocol does not allow on the wire is sent as 1000. No effect once the
     * session is closing or closed.
     */
    @Override
    public void close(CloseReason closeReason) throws IOException {
        WebSocketPeer current = peer.get();
        if (current == null || closed.get() || !current.isOpen() || !localClose.compareAndSet(null, closeReason)) {
            return;
        }
        current.close(wireCode(closeReason.getCloseCode().getCode()),
                Objects.requireNonNullElse(closeReason.getReasonPhrase(), ""));
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
     * {@code modifyHandshake} filled, the same map as the config given to {@code onOpen}, with the
     * remote and the local address once the session is open.
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

    // ---------------------------------------------------------------- for the remotes

    /**
     * Writes {@code frame}, blocking until it is written.
     *
     * @throws IOException when the session is not open, or the transport cannot write
     */
    void write(Frame frame) throws IOException {
        WebSocketPeer current = peer.get();
        if (current == null || closed.get()) {
            throw new IOException("The WebSocket session " + id + " is not open");
        }
        frame.writeTo(current);
        timer.touch();
    }

    SessionTimer timer() {
        return timer;
    }

    /** An unstarted virtual thread running {@code task} with the application's class loader as context loader. */
    Thread applicationThread(String prefix, Runnable task) {
        Thread thread = Thread.ofVirtual().name(prefix + id).unstarted(task);
        ClassLoader loader = applicationLoader;
        if (loader != null) {
            thread.setContextClassLoader(loader);
        }
        return thread;
    }

    /** Closes with {@code reason}; a close that fails leaves the transport to end the connection. */
    void closeQuietly(CloseReason reason) {
        try {
            close(reason);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Closing the WebSocket session " + id + " failed", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void idleTimedOut() {
        closeQuietly(new CloseReason(CloseCodes.GOING_AWAY, "idle timeout"));
    }

    /** Runs a delivery to a message handler; a handler that throws is reported, and the session stays open. */
    private void deliver(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException e) {
            endpoint.onError(this, e);
        }
    }

    /** The code as the API knows it; a code outside its list (none reaches here from the transport) as is. */
    private static CloseReason.CloseCode closeCode(int code) {
        try {
            return CloseCodes.getCloseCode(code);
        } catch (IllegalArgumentException _) {
            return () -> code;
        }
    }

    /** {@code code} when RFC 6455 allows it in a close frame, else 1000. */
    static int wireCode(int code) {
        boolean allowed = (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1014)
                || (code >= 3000 && code <= 4999);
        return allowed ? code : CloseCodes.NORMAL_CLOSURE.getCode();
    }

    /** The remaining bytes of {@code data}, copied; its position is unchanged. */
    static byte[] bytesOf(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        return bytes;
    }

    /**
     * {@code value}, checked.
     *
     * @throws IllegalArgumentException when it is {@code null}, as the remotes' sends specify
     */
    static <T> T required(T value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("The " + what + " to send is null");
        }
        return value;
    }

    private static ByteBuffer controlPayload(ByteBuffer data) {
        if (required(data, "application data").remaining() > MAX_CONTROL_PAYLOAD) {
            throw new IllegalArgumentException("The application data of a ping or a pong is at most "
                    + MAX_CONTROL_PAYLOAD + " bytes, not " + data.remaining());
        }
        return data;
    }

    // ---------------------------------------------------------------- nested types

    /** One outgoing message, as both remotes send it. */
    @FunctionalInterface
    interface Frame {

        void writeTo(WebSocketPeer peer) throws IOException;

        static Frame text(String text) {
            required(text, "text");
            return peer -> peer.sendText(text);
        }

        static Frame binary(ByteBuffer data) {
            required(data, "data");
            return peer -> peer.sendBinary(data);
        }

        /**
         * The frame of {@code sendObject}: a {@code String} or a boxed primitive as text, a
         * {@code ByteBuffer} or a {@code byte[]} as binary.
         *
         * @throws EncodeException for another type: encoders come with lot 2
         */
        static Frame of(Object data) throws EncodeException {
            return switch (required(data, "object")) {
                case String text -> text(text);
                case ByteBuffer buffer -> binary(buffer);
                case byte[] bytes -> binary(ByteBuffer.wrap(bytes));
                case Boolean _, Character _, Byte _, Short _, Integer _, Long _, Float _, Double _ ->
                        text(String.valueOf(data));
                default -> throw new EncodeException(data, "Foy cannot send a " + data.getClass().getName()
                        + ": encoders are not supported yet (Foy WebSocket lot 2)");
            };
        }
    }

    /** What both remotes share: pings and pongs, written at once, and no batching. */
    abstract static class Remote implements RemoteEndpoint {

        protected final FoySession session;

        Remote(FoySession session) {
            this.session = session;
        }

        /** Sends a ping, blocking until it is written; the pong reaches the pong handler. */
        @Override
        public void sendPing(ByteBuffer applicationData) throws IOException {
            ByteBuffer payload = controlPayload(applicationData);
            session.write(peer -> peer.sendPing(payload));
        }

        /** Sends an unsolicited pong, blocking until it is written. */
        @Override
        public void sendPong(ByteBuffer applicationData) throws IOException {
            ByteBuffer payload = controlPayload(applicationData);
            session.write(peer -> peer.sendPong(payload));
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
    }
}
