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

import io.vidocq.foy.spi.websocket.WebSocketPeer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A transport that records what the session writes. Like Chappe's peer, it is thread-safe, each
 * send returns once its frame is recorded, and a send after the close fails with
 * {@code IOException("WebSocket closed")}. A test can hold every write ({@link #blockWrites()}) or
 * make the peer vanish ({@link #vanish()}): the held writes and the later ones then fail.
 *
 * <p>A close answers whether it sent the Close frame. A test can have another Close come first
 * ({@link #anotherCloseCameFirst()}), hold a close before its frame goes out ({@link #holdCloseFrames()})
 * or before it answers ({@link #holdCloseAnswers()}), and have the write of the Close frame fail
 * ({@link #failCloses()}).
 */
final class FakePeer implements WebSocketPeer {

    static final InetSocketAddress REMOTE = new InetSocketAddress("192.0.2.7", 51_000);

    /** The bound of every wait of the fake, so a broken test fails instead of hanging. */
    private static final long WAIT_SECONDS = 10;

    /** The data frames written, in order: a {@code String} per text, a {@code byte[]} per binary. */
    final List<Object> messages = new CopyOnWriteArrayList<>();
    final List<String> controls = new CopyOnWriteArrayList<>();
    final CountDownLatch closed = new CountDownLatch(1);
    /** Counted down when a write starts, before it is held. */
    final CountDownLatch writing = new CountDownLatch(1);
    /** Counted down when a close is held ({@link #holdCloseFrames()}, {@link #holdCloseAnswers()}). */
    final CountDownLatch closeHeld = new CountDownLatch(1);

    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean overlapped;
    private volatile boolean open = true;
    private volatile boolean vanished;
    private volatile CountDownLatch gate;
    private volatile int closeCode = -1;
    private volatile String closeReason;
    private volatile boolean anotherCloseFirst;
    private volatile CountDownLatch closeGate;
    private volatile boolean holdBeforeTheFrame;
    private volatile boolean closesFail;

    /** Holds every write until {@link #releaseWrites()} or {@link #vanish()}. */
    void blockWrites() {
        gate = new CountDownLatch(1);
    }

    void releaseWrites() {
        CountDownLatch held = gate;
        if (held != null) {
            held.countDown();
        }
    }

    /** The TCP connection is cut: the held writes and every later one fail. */
    void vanish() {
        vanished = true;
        open = false;
        releaseWrites();
    }

    /**
     * The next close finds that another Close came first, which its check of {@link #isOpen()} could not see:
     * the peer's, or the undeploy's. It sends nothing and answers {@code false}; the connection is closing.
     */
    void anotherCloseCameFirst() {
        anotherCloseFirst = true;
    }

    /**
     * Holds the next close before its frame goes out, as a send the peer does not read holds it, until
     * {@link #releaseClose()}; the peer still reads as open meanwhile.
     */
    void holdCloseFrames() {
        holdBeforeTheFrame = true;
        closeGate = new CountDownLatch(1);
    }

    /**
     * Holds the next close once it took effect, its frame sent or another Close found first, before it answers,
     * until {@link #releaseClose()}; the peer no longer reads as open meanwhile.
     */
    void holdCloseAnswers() {
        holdBeforeTheFrame = false;
        closeGate = new CountDownLatch(1);
    }

    /**
     * The write of the next Close frame fails, as on a connection that broke: the close throws an
     * {@code IOException}, nothing went out, and the peer still reads as open until the transport notices.
     */
    void failCloses() {
        closesFail = true;
    }

    void releaseClose() {
        CountDownLatch held = closeGate;
        if (held != null) {
            held.countDown();
        }
    }

    /** Whether two writes ever ran at once. */
    boolean overlapped() {
        return overlapped;
    }

    int closeCode() {
        return closeCode;
    }

    String closeReason() {
        return closeReason;
    }

    /** The text messages written, in order. */
    List<String> texts() {
        return messages.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @Override
    public void sendText(String text) throws IOException {
        write(text);
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        write(bytes);
    }

    @Override
    public void sendPing(ByteBuffer data) throws IOException {
        control("ping", data);
    }

    @Override
    public void sendPong(ByteBuffer data) throws IOException {
        control("pong", data);
    }

    /**
     * Sends the Close frame unless the closing handshake began: another Close went out or came in first. Like
     * chappe, whose write lock is taken interruptibly, a call on an interrupted thread writes nothing and throws an
     * {@link InterruptedIOException}, the interrupt still set.
     */
    @Override
    public boolean close(int code, String reason) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while acquiring the write lock");
        }
        if (holdBeforeTheFrame) {
            awaitCloseGate();
        }
        if (closesFail) {
            throw new IOException("Broken pipe");
        }
        if (!open) {
            return false;
        }
        boolean sent = closeNow(code, reason);
        if (!holdBeforeTheFrame) {
            awaitCloseGate();
        }
        return sent;
    }

    /**
     * Sends the Close frame at once, never held nor failing: the transport's own Close, or the peer's as the
     * transport echoes it, which a test makes come before the session's.
     */
    boolean closeNow(int code, String reason) {
        if (!open) {
            return false;
        }
        open = false;
        boolean sent = !anotherCloseFirst;
        if (sent) {
            closeCode = code;
            closeReason = reason;
            closed.countDown();
        }
        return sent;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return REMOTE;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    private void control(String kind, ByteBuffer data) throws IOException {
        requireWritable();
        controls.add(kind + ":" + StandardCharsets.UTF_8.decode(data));
    }

    private void write(Object message) throws IOException {
        if (inFlight.incrementAndGet() > 1) {
            overlapped = true;
        }
        try {
            writing.countDown();
            awaitGate();
            requireWritable();
            messages.add(message);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void awaitGate() throws IOException {
        CountDownLatch held = gate;
        if (held == null) {
            return;
        }
        try {
            if (!held.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("the test never released the write");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while the write was held");
        }
    }

    /** Holds a close the test asked to hold; unchecked, so a test may close the peer from a lambda. */
    private void awaitCloseGate() {
        CountDownLatch held = closeGate;
        if (held == null) {
            return;
        }
        closeHeld.countDown();
        try {
            if (!held.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the close");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the close was held");
        }
    }

    private void requireWritable() throws IOException {
        if (vanished || !open) {
            throw new IOException("WebSocket closed");
        }
    }
}
