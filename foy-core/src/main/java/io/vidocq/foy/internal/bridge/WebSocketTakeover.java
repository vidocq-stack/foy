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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
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
 * from the listener's {@code onOpen}, after which chappe sends no event, closes the connection (1011, unless
 * the undeploy closed it first) and ends the takeover the same way before it propagates.</p>
 *
 * <p>The code the listener hears in {@code onClose} follows Jakarta WebSocket 2.2 section 2.1.5, which
 * {@code foy-websocket}'s session, the listener this SPI serves, implements: the code and reason of the peer's
 * Close when the peer closed first, and 1006, a code never sent on the wire, when this side's container closed
 * the connection, whatever code its Close frame carried, with a reason naming why (BUG-20261115-08). The
 * listener hears 1006 for the undeploy ({@code "the application is stopping"}, 1001 on the wire), for a failed
 * {@code onOpen} ({@code "onOpen failed"}, 1011), and for the closes chappe initiates on a failure it reports
 * to {@code onError} first: a protocol violation of the peer ({@code "protocol error: "} and what chappe
 * found, 1002, 1007 or 1009 on the wire) and a callback that threw ({@code "callback failed: "} and the
 * throwable, 1011). The undeploy and a failed {@code onOpen} may both decide to close a connection: the first
 * to decide gives the frame and the reason ({@link #firstClose}), so the listener hears the reason of the
 * Close the peer reads. A connection cut with no Close frame stays the 1006 chappe reports, with no reason. A
 * close the listener initiates through its peer reports the peer's answer: the listener knows its own
 * code.</p>
 *
 * <p>An undeploy ends the takeover before it goes on, so {@code onClose} and {@code destroy()} run
 * while the application is intact, before its {@code contextDestroyed} (BUG-20261114-08). A
 * connection still open is closed with 1001 and ended once its Close is sent, without waiting for the
 * peer's answer and never before that Close: on the undeploy hook's thread (a virtual thread of its own
 * for each connection), or, when an event in progress returns after the Close went out, on the
 * connection's thread as soon as it returns, so the listener never runs on two threads at once; the hook
 * then waits for that {@code onClose}. The listener hears the undeploy's close, 1006 with its reason, not
 * the code the peer answers with nor the 1006 with no reason of a connection the server's stop cut; no I/O
 * failure of that closing handshake reaches its {@code onError}, and no frame read once the undeploy closed
 * the connection reaches it either. A connection whose closing handshake had already begun keeps the code
 * of the side that closed first: the undeploy waits for its end. So does one whose peer or listener sent its
 * Close, or whose peer's Close arrived, after the undeploy found it open but before the undeploy's own Close
 * went out: chappe answers that the undeploy's Close did not go out, and the transport's events wait for that
 * answer before they reach the listener (BUG-20261115-08). The Close goes out on a thread of its own,
 * since a send blocked on a peer that does not read holds the lock it needs; a Close that send still holds
 * at the end of the grace never goes out: the connection is cut (CHAPPE-060), which fails that send, and
 * the takeover ends with the undeploy's close all the same. The whole of it, the Close and the waits, lasts
 * at most {@link #UNDEPLOY_GRACE}, plus {@link #CUT_GRACE} for a connection it cut, except an end already
 * under way on the connection's thread (an {@code onClose}, or the {@code onError} that follows a failed
 * {@code onOpen}), which the context's own grace bounds. Past it, the takeover still ends after the
 * application stopped: once the event in progress returns, or, after an {@code onOpen}, at the next frame or
 * at the peer's answer to the Close.</p>
 *
 * <p>Nothing interrupts the hook: it runs on a virtual thread the context starts for this connection, and
 * its Close on another one. An interrupt of the undeploy's own thread only stops the undeploy's wait for
 * its connections ({@code VidocqServletContext.closeUpgradedConnections}): a connection still closing then
 * finishes on its own virtual thread, and its {@code onClose} may come after {@code contextDestroyed}.</p>
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

    /**
     * How long an undeploy waits, once it cut a connection whose Close a send blocked on the peer held past
     * {@link #UNDEPLOY_GRACE}, for what the cut ends at once: the thread of that Close, and the event in
     * progress whose send the cut failed, so that the takeover still ends before the undeploy goes on.
     */
    static final Duration CUT_GRACE = Duration.ofMillis(500);

    private static final int GOING_AWAY = 1001;
    /** The code of a close this side's container initiated, for the listener only: never on the wire. */
    private static final int CLOSED_ABNORMALLY = 1006;
    private static final int INTERNAL_ERROR = 1011;
    /** The most a close reason holds, in UTF-8 bytes (RFC 6455 section 5.5: 125 bytes, 2 for the code). */
    private static final int MAX_REASON_BYTES = 123;
    private static final String STOPPING = "the application is stopping";
    private static final String ON_OPEN_FAILED = "onOpen failed";

    private final WebSocketUpgradeHandler handler;
    private final VidocqServletContext context;
    private final ClassLoader applicationLoader;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean registered;
    private volatile boolean openFailed;
    /**
     * The undeploy closed the connection: it found it open and its Close went out first, with its 1001 or with the
     * 1011 of an {@code onOpen} that failed first ({@link #firstClose}), or it cut the connection. Final once
     * {@link #undeployCloseInFlight} is {@code false}; read it through {@link #isClosedByUndeploy()}.
     */
    private volatile boolean closedByUndeploy;
    /**
     * The undeploy found the connection open and its Close has not answered yet: whether that Close went out first
     * is not known, so {@link #isClosedByUndeploy()} waits.
     */
    private volatile boolean undeployCloseInFlight;
    /** Signals {@link #undeployCloseSettled} when the undeploy's Close answered. Never held across I/O. */
    private final ReentrantLock undeployCloseLock = new ReentrantLock();
    private final Condition undeployCloseSettled = undeployCloseLock.newCondition();
    /**
     * The reason of the first Close the takeover decided on: the undeploy's ({@code "the application is
     * stopping"}) or a failed {@code onOpen}'s ({@code "onOpen failed"}); {@code null} until one did. Every
     * Close the takeover sends after it repeats its code and reason, on whichever thread, so the peer reads that
     * Close and the listener hears its reason (Jakarta WebSocket 2.2 section 2.1.5).
     */
    private final AtomicReference<String> firstClose = new AtomicReference<>();
    /**
     * The undeploy hook is past its wait for the 1001 Close: the Close went out, or the hook stopped waiting
     * for it at its deadline. Until then the takeover of a connection the undeploy closed does not end on
     * the connection's thread, so {@code onClose} follows the Close frame (Jakarta WebSocket 2.2 section
     * 2.1.5).
     */
    private volatile boolean closeWaitOver;
    /**
     * The undeploy cut the connection, its 1001 Close held past the grace: a send that fails then fails with
     * a {@link ConnectionCutException}, which says so.
     */
    private volatile boolean cut;
    /** When the undeploy hook started, a {@link System#nanoTime()}: its WARNINGs say how long it waited. */
    private long hookStarted;
    private final AtomicReference<WebSocketListener> listener = new AtomicReference<>();
    private final AtomicReference<AutoCloseable> undeployHook = new AtomicReference<>();
    /** The first failure chappe reported to {@link #onError}: a close that follows it is chappe's own. */
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    /** Held while the listener runs: the transport's events and the undeploy's end never overlap. */
    private final ReentrantLock events = new ReentrantLock();
    /** Opens once the takeover ended: {@code onClose} ran and {@code destroy()} returned. */
    private final CountDownLatch ended = new CountDownLatch(1);
    /** The undeploy hook's grace: {@link #UNDEPLOY_GRACE}, longer in a test that must not reach it. */
    private final Duration undeployGrace;

    WebSocketTakeover(WebSocketUpgradeHandler handler, VidocqServletContext context, ClassLoader applicationLoader) {
        this(handler, context, applicationLoader, UNDEPLOY_GRACE);
    }

    /** A takeover whose undeploy hook waits {@code undeployGrace} instead of {@link #UNDEPLOY_GRACE}. */
    WebSocketTakeover(WebSocketUpgradeHandler handler, VidocqServletContext context, ClassLoader applicationLoader,
                      Duration undeployGrace) {
        this.handler = handler;
        this.context = context;
        this.applicationLoader = applicationLoader;
        this.undeployGrace = undeployGrace;
    }

    /** The hook {@link #onOpen} registered with the context for its undeploy; {@code null} before. */
    AutoCloseable undeployHook() {
        return undeployHook.get();
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
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Error e) { // NOSONAR java:S1181 - deliberate: the boundary of the application's onOpen
            // Chappe's connection thread catches the Exceptions of onOpen, not an Error, which would end that
            // thread on its uncaught exception handler. By now open() closed and finished the takeover: log the
            // Error here, once (chappe logs an IOException at DEBUG only), then hand chappe an exception that
            // carries it, which chappe answers by ending the connection. The listener hears no onError for it:
            // the takeover ended, so report() drops the wrapper.
            LOG.log(System.Logger.Level.WARNING, "the onOpen of a WebSocket listener failed", e);
            throw new IOException(ON_OPEN_FAILED, e);
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
            // Chappe reports the failure through onError, but never calls onClose: onError closes and
            // finishes the takeover (see there). openFailed is set before the claim, so an undeploy that
            // finds the close claimed by this onOpen also finds that it failed, and leaves it that end.
            openFailed = true;
            claimClose(ON_OPEN_FAILED);
            throw e;
        } finally {
            if (!returned && !openFailed) {
                // An Error: chappe lets it end the connection with neither onError nor onClose.
                // Close and finish here, then the Error propagates.
                String reason = claimClose(ON_OPEN_FAILED);
                sendClose(ws, reason);
                finish(CLOSED_ABNORMALLY, reason);
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
     * class loader; none once the takeover ended, which an undeploy can do before the transport does, and
     * none once the undeploy closed the connection. Then, still under the lock, the takeover of a
     * connection the undeploy closed ends with the undeploy's close once the undeploy hook is past its wait
     * for the Close: after the event that was in progress when the undeploy came, or instead of a frame read
     * since.
     * Before that, the hook ends it itself after the Close ({@link #endNow}). So no frame reaches the
     * listener after the undeploy closed the connection, even when the undeploy stopped waiting for an
     * event that outlived its grace, and {@code onClose} never comes before the Close frame on this path.
     */
    private void deliver(Event event) throws IOException {
        events.lock();
        try {
            if (!isClosedByUndeploy()) {
                try (var _ = ContextLoaderScope.enter(applicationLoader)) {
                    var current = listener.get();
                    if (current != null && !closed.get()) event.deliverTo(current);
                }
            }
        } finally {
            try {
                if (closeWaitOver && isClosedByUndeploy()) finish(CLOSED_ABNORMALLY, STOPPING);
            } finally {
                events.unlock();
            }
        }
    }

    /**
     * Forwards the error to the listener, except an I/O failure of a connection the undeploy closed:
     * the session is over for the application once the undeploy sent its 1001, and what the transport
     * hits while that closing handshake ends (the server's stop cutting the read, the peer going away)
     * is the connection going down, not an error of the session (BUG-20261114-07). The first error is kept:
     * when chappe then closes the connection itself, {@link #onClose} names it to the listener.
     */
    @Override
    public void onError(WebSocket ws, Throwable error) {
        events.lock();
        try {
            failure.compareAndSet(null, error);
            try (var _ = ContextLoaderScope.enter(applicationLoader)) {
                report(error);
            } finally {
                // A failed onOpen is followed by no onClose from chappe: this ends the takeover, once.
                if (openFailed) finishOpenFailed(ws);
            }
        } finally {
            events.unlock();
        }
    }

    /**
     * Ends the takeover of an {@code onOpen} that failed, after the listener's {@code onError} (none for an
     * {@code IOException} once the undeploy closed the connection, BUG-20261114-07, nor for the failure of a
     * send the undeploy's cut ended): with 1006 and the reason of the close decided first ({@link #firstClose}).
     * That is the undeploy's when it closed the connection before {@code onOpen} failed, its 1001 sent or the
     * connection cut, else the 1011 of the failed {@code onOpen}. Its Close goes out here, before
     * {@code onClose}, so chappe's own 1011, which follows this {@code onError}, never goes out first.
     */
    private void finishOpenFailed(WebSocket ws) {
        String reason = Objects.requireNonNullElse(firstClose.get(), ON_OPEN_FAILED);
        sendClose(ws, reason);
        finish(CLOSED_ABNORMALLY, reason);
    }

    /**
     * Decides that the takeover closes the connection with {@code reason}, unless it already decided on
     * another: returns the reason that holds, which every Close the takeover sends repeats.
     */
    private String claimClose(String reason) {
        String first = firstClose.compareAndExchange(null, reason);
        return first == null ? reason : first;
    }

    /**
     * Sends the Close of {@code reason}: the undeploy's 1001, or the 1011 of a failed {@code onOpen}.
     *
     * @return {@code false} when another Close went out or came in before it ({@link #closeQuietly})
     */
    private static boolean sendClose(WebSocket ws, String reason) {
        return closeQuietly(ws, STOPPING.equals(reason) ? GOING_AWAY : INTERNAL_ERROR, reason);
    }

    private void report(Throwable error) {
        var current = listener.get();
        if (error instanceof IOException && isClosedByUndeploy()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "an I/O failure after the undeploy closed the WebSocket is not reported", error);
        } else if (current != null && !closed.get()) {
            current.onError(error);
        }
    }

    /**
     * Ends the takeover with the close of the side that closed first. Chappe reports the peer's code, or
     * 1006 for a cut connection; a close this side initiated reports 1006 with its own reason instead
     * (Jakarta WebSocket 2.2 section 2.1.5): the undeploy's, or, after a failure chappe reported to
     * {@link #onError} and closed the connection on, which it reports with no code of the peer's (1005),
     * a reason naming that failure ({@link #failureReason}). A no-op when the undeploy already ended it. While
     * the undeploy's Close has not answered, this waits for that answer ({@link #isClosedByUndeploy()}).
     */
    @Override
    public void onClose(WebSocket ws, int code, String reason) {
        events.lock();
        try {
            Throwable closedOn = failure.get();
            if (isClosedByUndeploy()) {
                finish(CLOSED_ABNORMALLY, STOPPING);
            } else if (closedOn != null && code != CLOSED_ABNORMALLY) {
                finish(CLOSED_ABNORMALLY, failureReason(closedOn));
            } else {
                finish(code, reason);
            }
        } finally {
            events.unlock();
        }
    }

    /**
     * The reason the listener hears for a close chappe initiated on {@code error}: an {@code IOException} is a
     * protocol violation of the peer, which chappe names in its message (an I/O failure of the connection is
     * reported as a cut, 1006, never here); anything else is the failure of a callback. At most
     * 123 bytes ({@link #boundedReason}).
     */
    static String failureReason(Throwable error) {
        String phrase = error instanceof IOException
                ? "protocol error: " + Objects.requireNonNullElse(error.getMessage(), error.getClass().getName())
                : "callback failed: " + error;
        return boundedReason(phrase);
    }

    /**
     * {@code phrase}, cut to the 123 bytes in UTF-8 a close reason holds, which
     * {@code jakarta.websocket.CloseReason} refuses past, without splitting a character.
     */
    static String boundedReason(String phrase) {
        byte[] bytes = phrase.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_REASON_BYTES) return phrase;
        int end = MAX_REASON_BYTES;
        // bytes[end] is the first byte left out: a continuation byte there means a character would be split.
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
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
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) { // NOSONAR java:S1181 - deliberate: the boundary of the application's listener
                // The boundary of the application's listener. Whichever thread ends the takeover (the undeploy's
                // hook, or chappe's connection thread, which catches Exceptions only from onClose), the
                // application's failure stops nothing: destroy() runs and it is logged here, once, on this
                // logger (BUG-20261116-01).
                LOG.log(System.Logger.Level.WARNING, "the onClose of a WebSocket listener failed", t);
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
     * for the whole hook, plus {@link #CUT_GRACE} when it had to cut the connection ({@link #abortHeldClose}). A
     * connection still open is marked closed by the undeploy, closed with 1001 ({@link #sendGoingAway}) and ended
     * at once ({@link #endNow}); one whose closing handshake already began is waited for
     * ({@link #awaitEnd}). It runs on the virtual thread the context starts for this connection, which
     * nothing interrupts; an {@code InterruptedException} would reach the context, which logs it.
     */
    private void undeploy(WebSocket ws) throws InterruptedException {
        hookStarted = System.nanoTime();
        long deadline = hookStarted + undeployGrace.toNanos();
        // A Close of the peer, or of the listener, may come between this check and the undeploy's own Close:
        // chappe then answers that the undeploy's did not go out, and the connection ends as one whose closing
        // handshake had begun, with the code of the side that closed first (WEBSOCKET.md section 5.4).
        boolean open = ws.isOpen();
        if (open) {
            undeployCloseInFlight = true;
            claimClose(STOPPING);
        }
        long next;
        boolean waited = false;
        try {
            next = sendGoingAway(ws, deadline);
            waited = true;
        } finally {
            // A hook that stopped before its Close answered (an interrupt, a thread that did not start) settles it
            // as the undeploy's close: no event of the transport waits for an answer no one waits for any more.
            if (!waited) settleUndeployClose(true);
        }
        if (open && isClosedByUndeploy()) {
            endNow(next);
        } else {
            awaitEnd(next);
        }
    }

    /**
     * Whether the undeploy closed the connection ({@link #closedByUndeploy}). While its Close has not answered,
     * waits for that answer: chappe gives it once the Close went out or another Close came first, the cut that
     * ends the undeploy's grace gives it otherwise ({@link #abortHeldClose}), and a hook that stops before either
     * gives it on its way out ({@link #undeploy}), so the wait is bounded by that grace.
     */
    private boolean isClosedByUndeploy() {
        if (undeployCloseInFlight) {
            undeployCloseLock.lock();
            try {
                while (undeployCloseInFlight) undeployCloseSettled.awaitUninterruptibly();
            } finally {
                undeployCloseLock.unlock();
            }
        }
        return closedByUndeploy;
    }

    /**
     * The undeploy's Close answered: {@code first} when it went out first or the undeploy cut the connection,
     * {@code false} when another Close went out or came in before it. The first answer holds.
     */
    private void settleUndeployClose(boolean first) {
        undeployCloseLock.lock();
        try {
            if (undeployCloseInFlight) {
                closedByUndeploy = first;
                undeployCloseInFlight = false;
                undeployCloseSettled.signalAll();
            }
        } finally {
            undeployCloseLock.unlock();
        }
    }

    /**
     * Sends the 1001 Close, waiting for it until {@code deadline}: the 1011 of an {@code onOpen} that failed
     * before the undeploy came instead, the close the takeover decided first ({@link #firstClose}). Chappe
     * sends it under the write lock of the connection, which a send blocked on a peer that does not read
     * holds for as long as the peer does not read, and takes that lock with no time limit: the Close therefore
     * goes out on a virtual thread of its own (BUG-20261115-01). Past the deadline the connection is cut
     * without it ({@link #abortHeldClose}). A close that throws (dead connection) is logged; chappe's own
     * teardown then ends the connection.
     *
     * <p>A connection still open was marked by the undeploy before its Close goes out: until that Close answers,
     * the transport's events wait for the answer ({@link #isClosedByUndeploy()}), so the peer's answer cannot reach
     * {@link #onClose} first. When the Close went out, no frame reaches the listener from then on; when another
     * Close went out or came in before it, the connection keeps the code of the side that closed first, as one
     * the peer or the listener already closed does. Once the wait is over, the takeover may end on the
     * connection's thread ({@link #deliver}).</p>
     *
     * @return the deadline of the waits that follow: {@code deadline}, or {@link #CUT_GRACE} after the cut
     */
    private long sendGoingAway(WebSocket ws, long deadline) throws InterruptedException {
        String reason = Objects.requireNonNullElse(firstClose.get(), STOPPING);
        Thread closing = Thread.ofVirtual().name("foy-websocket-close").start(() -> {
            boolean first = true;
            try {
                first = sendClose(ws, reason) || cut;
            } finally {
                settleUndeployClose(first);
            }
        });
        try {
            return closing.join(remaining(deadline)) ? deadline : abortHeldClose(ws, closing);
        } finally {
            closeWaitOver = true;
        }
    }

    /**
     * Cuts the connection whose Close a send blocked on a peer that does not read held past the grace:
     * chappe's {@link WebSocket#abort()} closes it at once, with no Close frame and without the write lock
     * (CHAPPE-060). The blocked send then fails, with an {@code IOException} that says the connection was
     * cut ({@link Peer}), and so does any later one; the thread of the Close returns without sending it; the
     * peer reads the end of the connection. The read the cut ends reaches no {@code onError}, and the
     * takeover still ends with the undeploy's close, once.
     *
     * @return the deadline, {@link #CUT_GRACE} from now, of the waits that follow
     */
    private long abortHeldClose(WebSocket ws, Thread closing) throws InterruptedException {
        long deadline = System.nanoTime() + CUT_GRACE.toNanos();
        LOG.log(System.Logger.Level.WARNING, "the Close of a WebSocket waited {0} ms into the undeploy for a"
                + " send blocked on its peer, which does not read: the connection is cut without it", waitedMillis());
        // No Close frame will go out: the event in progress may end the takeover as soon as it returns.
        closeWaitOver = true;
        cut = true;
        ws.abort();
        // The cut is the undeploy's close, whatever the thread of its Close still does.
        settleUndeployClose(true);
        if (!closing.join(remaining(deadline))) {
            LOG.log(System.Logger.Level.WARNING, "the Close of a cut WebSocket still ran {0} ms after the cut",
                    CUT_GRACE.toMillis());
        }
        return deadline;
    }

    /**
     * Ends the takeover with the undeploy's close (1006 to the listener, its Close frame 1001) without
     * waiting for the peer to answer: Jakarta WebSocket 2.2 section 5.1 asks that the listener never run on
     * two threads at once, and section 2.1.5 that the Close be sent before {@code onClose}, which needs no
     * answer from the peer. It runs once the hook is past its wait for the Close. With no event running it
     * ends here, on the undeploy hook's thread; an event in progress ends it on the connection's thread as
     * soon as it returns ({@link #deliver}), and this then finds it ended, or waits for its {@code onClose}
     * and {@code destroy()} when they still run at {@code deadline}. When a send blocked on a peer that does
     * not read held the Close back past the grace, the connection was cut without it
     * ({@link #abortHeldClose}): no Close frame comes before {@code onClose} on that path (section 2.1.5),
     * since waiting for one would let a peer that does not read hold the undeploy without limit
     * (BUG-20261115-01); the cut fails that send, so an event in progress that made it returns within the
     * {@code deadline} the cut set. An {@code onOpen} that failed keeps its own end: chappe's
     * {@code onError}, which always follows it on the connection's thread, reports the failure to the
     * listener and ends the takeover ({@link #finishOpenFailed}). This waits for that end with no deadline of
     * its own, as for an end under way ({@link #awaitEndUnderWay}), whichever takes the lock first: this,
     * which then releases it, or that {@code onError}, which this then outlasts the deadline for. The
     * context's own deadline for its upgraded connections bounds the wait. An event still running at
     * {@code deadline}: the undeploy stops waiting, and the takeover ends after the application stopped, once
     * that event returns, or, after an {@code onOpen}, which does not go through {@link #deliver}, at the next
     * frame or at the peer's answer to the Close.
     */
    private void endNow(long deadline) throws InterruptedException {
        if (!events.tryLock(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS)) {
            if (!awaitEndUnderWay()) {
                LOG.log(System.Logger.Level.WARNING, "a WebSocket event still ran {0} ms into the undeploy, which"
                        + " closed its connection: the takeover ends after the application stopped, once the event"
                        + " returns (after an onOpen, at the next frame or at the answer of the peer), and no later"
                        + " frame reaches the listener", waitedMillis());
            }
            return;
        }
        boolean openFailedFirst = openFailed;
        try {
            if (!openFailedFirst) finish(CLOSED_ABNORMALLY, STOPPING);
        } finally {
            events.unlock();
        }
        if (openFailedFirst) ended.await();
    }

    /**
     * Waits until {@code deadline} for the end of a closing handshake already under way (the listener or the
     * peer closed first), which ends with the code of the side that closed first. An {@code onClose} of that
     * end still running at {@code deadline} is waited for ({@link #awaitEndUnderWay}).
     */
    private void awaitEnd(long deadline) throws InterruptedException {
        if (!ended.await(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS) && !awaitEndUnderWay()) {
            LOG.log(System.Logger.Level.WARNING, "a WebSocket closing handshake was still under way {0} ms into the"
                    + " undeploy: onClose and destroy() run when it ends, after the application stopped",
                    waitedMillis());
        }
    }

    /** How long the undeploy hook has run, in milliseconds. */
    private long waitedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - hookStarted);
    }

    /**
     * Past the hook's deadline: when the takeover already began to end on the connection's thread, waits
     * for its {@code onClose} and {@code destroy()}, which run while the application is still intact. An
     * {@code onOpen} that failed is such an end too, though nothing has ended the takeover yet: chappe's
     * {@code onError} always follows it and ends the takeover, even when it already held the lock of the
     * listener's events at the deadline. The context's own deadline for its upgraded connections bounds that
     * wait.
     *
     * @return whether the takeover was ending: {@code false} when nothing has ended it yet
     */
    private boolean awaitEndUnderWay() throws InterruptedException {
        if (!closed.get() && !openFailed) return false;
        ended.await();
        return true;
    }

    /** What is left until {@code deadline}, a {@link System#nanoTime()}; zero once it passed. */
    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    /**
     * Sends the Close frame of {@code code}; a close that throws is logged, the transport ends the connection.
     *
     * @return chappe's answer: {@code false} when another Close went out or came in before this one, or an abort
     *         cut it; {@code true} when it went out, and when it threw: no other Close came first, the connection
     *         broke under it
     */
    private static boolean closeQuietly(WebSocket ws, int code, String reason) {
        try {
            return ws.sendClose(code, reason);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "closing the WebSocket with " + code + " failed", e);
            return true;
        }
    }

    /** One event of the transport, for {@link #deliver}. */
    @FunctionalInterface
    private interface Event {
        void deliverTo(WebSocketListener listener) throws IOException;
    }

    /** A send of the listener, for {@link Peer}. */
    @FunctionalInterface
    private interface Send {
        void run() throws IOException;
    }

    /**
     * Chappe's socket seen as the SPI's peer. A send that fails once the undeploy cut the connection fails
     * with an {@code IOException} that says so, chappe's own failure as its cause: an
     * {@code AsynchronousCloseException} for the send the cut ended, an {@code IOException("WebSocket closed")}
     * for one that waited behind it for the write lock, which chappe checks once it gets it, and for one made
     * after the cut; none of them tells the application why.
     */
    private final class Peer implements WebSocketPeer {
        private final WebSocket ws;

        Peer(WebSocket ws) {
            this.ws = ws;
        }

        @Override public void sendText(String text) throws IOException { send(() -> ws.sendText(text)); }
        @Override public void sendBinary(ByteBuffer data) throws IOException { send(() -> ws.sendBinary(data)); }
        @Override public void sendPing(ByteBuffer data) throws IOException { send(() -> ws.sendPing(data)); }
        @Override public void sendPong(ByteBuffer data) throws IOException { send(() -> ws.sendPong(data)); }
        @Override public boolean close(int code, String reason) throws IOException {
            return ws.sendClose(code, reason);
        }
        @Override public boolean isOpen() { return ws.isOpen(); }
        @Override public InetSocketAddress remoteAddress() { return ws.remoteAddress(); }
        @Override public boolean isSecure() { return ws.isSecure(); }

        private void send(Send send) throws IOException {
            try {
                send.run();
            } catch (IOException e) {
                if (!cut) throw e;
                throw new ConnectionCutException(e);
            }
        }
    }

    /** The failure of a send the undeploy's cut ended, chappe's own failure as its cause (see {@link Peer}). */
    private static final class ConnectionCutException extends IOException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        ConnectionCutException(IOException cause) {
            super("The WebSocket connection was cut: the application is stopping, and its peer did not read what"
                    + " was sent to it", cause);
        }
    }
}
