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
import java.nio.ByteBuffer;
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
 * <p><b>Non-blocking mode</b> (Servlet 6.1 section 3.7), entered by
 * {@link #setWriteListener(WriteListener)} when the {@link NonBlockingHost} allows it (the request
 * is async-started or upgraded). Writes never block: what the pipe cannot take at once is kept by
 * the stream ({@code pending}) and fed to the pipe as capacity frees up (a capacity callback starts
 * a short drain on a virtual thread; the callback itself runs on chappe's reader thread and never
 * takes the lock). Coalescing in the buffer is unchanged. {@link #isReady()} is {@code true} while no
 * byte waits for the pipe (before the commit, while the buffer has room or the overflowing write
 * can commit); so the stream keeps at most one buffer plus one write beyond what the pipe holds.
 * A {@code false} answer arms the next {@code onWritePossible}, submitted once the kept bytes are
 * in the pipe. A write, flush or writer operation while {@code isReady()} last answered
 * {@code false}, or while bytes still wait (the bound), throws {@link IllegalStateException}; the
 * first write in an {@code onWritePossible} needs no prior {@code isReady()}. {@code close()}
 * returns at once and the body ends once the kept bytes are drained. A pipe failure, or an
 * exception thrown by {@code onWritePossible}, goes to {@code onError} and then
 * {@link NonBlockingHost#failed} (the async cycle fails). Every callback goes through the host's
 * {@link CallbackSerializer}. When the async cycle ends ({@link #endNonBlocking()}) the stream is
 * blocking again (the listener stays set): kept bytes go out first, in order, from the next
 * blocking operation or the end of the body.</p>
 *
 * <p>Over a plain {@link java.io.OutputStream} (an upgraded connection), the same machinery applies
 * to a {@link ResponsePipe} whose reader a copying thread drains into that stream.</p>
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

    private static final System.Logger LOG = System.getLogger(ServletOutputStreamImpl.class.getName());

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

    // ---- non-blocking state, guarded by lock ----
    private NonBlockingHost host;
    private volatile WriteListener listener;
    /** A listener is set and the async cycle that set it is still open. */
    private volatile boolean nonBlocking;
    /** {@link #isReady()} last answered {@code false} (or a write was refused); cleared by a true answer or {@code onWritePossible}. */
    private boolean notReady;
    private boolean writePossibleQueued;
    /** A capacity callback is registered on the pipe. */
    private boolean drainArmed;
    /** A non-blocking close or end of body waits for the kept bytes to drain. */
    private boolean finishWhenDrained;
    /** onError was (or is about to be) delivered: no other callback runs. */
    private boolean errored;
    /** Nesting depth of response-writer operations: their bytes were checked once, up front. */
    private int writerOpDepth;
    /** Bytes kept for the pipe in non-blocking mode: {@code pend[pStart, pEnd)}. */
    private byte[] pend = new byte[0];
    private int pStart;
    private int pEnd;

    /** The owner of non-blocking mode; without one {@link #setWriteListener} throws. */
    void setHost(NonBlockingHost host) { this.host = host; }

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
        if (!nonBlocking) return true;
        lock.lock();
        try {
            if (!nonBlocking) return true;
            if (errored) return false;
            boolean ready;
            try {
                ready = readyNow();
            } catch (IOException e) {
                return false; // onError follows
            }
            notReady = !ready;
            if (!ready) armDrain();
            return ready;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        Objects.requireNonNull(writeListener, "writeListener");
        if (host == null || !host.nonBlockingAllowed()) {
            throw new IllegalStateException("the request is neither async-started nor upgraded");
        }
        lock.lock();
        try {
            if (listener != null) throw new IllegalStateException("a WriteListener is already set");
            listener = writeListener;
            nonBlocking = true;
            // Runs once the dispatch that set the listener returned (the serializer is held until then).
            submitWritePossible();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The async cycle ended: the stream is blocking again (callbacks are over; the listener stays
     * set, so a second {@link #setWriteListener} still fails). Bytes still kept go out first.
     */
    void endNonBlocking() {
        lock.lock();
        try {
            nonBlocking = false;
            notReady = false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void write(int b) throws IOException {
        lock.lock();
        try {
            checkWritable();
            checkNonBlockingWrite();
            single[0] = (byte) b;
            writeLocked(single, 0, 1);
        } finally {
            lock.unlock();
        }
    }

    private final byte[] single = new byte[1];

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        lock.lock();
        try {
            checkWritable();
            checkNonBlockingWrite();
            writeLocked(b, off, len);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Servlet 6.1: blocking mode writes the whole buffer; non-blocking mode when not ready throws
     * {@link IllegalStateException} and leaves the buffer untouched.
     */
    @Override
    public void write(ByteBuffer buffer) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        lock.lock();
        try {
            checkWritable();
            checkNonBlockingWrite();
            int len = buffer.remaining();
            if (len == 0) return;
            if (buffer.hasArray()) {
                writeLocked(buffer.array(), buffer.arrayOffset() + buffer.position(), len);
                buffer.position(buffer.limit());
                return;
            }
            byte[] tmp = new byte[Math.min(len, 8192)];
            while (buffer.hasRemaining()) {
                int n = Math.min(tmp.length, buffer.remaining());
                buffer.get(tmp, 0, n);
                writeLocked(tmp, 0, n);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * A response-writer operation starts (caller holds {@link #lock()}): one readiness check up
     * front when {@code check} (an application operation, not an internal drain), then every byte
     * the operation encodes is accepted until {@link #endWriterOperation()}.
     */
    void beginWriterOperation(boolean check) throws IOException {
        if (check) checkNonBlockingWrite();
        writerOpDepth++;
    }

    void endWriterOperation() { writerOpDepth--; }

    /**
     * Non-blocking mode: an application write is refused ({@link IllegalStateException}) while
     * {@code isReady()} last answered {@code false}, or while bytes still wait for the pipe (which
     * arms {@code onWritePossible}, as a false {@code isReady()} would). Caller holds the lock.
     */
    private void checkNonBlockingWrite() throws IOException {
        if (!nonBlocking || writerOpDepth > 0) return;
        if (notReady) throw new IllegalStateException("isReady() returned false: the write would block");
        if (!readyNow()) {
            notReady = true;
            armDrain();
            throw new IllegalStateException("the stream is not ready: the write would block");
        }
    }

    /** No byte waits for the pipe, after moving what fits now. A pipe failure goes to onError and is rethrown. */
    private boolean readyNow() throws IOException {
        if (pendingEmpty()) return true;
        try {
            offerPending();
        } catch (IOException e) {
            writeFailure(e);
            throw e;
        }
        return pendingEmpty();
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
                if (len >= limit) toPipe(b, off, len);
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
            checkNonBlockingWrite();
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

    /**
     * Pushes the buffered bytes into the pipe, when streaming. Blocking mode blocks while the pipe
     * is full; non-blocking mode keeps what does not fit.
     */
    void push() throws IOException {
        lock.lock();
        try {
            if (pipe == null || buffer.size() == 0) return;
            try {
                toPipe(buffer.array(), 0, buffer.size());
            } finally {
                buffer.reset();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Sends bytes to the pipe after the kept ones: non-blocking keeps what does not fit, blocking waits. */
    private void toPipe(byte[] b, int off, int len) throws IOException {
        if (nonBlocking) {
            if (pendingEmpty()) {
                int n;
                try {
                    n = pipe.offer(b, off, len);
                } catch (IOException e) {
                    writeFailure(e);
                    throw e;
                }
                off += n;
                len -= n;
            }
            if (len > 0) {
                appendPending(b, off, len);
                armDrain();
            }
        } else {
            flushPendingBlocking();
            pipeWrite(b, off, len);
        }
    }

    /** Writes into the pipe, blocking; a failure (client gone, body aborted) is reported. */
    private void pipeWrite(byte[] b, int off, int len) throws IOException {
        try {
            pipe.write(b, off, len);
        } catch (IOException e) {
            writeFailure(e);
            throw e;
        }
    }

    /** A pipe failure: the WriteListener's onError in non-blocking mode, otherwise the owner (the cycle's onError). */
    private void writeFailure(IOException e) {
        if (nonBlocking) failNonBlocking(e);
        else owner.writeFailed(e);
    }

    // ---- kept bytes (non-blocking mode) ----

    private boolean pendingEmpty() { return pStart == pEnd; }

    private void appendPending(byte[] b, int off, int len) {
        int kept = pEnd - pStart;
        if (pEnd + len > pend.length) {
            if (kept + len <= pend.length) {
                System.arraycopy(pend, pStart, pend, 0, kept);
            } else {
                byte[] grown = new byte[Math.max(2 * pend.length, kept + len)];
                System.arraycopy(pend, pStart, grown, 0, kept);
                pend = grown;
            }
            pStart = 0;
            pEnd = kept;
        }
        System.arraycopy(b, off, pend, pEnd, len);
        pEnd += len;
    }

    /** Moves what fits now from the kept bytes into the pipe. */
    private void offerPending() throws IOException {
        if (pendingEmpty()) return;
        pStart += pipe.offer(pend, pStart, pEnd - pStart);
        if (pStart == pEnd) pStart = pEnd = 0;
    }

    /** Blocking mode again: the kept bytes go first, waiting for room. */
    private void flushPendingBlocking() throws IOException {
        if (pendingEmpty()) return;
        int from = pStart;
        int len = pEnd - pStart;
        pStart = pEnd = 0;
        pipeWrite(pend, from, len);
    }

    /**
     * Registers for the pipe's next capacity event, once. The callback runs on chappe's reader
     * thread (or at once, here): it only starts {@link #drainOnCapacity} on a virtual thread, since
     * the stream lock may be held by a writer blocked on that very reader.
     */
    private void armDrain() {
        if (drainArmed || pipe == null) return;
        drainArmed = true;
        pipe.onCapacity(() -> Thread.ofVirtual().name("foy-write-drain").start(this::drainOnCapacity));
    }

    /** Capacity freed up: kept bytes go out; then the pending close ends the body, and onWritePossible follows if awaited. */
    private void drainOnCapacity() {
        lock.lock();
        try {
            drainArmed = false;
            if (pipe == null || ended.get()) return;
            try {
                offerPending();
            } catch (IOException e) {
                writeFailure(e);
                return;
            }
            if (!pendingEmpty()) {
                armDrain();
                return;
            }
            if (finishWhenDrained) {
                finishWhenDrained = false;
                endPipe();
            }
            if (nonBlocking && notReady && !writePossibleQueued && !errored) submitWritePossible();
        } finally {
            lock.unlock();
        }
    }

    // ---- WriteListener callbacks ----

    /** Caller holds the lock. */
    private void submitWritePossible() {
        writePossibleQueued = true;
        host.callbacks().submit(this::runWritePossible, this::callbackFailed);
    }

    private void runWritePossible() throws IOException {
        lock.lock();
        try {
            writePossibleQueued = false;
            if (!nonBlocking || errored) return;
            if (!readyNow()) {
                // Bytes written meanwhile still wait: the next capacity event calls again.
                notReady = true;
                armDrain();
                return;
            }
            // The listener may write at once, without an isReady() call first.
            notReady = false;
        } finally {
            lock.unlock();
        }
        listener.onWritePossible();
    }

    /** A pipe failure outside a callback: onError (then the host) in the next callback slot. Caller holds the lock. */
    private void failNonBlocking(Throwable t) {
        if (errored) return;
        errored = true;
        host.callbacks().submit(() -> deliverError(t),
                e -> LOG.log(System.Logger.Level.WARNING, "delivering a write failure failed", e));
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

    /**
     * Ends a live body: pushes what is left and signals EOF. A body shorter than its declared
     * Content-Length cannot be framed correctly any more, so the connection is aborted instead.
     * In non-blocking mode this never waits: the body ends once the kept bytes are drained.
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
            if (nonBlocking && !pendingEmpty()) {
                finishWhenDrained = true;
                armDrain();
                return;
            }
            flushPendingBlocking();
            endPipe();
        } finally {
            lock.unlock();
        }
    }

    /** finish and abort are mutually exclusive: whichever claims the end first wins. */
    private void endPipe() {
        if (ended.compareAndSet(false, true)) pipe.finish();
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
