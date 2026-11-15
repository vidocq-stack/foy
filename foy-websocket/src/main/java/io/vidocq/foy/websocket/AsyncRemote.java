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

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The asynchronous remote of a session (WEBSOCKET.md section 5.4). Sends join a FIFO that one
 * virtual thread drains, started when a send finds no thread draining and ended when the queue is
 * empty: sends are never reordered nor written at once, and each {@link SendHandler} hears the
 * result of its own send, on that thread, with the application's class loader as context loader.
 *
 * <p>With a send timeout, a send not written that long after it was queued fails its result with a
 * {@link SocketTimeoutException} and closes the session with 1001, which {@code Endpoint.onClose} hears as
 * 1006, {@code "send timeout"}: the container closed it (Jakarta WebSocket 2.2 section 2.1.5). When the
 * session closes, every queued send fails; a send queued afterwards fails at once.
 */
final class AsyncRemote extends FoySession.Remote implements RemoteEndpoint.Async {

    private static final System.Logger LOG = System.getLogger(AsyncRemote.class.getName());
    /** The longest timeout applied, about 73 years, so the deadline arithmetic cannot overflow. */
    private static final long MAX_TIMEOUT_NANOS = Long.MAX_VALUE / 4;

    private final Queue<Send> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong sendIds = new AtomicLong();
    private volatile long sendTimeout;
    private volatile boolean closed;

    /** @param sendTimeout the container's default send timeout, in milliseconds; {@code 0} or less for none */
    AsyncRemote(FoySession session, long sendTimeout) {
        super(session);
        this.sendTimeout = sendTimeout;
    }

    @Override
    public long getSendTimeout() {
        return sendTimeout;
    }

    /** The timeout of the sends queued from now on, in milliseconds; {@code 0} or less for none. */
    @Override
    public void setSendTimeout(long timeoutmillis) {
        sendTimeout = timeoutmillis;
    }

    @Override
    public void sendText(String text, SendHandler handler) {
        enqueue(FoySession.Frame.text(text), handler);
    }

    @Override
    public Future<Void> sendText(String text) {
        return enqueue(FoySession.Frame.text(text));
    }

    @Override
    public Future<Void> sendBinary(ByteBuffer data) {
        return enqueue(FoySession.Frame.binary(data));
    }

    @Override
    public void sendBinary(ByteBuffer data, SendHandler handler) {
        enqueue(FoySession.Frame.binary(data), handler);
    }

    /** As {@link BasicRemote#sendObject(Object)}; an {@link EncodeException} fails the future. */
    @Override
    public Future<Void> sendObject(Object data) {
        try {
            return enqueue(FoySession.Frame.of(data));
        } catch (EncodeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** As {@link BasicRemote#sendObject(Object)}; an {@link EncodeException} is the handler's result. */
    @Override
    public void sendObject(Object data, SendHandler handler) {
        FoySession.required(handler, "send handler");
        try {
            enqueue(FoySession.Frame.of(data), handler);
        } catch (EncodeException e) {
            handler.onResult(new SendResult(session, e));
        }
    }

    /** The session closed: every queued send fails, and so does every later one. */
    void sessionClosed() {
        closed = true;
        failQueued();
    }

    private Future<Void> enqueue(FoySession.Frame frame) {
        var future = new CompletableFuture<Void>();
        enqueue(frame, result -> {
            if (result.isOK()) {
                future.complete(null);
            } else {
                future.completeExceptionally(result.getException());
            }
        });
        return future;
    }

    private void enqueue(FoySession.Frame frame, SendHandler handler) {
        FoySession.required(handler, "send handler");
        queue.add(new Send(sendIds.incrementAndGet(), frame, handler, sendTimeout));
        if (closed) {
            failQueued();
        } else if (draining.compareAndSet(false, true)) {
            startDrainer();
        }
    }

    private void startDrainer() {
        session.applicationThread("foy-websocket-send-", this::drain).start();
    }

    /**
     * The drainer: writes the queued sends in order, and ends when the queue is empty, or when a
     * {@link SendHandler} throws an {@code Error}, which propagates once the next drainer is started.
     */
    private void drain() {
        try {
            for (Send send = queue.poll(); send != null; send = queue.poll()) {
                write(send);
            }
        } finally {
            draining.set(false);
            // A send queued after the last poll, while this thread still held the flag, started no
            // other thread, nor did the sends behind a SendHandler that threw: start one for them.
            if (!queue.isEmpty() && draining.compareAndSet(false, true)) {
                startDrainer();
            }
        }
    }

    private void write(Send send) {
        SessionTimer timer = session.timer();
        if (send.timeoutMillis > 0) {
            timer.sendDeadline(send.id, send.deadline, () -> expire(send));
        }
        Exception failure = null;
        try {
            session.write(send.frame);
        } catch (IOException | RuntimeException e) {
            failure = e;
        } finally {
            if (send.timeoutMillis > 0) {
                timer.sendDone(send.id);
            }
        }
        send.complete(failure);
    }

    /**
     * On the timer's thread: the send still being written fails, and the session closes, also when its
     * {@link SendHandler} throws an {@code Error}.
     */
    private void expire(Send send) {
        if (!send.claim()) {
            return;
        }
        try {
            send.deliver(new SocketTimeoutException("The send was not written within its timeout of "
                    + send.timeoutMillis + " ms"));
        } finally {
            session.closeByContainer(new CloseReason(CloseCodes.GOING_AWAY, "send timeout"));
        }
    }

    private void failQueued() {
        for (Send send = queue.poll(); send != null; send = queue.poll()) {
            send.complete(new IOException("The WebSocket session " + session.getId() + " is closed"));
        }
    }

    /** One queued send; its result is delivered once, by the first of the drainer, the timer and the close. */
    private final class Send {
        private final long id;
        private final FoySession.Frame frame;
        private final SendHandler handler;
        private final long timeoutMillis;
        /** The {@link System#nanoTime()} instant of the timeout, when there is one. */
        private final long deadline;
        private final AtomicBoolean done = new AtomicBoolean();

        Send(long id, FoySession.Frame frame, SendHandler handler, long timeoutMillis) {
            this.id = id;
            this.frame = frame;
            this.handler = handler;
            this.timeoutMillis = timeoutMillis;
            this.deadline = timeoutMillis > 0
                    ? System.nanoTime() + Math.min(TimeUnit.MILLISECONDS.toNanos(timeoutMillis), MAX_TIMEOUT_NANOS)
                    : 0;
        }

        /**
         * Delivers the result, unless it was delivered already.
         *
         * @param failure the failure, {@code null} for a send written
         */
        void complete(Exception failure) {
            if (claim()) {
                deliver(failure);
            }
        }

        /** Whether this call is the one that delivers the result: true once only. */
        boolean claim() {
            return done.compareAndSet(false, true);
        }

        /**
         * Hands the result to the handler, once {@link #claim()} returned {@code true}; an exception from
         * the handler is logged, an {@code Error} propagates.
         */
        void deliver(Exception failure) {
            try {
                handler.onResult(failure == null ? new SendResult(session) : new SendResult(session, failure));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, () -> "A SendHandler of the WebSocket session "
                        + session.getId() + " threw", e);
            }
        }
    }
}
