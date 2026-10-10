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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Turns a blocking {@link InputStream} (chappe's request body, an upgraded connection's input)
 * into a readiness source for non-blocking reads.
 *
 * <p>A virtual thread ({@code foy-read-<n>}) reads up to {@value #CAPACITY} bytes into a buffer,
 * then waits until the application has drained it before reading again. Readiness is this buffer
 * only: nothing ever asks the source for {@code available()} from another thread (it may block
 * behind the pump's read). Each change the application may care about (bytes buffered, end of
 * stream, read failure) is reported once to the {@link Listener}, from the pump thread, outside
 * the pump's lock.</p>
 *
 * <p>{@link #stop(boolean)} ends the pump after its current read, or interrupts that read;
 * {@link #awaitExit()} waits for the pump thread to end, so the owner can hand the source back to
 * chappe (which drains an unread body) without two threads ever reading it at once.</p>
 */
final class ReadPump {

    static final int CAPACITY = 8192;

    /** What the pump reports, on its own thread. */
    interface Listener {
        /** Bytes were buffered while none were. */
        void dataAvailable();
        /** The end of the stream was reached (the buffer is empty then). */
        void endOfStream();
        /** The source failed. */
        void failed(IOException e);
    }

    private static final AtomicLong THREADS = new AtomicLong();

    private final InputStream source;
    private final Listener listener;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition drained = lock.newCondition();
    private final byte[] buffer = new byte[CAPACITY];
    /** Guarded by {@link #lock}. */
    private int pos;
    private int limit;
    private boolean eof;
    private IOException error;
    private boolean stopped;
    private Thread thread;

    ReadPump(InputStream source, Listener listener) {
        this.source = source;
        this.listener = listener;
    }

    void start() {
        Thread t = Thread.ofVirtual().name("foy-read-" + THREADS.incrementAndGet()).unstarted(this::run);
        lock.lock();
        try { thread = t; }
        finally { lock.unlock(); }
        t.start();
    }

    /** The number of buffered bytes. */
    int buffered() {
        lock.lock();
        try { return limit - pos; }
        finally { lock.unlock(); }
    }

    /** The end of the stream was reached and every byte before it was taken. */
    boolean atEof() {
        lock.lock();
        try { return eof && pos == limit; }
        finally { lock.unlock(); }
    }

    /** The read failure, or {@code null}. */
    IOException error() {
        lock.lock();
        try { return error; }
        finally { lock.unlock(); }
    }

    /**
     * Copies up to {@code len} buffered bytes without blocking.
     *
     * @return the number of bytes copied, {@code 0} when nothing is buffered yet, {@code -1} at the
     *         end of the stream
     */
    int take(byte[] b, int off, int len) {
        lock.lock();
        try {
            int n = Math.min(len, limit - pos);
            if (n == 0) return eof && pos == limit && len > 0 ? -1 : 0;
            System.arraycopy(buffer, pos, b, off, n);
            pos += n;
            if (pos == limit) drained.signalAll();
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops the pump after its current read; it reports nothing more. Idempotent, never waits.
     *
     * @param interruptRead also interrupt a blocked read. On HTTP/2's DATA queue the read just ends.
     *        On a socket-backed source, interrupting a virtual thread blocked on a channel read
     *        closes the channel, hence the connection: do it only once the response is delivered
     *        (the hand-back); chappe then closes the connection and never reuses its buffer.
     */
    void stop(boolean interruptRead) {
        Thread t;
        lock.lock();
        try {
            stopped = true;
            drained.signalAll();
            t = thread;
        } finally {
            lock.unlock();
        }
        if (interruptRead && t != null) t.interrupt();
    }

    /** Whether the pump thread is still running (a read may be in progress). */
    boolean isAlive() {
        lock.lock();
        try { return thread != null && thread.isAlive(); }
        finally { lock.unlock(); }
    }

    /** Waits for the pump thread to end (its current read, if any, must return first). */
    void awaitExit() {
        Thread t;
        lock.lock();
        try { t = thread; }
        finally { lock.unlock(); }
        if (t == null || t == Thread.currentThread()) return;
        boolean interrupted = false;
        while (true) {
            try {
                t.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void run() {
        byte[] chunk = new byte[CAPACITY];
        while (true) {
            lock.lock();
            try {
                while (!stopped && pos < limit) drained.awaitUninterruptibly();
                if (stopped) return;
            } finally {
                lock.unlock();
            }
            int n;
            try {
                n = source.read(chunk, 0, CAPACITY);
                if (n == 0) {
                    // A source may return 0 without data: block on a single byte rather than spin.
                    int b = source.read();
                    if (b < 0) n = -1;
                    else { chunk[0] = (byte) b; n = 1; }
                }
            } catch (IOException e) {
                if (record(e)) listener.failed(e);
                return;
            } catch (RuntimeException e) {
                IOException wrapped = new IOException(e);
                if (record(wrapped)) listener.failed(wrapped);
                return;
            }
            if (n < 0) {
                lock.lock();
                try {
                    if (stopped) return;
                    eof = true;
                } finally {
                    lock.unlock();
                }
                listener.endOfStream();
                return;
            }
            lock.lock();
            try {
                if (stopped) return;
                System.arraycopy(chunk, 0, buffer, 0, n);
                pos = 0;
                limit = n;
            } finally {
                lock.unlock();
            }
            listener.dataAvailable();
        }
    }

    private boolean record(IOException e) {
        lock.lock();
        try {
            if (stopped) return false;
            error = e;
            return true;
        } finally {
            lock.unlock();
        }
    }
}
