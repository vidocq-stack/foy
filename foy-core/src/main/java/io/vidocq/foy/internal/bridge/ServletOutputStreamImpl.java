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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The response body stream: a bounded buffer of {@code bufferSize} bytes, then a live body.
 *
 * <p>Before the response is committed, bytes accumulate in the buffer. A write that would overflow
 * it asks the owning response to commit; the response then either connects a {@link ResponsePipe}
 * ({@link #streamTo}) or, for a response that carries no body on the wire (HEAD, 204, 304),
 * suppresses the body ({@link #suppress}). Once streaming, the buffer keeps coalescing small
 * writes and is pushed into the pipe when it fills, on {@link #flush()}, and at the end of the
 * body. A response that is never committed hands its whole buffer to chappe at the end of the
 * request ({@link #toByteArray()}).</p>
 *
 * <p>Bytes beyond a declared {@code Content-Length} are dropped; reaching it is reported to the
 * owner, which commits the response (Servlet 6.1 section 5.6).</p>
 *
 * <p>Threads (BUG-20261010-01). The stream may be written from the pipeline thread and from
 * async threads in turn; one {@link ReentrantLock} ({@link #lock()}) serialises every operation on
 * the buffer and the pipe (write, flush, close, push, finish, reset), and the response writer takes
 * it too. The lock is held while a push waits for room in the pipe; {@link #abort} wakes such a
 * writer before taking it. At the end of an async cycle the pipeline thread {@linkplain #claim
 * claims} the stream: from then on a write, flush or close from any other thread fails with an
 * {@link IOException} (so a stale loop stops), until a new cycle {@linkplain #open opens} it again.
 * A pipe write that fails (the client is gone) is reported to the owner, which fails the current
 * async cycle ({@code onError}).</p>
 *
 * <p><em>Non-blocking I/O is not implemented in this milestone.</em></p>
 */
public final class ServletOutputStreamImpl extends ServletOutputStream {

    /** The response side of the stream. */
    interface Owner {
        /** The declared Content-Length, or -1. */
        long declaredLength();
        /** The buffer would overflow on a response not yet streaming: commit it for real. */
        void overflow() throws IOException;
        /** An application flush: commit and push the buffered bytes (ignored while the response drains its writer). */
        void flushRequested() throws IOException;
        /** The declared Content-Length has been written in full. */
        void contentLengthReached() throws IOException;
        /** A write into the pipe failed (the client is gone). */
        default void writeFailed(IOException failure) {}
    }

    /** Exposes the internal array, so pushing the buffer into the pipe does not copy it. */
    private static final class Buffer extends ByteArrayOutputStream {
        byte[] array() { return buf; }
    }

    private static final Owner DETACHED = new Owner() {
        @Override public long declaredLength() { return -1; }
        @Override public void overflow() {}
        @Override public void flushRequested() {}
        @Override public void contentLengthReached() {}
    };

    private final ReentrantLock lock = new ReentrantLock();
    /** The only thread allowed to write once claimed; {@code null} while any thread may. */
    private volatile Thread claimant;
    private final Buffer buffer = new Buffer();
    private Owner owner = DETACHED;
    private int limit = 8192;
    /** Body bytes accepted so far (buffered or already pushed). */
    private long written;
    private volatile ResponsePipe pipe;
    private volatile boolean suppressed;
    private boolean closed;
    private boolean discarding;

    /** The lock serialising every buffer and pipe operation (shared with the response writer). */
    ReentrantLock lock() { return lock; }

    /** From now on only the calling thread may write (end of an async cycle). */
    void claim() { this.claimant = Thread.currentThread(); }

    /**
     * Like {@link #claim()}, and {@code retired} (the threads an ended cycle started) are refused
     * for good: a later {@link #open()} by a new cycle does not let them write again.
     */
    void claim(Collection<Thread> retired) {
        this.retired.addAll(retired);
        claim();
    }

    /** Threads of ended cycles, never allowed to write again. */
    private final Set<Thread> retired = ConcurrentHashMap.newKeySet();

    /** Any thread may write again (a new async cycle started). */
    void open() { this.claimant = null; }

    /** Whether the calling thread may write: no claim, or the claiming thread. */
    boolean writableByCurrentThread() {
        Thread current = Thread.currentThread();
        if (!retired.isEmpty() && retired.contains(current)) return false;
        Thread c = claimant;
        return c == null || c == current;
    }

    private void checkWritable() throws IOException {
        if (!writableByCurrentThread()) {
            throw new IOException("the async cycle ended: this thread may no longer write the response");
        }
    }

    void setOwner(Owner owner) { this.owner = owner == null ? DETACHED : owner; }

    /** While discarding, writes are silently dropped (response closed by sendError/sendRedirect). */
    void setDiscarding(boolean discarding) { this.discarding = discarding; }

    boolean isDiscarding() { return discarding; }

    /** Sets the buffer threshold (the response's buffer size). */
    void setLimit(int limit) { this.limit = Math.max(0, limit); }

    /** Whether any body byte was accepted since the last reset. */
    boolean hasContent() { return written > 0 || buffer.size() > 0; }

    /** Body bytes accepted since the last reset. */
    long written() { return written; }

    /** Whether the body goes to the wire live (committed for real). */
    boolean isStreaming() { return pipe != null || suppressed; }

    /** Connects the live body: from now on the buffer is pushed into {@code target}. */
    void streamTo(ResponsePipe target) { this.pipe = target; }

    /** The committed response carries no body on the wire: every byte is dropped from now on. */
    void suppress() {
        lock.lock();
        try {
            this.suppressed = true;
            buffer.reset();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        throw new UnsupportedOperationException("non-blocking write not implemented");
    }

    @Override
    public void write(int b) throws IOException {
        single[0] = (byte) b;
        write(single, 0, 1);
    }

    private final byte[] single = new byte[1];

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        lock.lock();
        try {
            checkWritable();
            writeLocked(b, off, len);
        } finally {
            lock.unlock();
        }
    }

    private void writeLocked(byte[] b, int off, int len) throws IOException {
        if (discarding || len == 0) return;
        if (closed) throw new IOException("stream closed");
        long declared = owner.declaredLength();
        if (declared >= 0) {
            long room = declared - written;
            if (room <= 0) return; // content beyond the declared Content-Length is never sent
            if (len > room) len = (int) room;
        }
        if (!isStreaming() && buffer.size() + len > limit) {
            owner.overflow();
        }
        if (suppressed) {
            written += len;
        } else if (pipe != null) {
            if (buffer.size() + len > limit) {
                push();
                if (len >= limit) pipeWrite(b, off, len);
                else buffer.write(b, off, len);
            } else {
                buffer.write(b, off, len);
            }
            written += len;
        } else {
            buffer.write(b, off, len);
            written += len;
        }
        if (declared >= 0 && written >= declared) owner.contentLengthReached();
    }

    /**
     * Container-generated content (the {@code sendError} page) on a response not yet streaming:
     * buffered whatever the threshold, since an error page may still replace it.
     */
    void writeBuffered(byte[] b) {
        lock.lock();
        try {
            if (isStreaming()) return;
            buffer.write(b, 0, b.length);
            written += b.length;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void flush() throws IOException {
        lock.lock();
        try {
            checkWritable();
            // Servlet 6.1 section 5.2: flushing commits the response and sends the buffered content.
            owner.flushRequested();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            checkWritable();
            if (closed) return;
            closed = true;
            // A live body ends with the stream; a buffered one is sent whole at the end of the request.
            if (pipe != null) finish();
        } finally {
            lock.unlock();
        }
    }

    /** Pushes the buffered bytes into the pipe, when streaming. Blocks while the pipe is full. */
    void push() throws IOException {
        lock.lock();
        try {
            if (pipe == null || buffer.size() == 0) return;
            try {
                pipeWrite(buffer.array(), 0, buffer.size());
            } finally {
                buffer.reset();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Writes into the pipe; a failure (client gone, body aborted) is reported to the owner. */
    private void pipeWrite(byte[] b, int off, int len) throws IOException {
        try {
            pipe.write(b, off, len);
        } catch (IOException e) {
            owner.writeFailed(e);
            throw e;
        }
    }

    /**
     * Ends a live body: pushes what is left and signals EOF. A body shorter than its declared
     * Content-Length cannot be framed correctly any more, so the connection is aborted instead.
     */
    void finish() throws IOException {
        lock.lock();
        try {
            if (pipe == null || ended.get()) return;
            long declared = owner.declaredLength();
            if (declared >= 0 && written < declared) {
                abort(new IOException("response body shorter than its Content-Length ("
                        + written + " < " + declared + ")"));
                return;
            }
            push();
            // finish and abort are mutually exclusive: whichever claims the end first wins.
            if (ended.compareAndSet(false, true)) pipe.finish();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends a live body abnormally: chappe drops the connection. Ignored once the body ended. The
     * pipe is aborted before the lock is taken, so a writer blocked on a full pipe (holding the
     * lock) wakes up with an {@link IOException} instead of making this call wait for the client.
     */
    void abort(Throwable cause) {
        ResponsePipe target = pipe;
        if (target == null || !ended.compareAndSet(false, true)) return;
        target.abort(cause);
        lock.lock();
        try {
            buffer.reset();
        } finally {
            lock.unlock();
        }
    }

    /** Set once the live body ended, normally or not. */
    private final AtomicBoolean ended = new AtomicBoolean();

    public byte[] toByteArray() {
        lock.lock();
        try {
            return buffer.toByteArray();
        } finally {
            lock.unlock();
        }
    }

    /** Bytes currently buffered (not yet pushed). */
    public int size() {
        lock.lock();
        try {
            return buffer.size();
        } finally {
            lock.unlock();
        }
    }

    public void resetBuffer() {
        lock.lock();
        try {
            buffer.reset();
            written = 0;
        } finally {
            lock.unlock();
        }
    }
}
