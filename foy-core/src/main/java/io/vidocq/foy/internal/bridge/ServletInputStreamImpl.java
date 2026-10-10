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

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ServletInputStream} over a blocking {@link InputStream}: chappe's request body, or (for
 * an upgraded connection) its raw input.
 *
 * <p><b>Blocking mode</b> (the default): reads go straight to the source.</p>
 *
 * <p><b>Non-blocking mode</b> (Servlet 6.1 section 3.7), entered by
 * {@link #setReadListener(ReadListener)} when the {@link NonBlockingHost} allows it (the request is
 * async-started or upgraded). A {@link ReadPump} reads the source on its own virtual thread into a
 * buffer; {@link #isReady()} is {@code true} while buffered bytes remain, or while the end of the
 * stream is buffered and not yet reported by a read; otherwise it is {@code false} and arms the next
 * {@code onDataAvailable}. Reading after {@code isReady()} returned {@code false}, or when nothing is
 * buffered, throws {@link IllegalStateException}. {@code onDataAvailable} runs when bytes arrive and
 * the listener is armed (it is armed from the start, and again whenever a read drains the buffer);
 * {@code onAllDataRead} runs once at the end of the stream; a read failure, or an exception thrown
 * by {@code onDataAvailable}/{@code onAllDataRead}, goes to {@code onError} and then
 * {@link NonBlockingHost#failed} (the async cycle fails). After an error no other callback runs.
 * Every callback goes through the host's {@link CallbackSerializer}.</p>
 */
public final class ServletInputStreamImpl extends ServletInputStream {

    /** What non-blocking mode needs from the owner of the stream (a request or an upgraded connection). */
    interface NonBlockingHost {
        /** Whether a {@link ReadListener} may be set now (async started, or upgraded). */
        boolean nonBlockingAllowed();
        /** The owner's callback serializer. */
        CallbackSerializer callbacks();
        /** A read failure or a throwing callback, after {@code onError}: fails the owner (the async cycle). */
        void failed(Throwable t);
    }

    private static final System.Logger LOG = System.getLogger(ServletInputStreamImpl.class.getName());

    private final InputStream delegate;
    private final NonBlockingHost host;
    private boolean finished;

    // ---- non-blocking state, guarded by lock ----
    private final ReentrantLock lock = new ReentrantLock();
    private volatile ReadListener listener;
    private ReadPump pump;
    /** The next arrival of bytes submits onDataAvailable. */
    private boolean armed;
    /** What isReady() last answered. */
    private boolean lastReady;
    private boolean eofReported;
    private boolean allDataReadFired;
    /** onError was (or is about to be) delivered: no other callback runs. */
    private boolean errored;
    private boolean ended;

    /** A stream that never enters non-blocking mode ({@link #setReadListener} throws). */
    public ServletInputStreamImpl(InputStream delegate) {
        this(delegate, null);
    }

    ServletInputStreamImpl(InputStream delegate, NonBlockingHost host) {
        this.delegate = delegate;
        this.host = host;
    }

    // ---- reads ----

    @Override
    public int read() throws IOException {
        if (listener != null) {
            byte[] one = new byte[1];
            int n = readNonBlocking(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xFF;
        }
        int b = delegate.read();
        if (b == -1) finished = true;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (listener != null) return readNonBlocking(b, off, len);
        int n = delegate.read(b, off, len);
        if (n == -1) finished = true;
        return n;
    }

    /**
     * Servlet 6.1: {@code 0} when the buffer has no space left; in blocking mode, blocks until at
     * least one byte is read; {@code -1} at the end of the stream; non-blocking without
     * {@link #isReady()} is an {@link IllegalStateException}.
     */
    @Override
    public int read(ByteBuffer buffer) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        if (!buffer.hasRemaining()) return 0;
        if (buffer.hasArray()) {
            int n = read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
            if (n > 0) buffer.position(buffer.position() + n);
            return n;
        }
        byte[] tmp = new byte[Math.min(buffer.remaining(), ReadPump.CAPACITY)];
        int n = read(tmp, 0, tmp.length);
        if (n > 0) buffer.put(tmp, 0, n);
        return n;
    }

    private int readNonBlocking(byte[] b, int off, int len) throws IOException {
        lock.lock();
        try {
            IOException error = pump.error();
            if (error != null) throw new IOException(error.getMessage(), error);
            if (!lastReady) throw new IllegalStateException("isReady() returned false");
            if (len == 0) return 0;
            int n = pump.take(b, off, len);
            if (n == 0) {
                lastReady = false;
                throw new IllegalStateException("no data available; call isReady() first");
            }
            if (n == -1) {
                eofReported = true;
                return -1;
            }
            // Drained: the next bytes notify the application again.
            if (pump.buffered() == 0) armed = true;
            return n;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isFinished() {
        if (listener == null) return finished;
        return pump.atEof();
    }

    @Override
    public boolean isReady() {
        if (listener == null) return true;
        lock.lock();
        try {
            if (errored || pump.error() != null) {
                lastReady = false;
                return false;
            }
            if (pump.buffered() > 0 || (pump.atEof() && !eofReported)) {
                lastReady = true;
                return true;
            }
            armed = true;
            lastReady = false;
            return false;
        } finally {
            lock.unlock();
        }
    }

    // ---- non-blocking mode ----

    @Override
    public void setReadListener(ReadListener readListener) {
        Objects.requireNonNull(readListener, "readListener");
        if (host == null || !host.nonBlockingAllowed()) {
            throw new IllegalStateException("the request is neither async-started nor upgraded");
        }
        ReadPump started;
        lock.lock();
        try {
            if (listener != null) throw new IllegalStateException("a ReadListener is already set");
            if (ended) throw new IllegalStateException("the request is over");
            pump = new ReadPump(delegate, new PumpEvents());
            armed = true;
            listener = readListener;
            started = pump;
        } finally {
            lock.unlock();
        }
        started.start();
    }

    /**
     * Ends non-blocking mode: no callback is submitted any more, the pump stops after its current
     * read, and this call waits for that read to return, so the source can go back to its owner
     * (chappe drains an unread body). Idempotent; no-op in blocking mode.
     */
    void endNonBlocking() {
        ReadPump p;
        lock.lock();
        try {
            ended = true;
            p = pump;
        } finally {
            lock.unlock();
        }
        if (p == null) return;
        p.stop();
        p.awaitExit();
    }

    /** Submits {@code callback} unless the stream is over or failed. Caller holds {@link #lock}. */
    private void submit(CallbackSerializer.ThrowingRunnable callback) {
        host.callbacks().submit(() -> {
            lock.lock();
            try {
                if (ended || errored) return;
            } finally {
                lock.unlock();
            }
            callback.run();
        }, this::callbackFailed);
    }

    private void onDataAvailableIfStillAvailable() throws IOException {
        lock.lock();
        try {
            // Another callback consumed the bytes meanwhile: wait for the next arrival instead.
            if (pump.buffered() == 0) {
                if (!pump.atEof()) armed = true;
                return;
            }
        } finally {
            lock.unlock();
        }
        listener.onDataAvailable();
    }

    /** onDataAvailable or onAllDataRead threw: onError, then the owner fails. */
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
            LOG.log(System.Logger.Level.WARNING, "ReadListener.onError threw", e);
        } finally {
            host.failed(t);
        }
    }

    /** The pump's reports, on the pump thread. */
    private final class PumpEvents implements ReadPump.Listener {
        @Override public void dataAvailable() {
            lock.lock();
            try {
                if (!armed || errored || ended) return;
                armed = false;
                submit(ServletInputStreamImpl.this::onDataAvailableIfStillAvailable);
            } finally {
                lock.unlock();
            }
        }

        @Override public void endOfStream() {
            lock.lock();
            try {
                if (allDataReadFired || errored || ended) return;
                allDataReadFired = true;
                submit(() -> listener.onAllDataRead());
            } finally {
                lock.unlock();
            }
        }

        @Override public void failed(IOException e) {
            lock.lock();
            try {
                if (errored || ended) return;
                errored = true;
            } finally {
                lock.unlock();
            }
            host.callbacks().submit(() -> deliverError(e), t -> LOG.log(System.Logger.Level.WARNING,
                    "delivering a read failure failed", t));
        }
    }

    @Override
    public void close() throws IOException {
        if (listener != null) {
            // The pump owns the source until it stops; no further callback after a close.
            lock.lock();
            try { ended = true; }
            finally { lock.unlock(); }
            pump.stop();
            return;
        }
        delegate.close();
    }
}
