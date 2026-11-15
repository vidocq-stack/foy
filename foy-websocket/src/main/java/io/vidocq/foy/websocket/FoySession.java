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
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
 * then it propagates). {@code Endpoint.onClose} runs once, with the reason of the side that closed first,
 * also when a Close of the peer or of the undeploy crosses this side's: this side's, unless the transport
 * answers that another Close came first ({@link WebSocketPeer#close(int, String)}) or reports a Close of the
 * peer before this side's close answered; then the session leaves the open sessions, its timer stops and its
 * queued asynchronous sends fail. No {@code Endpoint.onError} follows it.
 *
 * <p>A close the container initiates (the idle and send timeouts, a message too big, a failed
 * {@code onOpen}) sends its own code to the peer, but {@code Endpoint.onClose} hears 1006 with the same
 * reason (Jakarta WebSocket 2.2 section 2.1.5), so the endpoint tells it from a close of the peer. A close
 * the application initiates with {@link #close(CloseReason)} keeps the application's code: the spec's rule
 * names the container. The transport applies the same rule to the closes it initiates, the undeploy's
 * among them ({@link WebSocketListener#onClose(int, String)}).
 */
final class FoySession implements Session, WebSocketListener {

    private static final System.Logger LOG = System.getLogger(FoySession.class.getName());

    /** The user property holding the remote address, an {@link InetSocketAddress}. */
    static final String REMOTE_ADDRESS = "jakarta.websocket.endpoint.remoteAddress";
    /** The user property holding the local address of the handshake request, an {@link InetSocketAddress}. */
    static final String LOCAL_ADDRESS = "jakarta.websocket.endpoint.localAddress";

    private static final String PROTOCOL_VERSION = "13";
    private static final int MAX_CONTROL_PAYLOAD = 125;
    /** The close of a failed {@code onOpen}, as the peer reads it. */
    private static final CloseReason ON_OPEN_FAILED =
            new CloseReason(CloseCodes.UNEXPECTED_CONDITION, "onOpen failed");
    /**
     * How long {@link #onClose} waits at most for the answer of a close in flight. The transport gives that answer
     * once the close gets past the write in progress, if any; with chappe that write can be its automatic Pong of a
     * Ping the peer sent before its Close, stalled on a peer that does not read for up to the write timeout, which
     * this bound keeps {@code onClose}, and the undeploy that runs it, from waiting for.
     */
    static final Duration CLOSE_ANSWER_WAIT = Duration.ofMillis(250);

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
    /**
     * Guards the close of this side ({@link #closing}, {@link #localClose}, {@link #localCloseSent}) and the start of
     * {@link #onClose}, which waits on {@link #closeAnswered} for a close in flight. Never held across I/O.
     */
    private final ReentrantLock closeLock = new ReentrantLock();
    /** How long {@link #onClose} waits at most for the answer of a close in flight ({@link #CLOSE_ANSWER_WAIT}). */
    private final Duration closeAnswerWait;
    /** Signalled when the transport answered a close of this side. */
    private final Condition closeAnswered = closeLock.newCondition();
    /** A close of this side is in flight: the transport has not answered yet whether it sent its Close frame. */
    private boolean closing;
    /**
     * The reason {@code Endpoint.onClose} reports when this side closed first: the application's own, or 1006 for a
     * close the container initiated. Set when a close of this side starts, and dropped when the transport answers
     * that another Close came first, or when that close was interrupted before it wrote anything and so never
     * started, which leaves a later close free to send: a close in flight, or one whose write failed, keeps it
     * against a cut.
     */
    private CloseReason localClose;
    /** The transport answered that this side's Close frame went out: {@link #localClose} holds, whatever follows. */
    private boolean localCloseSent;
    /** The write of this side's Close frame failed: no other Close came first, the connection broke under it. */
    private boolean localCloseFailed;
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
        this(container, config, endpoint, request, pathParameters, subprotocol, CLOSE_ANSWER_WAIT);
    }

    /**
     * A session whose {@link #onClose} waits at most {@code closeAnswerWait} for the answer of a close in flight.
     */
    FoySession(FoyServerContainer container, ConnectionConfig config, Endpoint endpoint,
               HandshakeRequestImpl request, Map<String, String> pathParameters, String subprotocol,
               Duration closeAnswerWait) {
        this.closeAnswerWait = closeAnswerWait;
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
     * {@code onClose} runs, with 1006, before the {@code Error} propagates to the transport, which may end
     * the connection without another event: neither the session, its timer nor its sends outlive it. Either
     * way, when the transport's Close went out first (the undeploy's), the session sends none and the
     * transport's {@code onClose}, which the SPI delivers once, reports that close, the one the peer read.
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
                closeByContainer(ON_OPEN_FAILED);
                // Only a close of this side is reported here: the transport reports its own.
                if (closedFirst()) {
                    onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), ON_OPEN_FAILED.getReasonPhrase());
                }
            }
        }
    }

    /** {@code Endpoint.onOpen}; an exception it throws is reported, then the session closes with 1011. */
    private void openEndpoint() {
        try {
            endpoint.onOpen(this, config);
        } catch (RuntimeException e) {
            endpoint.onError(this, e);
            closeByContainer(ON_OPEN_FAILED);
        }
    }

    @Override
    public void onText(String text) {
        timer.touch();
        if (text.length() > maxTextMessageBufferSize) {
            closeByContainer(new CloseReason(CloseCodes.TOO_BIG, "text message of " + text.length()
                    + " characters, the maximum is " + maxTextMessageBufferSize));
            return;
        }
        deliver(() -> handlers.text(text));
    }

    @Override
    public void onBinary(ByteBuffer data) {
        timer.touch();
        if (data.remaining() > maxBinaryMessageBufferSize) {
            closeByContainer(new CloseReason(CloseCodes.TOO_BIG, "binary message of " + data.remaining()
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
     * Runs {@code Endpoint.onClose} once, with the local reason when this side closed first (the application's,
     * or 1006 for a close of the container), else the transport's: the peer's code, or 1006 for a close the
     * transport initiated or a cut connection; then the session leaves the open sessions, its timer stops and its
     * queued sends fail.
     *
     * <p>This side closed first when the transport answered that its Close frame went out ({@link #keepsLocalReason}).
     * A close of this side whose write failed closed first too, unless the transport reports a Close of the peer (a
     * code other than 1006). One the transport has not answered, still in flight on another thread, keeps its
     * reason only against a bare cut (1006 with no reason), as Jakarta WebSocket 2.2 section 2.1.5 keeps the reason
     * of the side that closed first when the peer then vanishes: a Close of the peer, or a close the transport names
     * (the undeploy's, whose Close went out first or which cut the connection), wins. Once the peer no longer reads
     * as open (a Close went out or came in, or the connection was cut), the close in flight writes nothing more and
     * answers once the write in progress, if any, ends, so this waits for that answer, at most
     * {@link #CLOSE_ANSWER_WAIT}; it waits as well, within the same bound, when the transport names the close it
     * reports (1006 with a reason), since the answer then decides. While the peer still reads as open and the
     * transport reports a bare cut, the close in flight may wait for a send the peer does not read: this does not
     * wait for it, and that close keeps its reason.
     */
    @Override
    public void onClose(int code, String reason) {
        CloseReason local;
        closeLock.lock();
        try {
            awaitCloseAnswer(code, reason);
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            local = keepsLocalReason(code, reason) ? localClose : null;
        } finally {
            closeLock.unlock();
        }
        open = false;
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

    /**
     * No frame in or out for {@code milliseconds} closes the session with 1001, which {@code Endpoint.onClose}
     * hears as 1006, {@code "idle timeout"}; {@code 0} or less for none.
     */
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
     * reports: a close of the application keeps its code, 1001 included. A code the protocol does not
     * allow on the wire is sent as 1000. No effect once the session is closing or closed.
     */
    @Override
    public void close(CloseReason closeReason) throws IOException {
        close(closeReason, closeReason);
    }

    /**
     * Sends {@code wire} to the peer, unless the session is closing or closed, and gives {@code reported} to
     * {@code Endpoint.onClose} unless the transport answers that another Close came first: a Close of the peer, or
     * the undeploy's, that comes between the check of {@link WebSocketPeer#isOpen()} and the send leaves this Close
     * unsent, and {@code onClose} reports that Close (WEBSOCKET.md section 5.4). A write that fails keeps
     * {@code reported} unless the transport reports a Close of the peer; a close still in flight keeps it only
     * against a bare cut ({@link #keepsLocalReason}). A close interrupted before it wrote anything never started:
     * it leaves no reason behind, so a later close still sends its frame, and the {@link InterruptedIOException}
     * reaches the caller, its interrupt status set. {@link #onClose} waits for the answer when it comes meanwhile;
     * no lock is held while the transport writes.
     */
    private void close(CloseReason wire, CloseReason reported) throws IOException {
        WebSocketPeer current = peer.get();
        if (current == null || !startClose(current, reported)) {
            return;
        }
        CloseOutcome outcome = CloseOutcome.FAILED;
        try {
            outcome = current.close(wireCode(wire.getCloseCode().getCode()),
                    Objects.requireNonNullElse(wire.getReasonPhrase(), ""))
                    ? CloseOutcome.SENT : CloseOutcome.ANOTHER_FIRST;
        } catch (InterruptedIOException e) {
            // Interrupted before it wrote anything (WebSocketPeer.close): that close never started. A
            // SocketTimeoutException is a failed write like any other: the connection is gone.
            if (!(e instanceof SocketTimeoutException)) {
                outcome = CloseOutcome.NOT_STARTED;
            }
            throw e;
        } finally {
            settleClose(outcome);
        }
    }

    /** How a close of this side ended, for {@link #settleClose}. */
    private enum CloseOutcome {
        /** The transport sent its Close frame: this side closed first. */
        SENT,
        /** Another Close went out or came in first: the transport's report gives the reason. */
        ANOTHER_FIRST,
        /** The write of its Close frame failed: this side closed first, and the connection broke under it. */
        FAILED,
        /** Interrupted before anything was written: as if it never started, so a later close may still. */
        NOT_STARTED
    }

    /**
     * Whether a close of this side may start: the session is neither closing nor closed, and no close of this side
     * started before; marks it in flight with {@code reported}.
     */
    private boolean startClose(WebSocketPeer current, CloseReason reported) {
        closeLock.lock();
        try {
            if (closed.get() || closing || localClose != null || !current.isOpen()) {
                return false;
            }
            closing = true;
            localClose = reported;
            return true;
        } finally {
            closeLock.unlock();
        }
    }

    /**
     * The close in flight is over with {@code outcome}: a close whose frame went out, or whose write failed, keeps
     * {@link #localClose}; one that another Close came before, or that never started, drops it.
     */
    private void settleClose(CloseOutcome outcome) {
        closeLock.lock();
        try {
            closing = false;
            switch (outcome) {
                case SENT -> localCloseSent = true;
                case FAILED -> localCloseFailed = true;
                case ANOTHER_FIRST, NOT_STARTED -> localClose = null;
            }
            closeAnswered.signalAll();
        } finally {
            closeLock.unlock();
        }
    }

    /**
     * Under {@link #closeLock}: waits for the answer of a close in flight once it is decided ({@link #onClose}), or
     * when the transport reports a close it names, which a close of this side that failed in its write came before,
     * at most {@link #closeAnswerWait}; an interrupt ends the wait too, and stays set.
     */
    private void awaitCloseAnswer(int code, String reason) {
        WebSocketPeer current = peer.get();
        long left = closeAnswerWait.toNanos();
        while (closing && (!current.isOpen() || namedByTheTransport(code, reason)) && left > 0) {
            try {
                left = closeAnswered.awaitNanos(left);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Under {@link #closeLock}: whether {@code Endpoint.onClose} hears {@link #localClose} rather than the end the
     * transport reports with {@code code} and {@code reason}.
     */
    private boolean keepsLocalReason(int code, String reason) {
        if (localClose == null || localCloseSent) {
            return localClose != null;
        }
        if (code != CloseCodes.CLOSED_ABNORMALLY.getCode()) {
            return false; // the transport read a Close of the peer before this side's answered
        }
        return localCloseFailed || !namedByTheTransport(code, reason);
    }

    /** Whether the transport reports a close it initiated and names (1006 with a reason), not a bare cut. */
    private static boolean namedByTheTransport(int code, String reason) {
        return code == CloseCodes.CLOSED_ABNORMALLY.getCode() && reason != null && !reason.isEmpty();
    }

    /** Whether the transport answered that this side's Close went out. */
    private boolean closedFirst() {
        closeLock.lock();
        try {
            return localCloseSent;
        } finally {
            closeLock.unlock();
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

    /**
     * A close the container initiates: the peer reads {@code wire}, and {@code Endpoint.onClose} hears 1006
     * with the same reason, never sent on the wire (Jakarta WebSocket 2.2 section 2.1.5). A close that fails
     * leaves the transport to end the connection.
     *
     * @param wire the code and the reason of the Close frame, the reason at most 123 bytes in UTF-8
     */
    void closeByContainer(CloseReason wire) {
        try {
            close(wire, new CloseReason(CloseCodes.CLOSED_ABNORMALLY, wire.getReasonPhrase()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Closing the WebSocket session " + id + " failed", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void idleTimedOut() {
        closeByContainer(new CloseReason(CloseCodes.GOING_AWAY, "idle timeout"));
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
