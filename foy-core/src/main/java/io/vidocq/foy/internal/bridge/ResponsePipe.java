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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded single-producer byte pipe between a servlet's output and chappe's body reader.
 *
 * <p>A committed response hands chappe {@link #reader()} as its body; the servlet's output stream
 * feeds {@link #write}. The pipe is a ring buffer guarded by one {@link ReentrantLock} with two
 * conditions (data available, capacity available). Unlike {@link java.io.PipedInputStream} it is
 * not tied to the liveness of the threads that used it, which matters because the servlet output
 * may be written from several threads in turn (the request thread, then an async thread).</p>
 *
 * <p>{@link InputStream#available()} on the reader reports exactly the buffered bytes, and
 * {@code 0} while the servlet has not written more: chappe flushes a live known-length body to
 * the client only when the next read could block ({@code available() <= 0}).</p>
 */
final class ResponsePipe {

    private final byte[] ring;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition readable = lock.newCondition();
    private final Condition writable = lock.newCondition();
    private final Reader reader = new Reader();
    /** Index of the next byte to read. */
    private int head;
    /** Number of buffered bytes. */
    private int count;
    private boolean finished;
    private Throwable aborted;
    /** Set once the reader is closed: the client is gone (or chappe stopped reading). */
    private boolean broken;
    private Runnable capacityCallback;

    ResponsePipe(int capacityBytes) {
        if (capacityBytes <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacityBytes);
        this.ring = new byte[capacityBytes];
    }

    /** Blocks while full; IOException("client disconnected") once the reader side is closed. */
    void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        while (len > 0) {
            lock.lock();
            try {
                while (count == ring.length && !broken && aborted == null && !finished) {
                    writable.awaitUninterruptibly();
                }
                if (broken) throw new IOException("client disconnected");
                if (aborted != null) throw new IOException("response aborted", aborted);
                if (finished) throw new IOException("response body already ended");
                int tail = (head + count) % ring.length;
                int n = Math.min(len, Math.min(ring.length - count, ring.length - tail));
                System.arraycopy(b, off, ring, tail, n);
                count += n;
                off += n;
                len -= n;
                readable.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Non-blocking readiness used by WriteListener (Task 5.8): true when at least one byte fits. */
    boolean hasCapacity() {
        lock.lock();
        try {
            return count < ring.length || broken || aborted != null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Registers a one-shot callback fired when capacity frees up (Task 5.8). It runs at once, on
     * the calling thread, when the pipe already has room or the reader is gone (the next write
     * then reports the failure); otherwise on the reader's thread, outside the lock.
     */
    void onCapacity(Runnable r) {
        lock.lock();
        try {
            if (count == ring.length && !broken && aborted == null) {
                capacityCallback = r;
                return;
            }
        } finally {
            lock.unlock();
        }
        r.run();
    }

    /** Normal end of body: the reader sees EOF after the buffered bytes. Idempotent. */
    void finish() {
        lock.lock();
        try {
            finished = true;
            readable.signalAll();
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Abnormal end: the reader's next read throws IOException, which makes chappe drop the connection. */
    void abort(Throwable cause) {
        Runnable callback;
        lock.lock();
        try {
            if (finished && count == 0) return; // already fully delivered: nothing left to abort
            if (aborted == null) aborted = cause == null ? new IOException("response aborted") : cause;
            readable.signalAll();
            writable.signalAll();
            callback = takeCallback();
        } finally {
            lock.unlock();
        }
        if (callback != null) callback.run();
    }

    /** The chappe-facing side; close() by chappe marks the pipe broken (client gone). */
    InputStream reader() {
        return reader;
    }

    private Runnable takeCallback() {
        Runnable callback = capacityCallback;
        capacityCallback = null;
        return callback;
    }

    private final class Reader extends InputStream {

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) return 0;
            Runnable callback;
            int n;
            lock.lock();
            try {
                while (true) {
                    if (aborted != null) throw new IOException("response aborted", aborted);
                    if (broken) throw new IOException("pipe closed");
                    if (count > 0) break;
                    if (finished) return -1;
                    try {
                        readable.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("interrupted while waiting for the response body");
                    }
                }
                n = Math.min(len, Math.min(count, ring.length - head));
                System.arraycopy(ring, head, b, off, n);
                head = (head + n) % ring.length;
                count -= n;
                writable.signalAll();
                callback = takeCallback();
            } finally {
                lock.unlock();
            }
            if (callback != null) callback.run();
            return n;
        }

        @Override
        public int available() {
            lock.lock();
            try {
                return aborted != null || broken ? 0 : count;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            Runnable callback;
            lock.lock();
            try {
                if (broken) return;
                // A close after the whole body was read is the normal end, not a disconnect; it still
                // makes any later write fail, which no correct writer issues after finish().
                broken = true;
                count = 0;
                readable.signalAll();
                writable.signalAll();
                callback = takeCallback();
            } finally {
                lock.unlock();
            }
            if (callback != null) callback.run();
        }
    }
}
