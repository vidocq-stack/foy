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

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@link ServletOutputStream} of an upgraded connection ({@code WebConnection.getOutputStream()}),
 * writing straight to the connection's raw output: no response buffer, no commit, no framing, and
 * {@link #flush()} puts the bytes on the wire.
 *
 * <p>{@link ServletOutputStreamImpl} is not reused: its whole design (the response buffer, the commit
 * of a head, the {@link ResponsePipe} chappe reads, Content-Length checks, cycle claims) belongs to an
 * HTTP response. Over an upgraded connection the bytes go to an {@link OutputStream} that blocks
 * until they are written, so non-blocking readiness only needs to know whether a write is still in
 * flight.</p>
 *
 * <p><b>Blocking mode</b> (the default): writes and flushes go straight to the connection.</p>
 *
 * <p><b>Non-blocking mode</b> (Servlet 6.1 section 3.7), entered by
 * {@link #setWriteListener(WriteListener)} (always allowed: the connection is upgraded). A write
 * copies its bytes and hands them to a short virtual thread ({@code foy-upgrade-write-<n>}) that
 * writes and flushes them, so the write never blocks; memory is bounded by one write.
 * {@link #isReady()} answers {@code true} while no write is in flight; when it answers {@code false}
 * the end of the write in flight submits {@code onWritePossible}. A write while one is still in
 * flight throws {@link IllegalStateException} (and arms {@code onWritePossible}); {@link #flush()}
 * never needs to wait (the writer flushes) and is a no-op. {@code onWritePossible} clears the
 * not-ready state before it runs, so its first write needs no {@code isReady()} call. {@link #close()}
 * returns at once; the connection's output is reported closed once the write in flight is done. A
 * failed write, or an exception thrown by {@code onWritePossible}, goes to {@code onError} and then
 * {@link NonBlockingHost#failed} (the connection closes), once. Every callback goes through the
 * host's {@link CallbackSerializer}.</p>
 */
final class UpgradedOutputStream extends ServletOutputStream {

    private static final System.Logger LOG = System.getLogger(UpgradedOutputStream.class.getName());
    private static final AtomicLong THREADS = new AtomicLong();

    private final OutputStream out;
    private final NonBlockingHost host;
    /** Runs once, when the application closed this stream and every byte went out. */
    private final Runnable onClosed;

    private final ReentrantLock lock = new ReentrantLock();
    /** Signalled whenever the write in flight is over (written or failed). */
    private final java.util.concurrent.locks.Condition writeDone = lock.newCondition();
    private volatile WriteListener listener;
    /** Guarded by {@link #lock}: the bytes of the write in flight, or {@code null}. */
    private byte[] inFlight;
    /** {@link #isReady()} answered {@code false}, or a write was refused: onWritePossible is due. */
    private boolean notReady;
    private boolean closed;
    private boolean closeWhenWritten;
    private boolean errored;
    private IOException failure;
    /** The connection is over: nothing is reported any more. */
    private boolean ended;

    UpgradedOutputStream(OutputStream out, NonBlockingHost host, Runnable onClosed) {
        this.out = out;
        this.host = host;
        this.onClosed = onClosed;
    }

    // ---- writes ----

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (listener == null) {
            lock.lock();
            try {
                if (closed) throw new IOException("the stream is closed");
            } finally {
                lock.unlock();
            }
            out.write(b, off, len);
            return;
        }
        byte[] bytes;
        lock.lock();
        try {
            if (errored) throw new IOException(failure == null ? "the connection failed" : failure.getMessage(), failure);
            if (closed) throw new IOException("the stream is closed");
            if (notReady || inFlight != null) {
                // The bound: a single write in flight. Its end submits onWritePossible.
                notReady = true;
                throw new IllegalStateException("not ready for writing: isReady() is false");
            }
            if (len == 0) return;
            bytes = Arrays.copyOfRange(b, off, off + len);
            inFlight = bytes;
        } finally {
            lock.unlock();
        }
        Thread.ofVirtual().name("foy-upgrade-write-" + THREADS.incrementAndGet()).start(() -> writeInFlight(bytes));
    }

    /** The writer thread: puts the bytes on the wire, then reports what is due. */
    private void writeInFlight(byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException | RuntimeException e) {
            failed(e instanceof IOException io ? io : new IOException(e));
            return;
        }
        boolean writePossible;
        boolean closeNow;
        lock.lock();
        try {
            inFlight = null;
            writeDone.signalAll();
            if (ended) return;
            closeNow = closeWhenWritten;
            writePossible = notReady && !closed;
        } finally {
            lock.unlock();
        }
        if (closeNow) onClosed.run();
        else if (writePossible) submitWritePossible();
    }

    @Override
    public void flush() throws IOException {
        if (listener == null) {
            out.flush();
            return;
        }
        lock.lock();
        try {
            if (errored) throw new IOException(failure == null ? "the connection failed" : failure.getMessage(), failure);
        } finally {
            lock.unlock();
        }
        // Non-blocking: the writer thread flushes every write it puts on the wire.
    }

    @Override
    public boolean isReady() {
        if (listener == null) return true;
        lock.lock();
        try {
            if (errored || closed) return false;
            if (inFlight == null) return true;
            notReady = true;
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        Objects.requireNonNull(writeListener, "writeListener");
        if (!host.nonBlockingAllowed()) throw new IllegalStateException("non-blocking output is not allowed");
        lock.lock();
        try {
            if (listener != null) throw new IllegalStateException("a WriteListener is already set");
            if (closed) throw new IllegalStateException("the stream is closed");
            listener = writeListener;
            notReady = true;
        } finally {
            lock.unlock();
        }
        // The first onWritePossible; it runs once the handler's init returned (callbacks are held).
        submitWritePossible();
    }

    @Override
    public void close() throws IOException {
        boolean closeNow;
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            closeNow = inFlight == null;
            if (!closeNow) closeWhenWritten = true;
        } finally {
            lock.unlock();
        }
        if (!closeNow) return; // non-blocking: returns at once, reported once the write is done
        if (listener == null) {
            try {
                out.flush();
            } finally {
                onClosed.run();
            }
        } else {
            onClosed.run();
        }
    }

    /**
     * Waits until the non-blocking write in flight, if any, is on the wire (or failed), at most
     * {@code timeout}: the connection may then close without cutting the application's last
     * write. Returns at once in blocking mode or without a write in flight.
     *
     * @return whether no write is in flight any more
     */
    boolean awaitWriteInFlight(java.time.Duration timeout) {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (inFlight != null) {
                if (nanos <= 0) return false;
                nanos = writeDone.awaitNanos(nanos);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return inFlight == null;
        } finally {
            lock.unlock();
        }
    }

    /** The connection is over: no callback is submitted any more and writes fail. Idempotent. */
    void end() {
        lock.lock();
        try {
            ended = true;
            closed = true;
        } finally {
            lock.unlock();
        }
    }

    // ---- callbacks ----

    private void submitWritePossible() {
        host.callbacks().submit(() -> {
            WriteListener l;
            lock.lock();
            try {
                if (ended || errored || closed) return;
                notReady = false;
                l = listener;
            } finally {
                lock.unlock();
            }
            l.onWritePossible();
        }, this::callbackFailed);
    }

    /** A write failed on the writer thread. */
    private void failed(IOException e) {
        lock.lock();
        try {
            inFlight = null;
            writeDone.signalAll();
            if (ended || errored) return;
            errored = true;
            failure = e;
        } finally {
            lock.unlock();
        }
        host.callbacks().submit(() -> deliverError(e),
                t -> LOG.log(System.Logger.Level.WARNING, "delivering a write failure failed", t));
    }

    /** onWritePossible threw: onError, then the host fails. */
    private void callbackFailed(Throwable t) {
        lock.lock();
        try {
            if (errored) return;
            errored = true;
        } finally {
            lock.unlock();
        }
        deliverError(t);
    }

    private void deliverError(Throwable t) {
        try {
            listener.onError(t);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "WriteListener.onError threw", e);
        } finally {
            host.failed(t);
        }
    }
}
