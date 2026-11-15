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

import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.api.WebSocketUpgrade;
import io.vidocq.foy.internal.container.ContextLoaderScope;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketPeer;
import io.vidocq.foy.spi.websocket.WebSocketUpgradeHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The takeover of an upgraded request by a {@link WebSocketUpgradeHandler}: chappe speaks RFC 6455
 * (handshake already answered, frames, closing handshake) and this class forwards its events to the
 * handler's {@link WebSocketListener}, one at a time, each with the application class loader as
 * context class loader (WEBSOCKET.md section 5.2).
 *
 * <p>The transport answers every ping with a pong itself: {@code onPing} is only forwarded. The
 * connection is registered with the context at {@code onOpen} so that an undeploy closes it with
 * 1001; {@code onClose} calls the listener, then unregisters the connection and calls
 * {@link WebSocketUpgradeHandler#destroy()}, exactly once, and no event follows it. An {@code Error}
 * from the listener's {@code onOpen}, after which chappe sends no event, closes with 1011 and ends the
 * takeover the same way before it propagates.</p>
 *
 * <p>An undeploy ends the takeover before it goes on, so {@code onClose} and {@code destroy()} run
 * while the application is intact, before its {@code contextDestroyed} (BUG-20261114-08). A
 * connection still open is closed with 1001 and ended on the undeploy's thread as soon as its Close
 * is sent, without waiting for the peer's answer: the listener hears that 1001, not the code the peer
 * answers with nor the 1006 of a connection the server's stop cut, and no I/O failure of that closing
 * handshake reaches its {@code onError}. That end waits for the event in progress, so the listener
 * never runs on two threads at once. A connection whose closing handshake had already begun keeps the
 * code of the side that closed first: the undeploy waits for its end. The Close goes out on a thread of
 * its own, since a send blocked on a peer that does not read holds the lock it needs. The whole of it,
 * the Close and the wait, lasts at most {@link #UNDEPLOY_GRACE}; past it, the takeover ends on the
 * transport's thread, and a Close still held goes out once that send ends.</p>
 */
final class WebSocketTakeover implements WebSocketHandler {

    private static final System.Logger LOG = System.getLogger(WebSocketTakeover.class.getName());

    /**
     * How long an undeploy waits for a takeover, all waits together: for its 1001 Close to go out, then
     * for the event in progress before it ends the takeover, or for the end of a closing handshake
     * already under way. Bounded, as a servlet's {@code destroy()} waits for the requests in progress up
     * to a server-defined limit (Servlet 6.1 section 2.3.4), so that a callback that never returns, a send
     * to a peer that does not read or a peer that never answers cannot hold the undeploy.
     */
    static final Duration UNDEPLOY_GRACE = Duration.ofSeconds(2);

    private static final int GOING_AWAY = 1001;
    private static final int INTERNAL_ERROR = 1011;
    private static final String STOPPING = "the application is stopping";
    private static final String ON_OPEN_FAILED = "onOpen failed";

    private final WebSocketUpgradeHandler handler;
    private final VidocqServletContext context;
    private final ClassLoader applicationLoader;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean registered;
    private volatile boolean openFailed;
    /** The undeploy closed the connection while it was open: this side closed first, with 1001. */
    private volatile boolean closedByUndeploy;
    private final AtomicReference<WebSocketListener> listener = new AtomicReference<>();
    private final AtomicReference<AutoCloseable> undeployHook = new AtomicReference<>();
    /** Held while the listener runs: the transport's events and the undeploy's end never overlap. */
    private final ReentrantLock events = new ReentrantLock();
    /** Opens once the takeover ended: {@code onClose} ran and {@code destroy()} returned. */
    private final CountDownLatch ended = new CountDownLatch(1);

    WebSocketTakeover(WebSocketUpgradeHandler handler, VidocqServletContext context, ClassLoader applicationLoader) {
        this.handler = handler;
        this.context = context;
        this.applicationLoader = applicationLoader;
    }

    /**
     * The 101 response chappe completes the handshake with: the handler's subprotocol and the headers the
     * application set (chappe drops the ones it writes itself).
     *
     * @throws IllegalArgumentException when chappe refuses the subprotocol or a header
     */
    Response response(Headers headers) {
        return new WebSocketUpgrade(this, handler.subprotocol(), null, headers);
    }

    @Override
    public void onOpen(WebSocket ws, Request handshake) throws IOException {
        events.lock();
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            open(ws);
        } finally {
            events.unlock();
        }
    }

    /**
     * Registers the connection with the context, then opens the listener. Runs under {@link #events}: an
     * undeploy meanwhile ends the takeover only once {@code onOpen} returned.
     */
    private void open(WebSocket ws) throws IOException {
        AutoCloseable hook = () -> undeploy(ws);
        undeployHook.set(hook);
        if (context != null && !context.registerUpgradedConnection(hook)) {
            // Undeployed meanwhile: the handler never starts, so it is not destroyed either. No send of
            // the application can hold the write lock yet.
            closeQuietly(ws, GOING_AWAY, STOPPING);
            return;
        }
        registered = true;
        boolean returned = false;
        try {
            var opened = handler.listener();
            listener.set(opened);
            opened.onOpen(new Peer(ws));
            returned = true;
        } catch (Exception e) {
            // Chappe reports the failure through onError and closes with 1011, but never calls
            // onClose: onError finishes the takeover (see there).
            openFailed = true;
            throw e;
        } finally {
            if (!returned && !openFailed) {
                // An Error: chappe lets it end the connection with neither onError nor onClose.
                // Close with 1011 and finish here, then the Error propagates.
                closeQuietly(ws, INTERNAL_ERROR, ON_OPEN_FAILED);
                finish(INTERNAL_ERROR, ON_OPEN_FAILED);
            }
        }
    }

    @Override
    public void onText(WebSocket ws, String message) throws IOException {
        deliver(current -> current.onText(message));
    }

    @Override
    public void onBinary(WebSocket ws, ByteBuffer data) throws IOException {
        deliver(current -> current.onBinary(data));
    }

    @Override
    public void onPing(WebSocket ws, ByteBuffer payload) throws IOException {
        deliver(current -> current.onPing(payload));
    }

    @Override
    public void onPong(WebSocket ws, ByteBuffer payload) throws IOException {
        deliver(current -> current.onPong(payload));
    }

    /**
     * Hands one event of the transport to the listener, under {@link #events} and with the application
     * class loader; none once the takeover ended, which an undeploy can do before the transport does.
     */
    private void deliver(Event event) throws IOException {
        events.lock();
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            var current = listener.get();
            if (current != null && !closed.get()) event.deliverTo(current);
        } finally {
            events.unlock();
        }
    }

    /**
     * Forwards the error to the listener, except an I/O failure of a connection the undeploy closed:
     * the session is over for the application once the undeploy sent its 1001, and what the transport
     * hits while that closing handshake ends (the server's stop cutting the read, the peer going away)
     * is the connection going down, not an error of the session (BUG-20261114-07).
     */
    @Override
    public void onError(WebSocket ws, Throwable error) {
        events.lock();
        try {
            try (var _ = ContextLoaderScope.enter(applicationLoader)) {
                report(error);
            } finally {
                // A failed onOpen is followed by no onClose from chappe: the listener hears the 1011 chappe
                // closes with, then the takeover is released, once.
                if (openFailed) finish(INTERNAL_ERROR, ON_OPEN_FAILED);
            }
        } finally {
            events.unlock();
        }
    }

    private void report(Throwable error) {
        var current = listener.get();
        if (closedByUndeploy && error instanceof IOException) {
            LOG.log(System.Logger.Level.DEBUG,
                    "an I/O failure after the undeploy closed the WebSocket is not reported", error);
        } else if (current != null && !closed.get()) {
            current.onError(error);
        }
    }

    /**
     * Ends the takeover with the code of the side that closed first: chappe reports the peer's code, or
     * 1006 for a cut connection, so an undeploy that closed first reports its own 1001 instead. A no-op
     * when the undeploy already ended it.
     */
    @Override
    public void onClose(WebSocket ws, int code, String reason) {
        events.lock();
        try {
            if (closedByUndeploy) {
                finish(GOING_AWAY, STOPPING);
            } else {
                finish(code, reason);
            }
        } finally {
            events.unlock();
        }
    }

    /**
     * The end of the takeover, once (also when {@code onOpen} failed): {@code listener.onClose}, then the
     * connection is unregistered and {@code destroy()} runs. A refused registration (undeployed
     * meanwhile) never started the handler: no {@code onClose}, no {@code destroy()}.
     */
    private void finish(int code, String reason) {
        if (!registered || !closed.compareAndSet(false, true)) return;
        try (var _ = ContextLoaderScope.enter(applicationLoader)) {
            try {
                var current = listener.get();
                if (current != null) current.onClose(code, reason);
            } finally {
                if (context != null) context.unregisterUpgradedConnection(undeployHook.get());
                handler.destroy();
            }
        } finally {
            ended.countDown();
        }
    }

    /**
     * The undeploy hook: ends the takeover before the undeploy goes on, within {@link #UNDEPLOY_GRACE}
     * for the whole hook. A connection still open is closed with 1001 ({@link #sendGoingAway}) and ended
     * at once ({@link #endNow}); one whose closing handshake already began is waited for
     * ({@link #awaitEnd}). The interrupt of the undeploy's thread is cleared for the hook and restored
     * afterwards; one that arrives during a wait ends the wait.
     */
    private void undeploy(WebSocket ws) {
        long deadline = System.nanoTime() + UNDEPLOY_GRACE.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            if (sendGoingAway(ws, deadline)) {
                endNow(deadline);
            } else {
                awaitEnd(deadline);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends the 1001 Close, waiting for it until {@code deadline}. Chappe sends it under the write lock of
     * the connection, which a send blocked on a peer that does not read holds for as long as the peer does
     * not read, and takes that lock with no time limit: the Close therefore goes out on a virtual thread
     * of its own (BUG-20261115-01). Past the deadline the undeploy goes on without it, and the Close goes
     * out once that send ends (or fails with the connection). On its own thread, the close is not
     * affected by an interrupt of the undeploy, on which chappe would send no Close. A close that throws
     * (dead connection) is logged; chappe's own teardown then ends the connection.
     *
     * <p>A connection still open is marked closed by the undeploy before its Close goes out, so the
     * peer's answer cannot reach {@link #onClose} first; a connection the peer or the listener already
     * closed keeps their code.</p>
     *
     * @return whether the connection was still open: the undeploy closed it first
     */
    private boolean sendGoingAway(WebSocket ws, long deadline) throws InterruptedException {
        // A window remains for a simultaneous close: a peer Close chappe has read but not yet marked
        // (closeReceived), or an EOF chappe has hit before notifyClose, still finds the socket open, so
        // the connection is reported as 1001 although no 1001 frame went out. Closing it needs chappe
        // to tell whether close() actually sent the frame.
        boolean open = ws.isOpen();
        if (open) {
            closedByUndeploy = true;
        }
        Thread closing = Thread.ofVirtual().name("foy-websocket-close")
                .start(() -> closeQuietly(ws, GOING_AWAY, STOPPING));
        if (!closing.join(remaining(deadline))) {
            LOG.log(System.Logger.Level.WARNING, "the 1001 Close of a WebSocket waited {0} ms into the undeploy"
                    + " for a send blocked on its peer: the session ends without it, and the Close goes out once"
                    + " that send ends", UNDEPLOY_GRACE.toMillis());
        }
        return open;
    }

    /**
     * Ends the takeover on the undeploy's thread with its 1001, once the event in progress returned, and
     * without waiting for the peer to answer: Jakarta WebSocket 2.2 section 2.1.5 only asks that the
     * Close be sent before {@code onClose}, and section 5.1 that the listener never runs on two threads
     * at once. An event still running at {@code deadline} leaves the end to the transport's thread, once
     * it returned.
     */
    private void endNow(long deadline) throws InterruptedException {
        if (!events.tryLock(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS)) {
            LOG.log(System.Logger.Level.WARNING, "a WebSocket event still ran {0} ms after the undeploy closed"
                    + " its connection: onClose and destroy() run once it returns, after the application stopped",
                    UNDEPLOY_GRACE.toMillis());
            return;
        }
        try {
            finish(GOING_AWAY, STOPPING);
        } finally {
            events.unlock();
        }
    }

    /**
     * Waits until {@code deadline} for the end of a closing handshake already under way (the listener or
     * the peer closed first): the transport ends it with the code of the side that closed first.
     */
    private void awaitEnd(long deadline) throws InterruptedException {
        if (!ended.await(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS)) {
            LOG.log(System.Logger.Level.WARNING, "a WebSocket closing handshake was still under way {0} ms into"
                    + " the undeploy: onClose and destroy() run when it ends, after the application stopped",
                    UNDEPLOY_GRACE.toMillis());
        }
    }

    /** What is left until {@code deadline}, a {@link System#nanoTime()}; zero once it passed. */
    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    /** Sends the Close frame of {@code code}; a close that throws is logged, the transport ends the connection. */
    private static void closeQuietly(WebSocket ws, int code, String reason) {
        try {
            ws.close(code, reason);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "closing the WebSocket with " + code + " failed", e);
        }
    }

    /** One event of the transport, for {@link #deliver}. */
    @FunctionalInterface
    private interface Event {
        void deliverTo(WebSocketListener listener) throws IOException;
    }

    /** Chappe's socket seen as the SPI's peer. */
    private record Peer(WebSocket ws) implements WebSocketPeer {
        @Override public void sendText(String text) throws IOException { ws.sendText(text); }
        @Override public void sendBinary(ByteBuffer data) throws IOException { ws.sendBinary(data); }
        @Override public void sendPing(ByteBuffer data) throws IOException { ws.sendPing(data); }
        @Override public void sendPong(ByteBuffer data) throws IOException { ws.sendPong(data); }
        @Override public void close(int code, String reason) throws IOException { ws.close(code, reason); }
        @Override public boolean isOpen() { return ws.isOpen(); }
        @Override public InetSocketAddress remoteAddress() { return ws.remoteAddress(); }
        @Override public boolean isSecure() { return ws.isSecure(); }
    }
}
