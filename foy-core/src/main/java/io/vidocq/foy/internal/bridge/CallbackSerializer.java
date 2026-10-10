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

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Runs listener callbacks for one request one at a time, with the application's class loader.
 *
 * <p>Servlet 6.1 section 2.3.3.4: the container serialises the non-blocking I/O callbacks
 * ({@code ReadListener}, {@code WriteListener}) of a request, and section 3.7 forbids them to run
 * while the dispatch that registered the listener is still in progress. Callbacks are queued and run
 * in submission order on a virtual thread ({@code foy-callback-<n>}), never two at once, with the
 * application's class loader as the thread context class loader. An exception thrown by a callback
 * goes to the {@code onError} consumer given with it, in the same serialised slot.</p>
 *
 * <p>The owner gates the queue: {@link #hold()} while a dispatch runs (it waits for the callback in
 * progress, so the dispatch never overlaps one), {@link #release()} once it returned, and
 * {@link #close()} when the request (or upgraded connection) is over: queued callbacks are dropped
 * and later submissions ignored. A new serializer is released. {@link #hold()} and {@link #close()}
 * must not be called from a callback (they wait for it to return).</p>
 */
final class CallbackSerializer {

    /** A callback that may throw; the exception goes to the {@code onError} consumer. */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final System.Logger LOG = System.getLogger(CallbackSerializer.class.getName());
    private static final AtomicLong THREADS = new AtomicLong();

    private record Task(ThrowingRunnable callback, Consumer<Throwable> onError) {}

    private final ClassLoader applicationLoader;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    /** Guarded by {@link #lock}. */
    private final ArrayDeque<Task> queue = new ArrayDeque<>();
    /** A drain thread is running callbacks. Guarded by {@link #lock}. */
    private boolean running;
    /** Guarded by {@link #lock}. */
    private boolean held;
    /** Guarded by {@link #lock}. */
    private boolean closed;
    /** The thread running callbacks, while {@link #running}. Guarded by {@link #lock}. */
    private Thread drainer;

    CallbackSerializer(ClassLoader applicationLoader) {
        this.applicationLoader = applicationLoader;
    }

    /** Queues {@code r}; runs on a virtual thread after any running callback; exceptions go to {@code onError}. */
    void submit(ThrowingRunnable r, Consumer<Throwable> onError) {
        lock.lock();
        try {
            if (closed) return;
            queue.add(new Task(r, onError));
            startDrainerIfNeeded();
        } finally {
            lock.unlock();
        }
    }

    /** Stops starting callbacks and waits for the one in progress, if any. */
    void hold() {
        lock.lock();
        try {
            held = true;
            awaitIdle();
        } finally {
            lock.unlock();
        }
    }

    /** Lets the queued callbacks run again. */
    void release() {
        lock.lock();
        try {
            held = false;
            startDrainerIfNeeded();
        } finally {
            lock.unlock();
        }
    }

    /** Drops the queued callbacks, ignores later ones, and waits for the one in progress. Idempotent. */
    void close() {
        lock.lock();
        try {
            closed = true;
            queue.clear();
            awaitIdle();
        } finally {
            lock.unlock();
        }
    }

    /** Whether a callback is running or about to run. */
    boolean isRunning() {
        lock.lock();
        try {
            return running;
        } finally {
            lock.unlock();
        }
    }

    private void awaitIdle() {
        if (drainer == Thread.currentThread()) {
            throw new IllegalStateException("a callback cannot wait for itself");
        }
        while (running) idle.awaitUninterruptibly();
    }

    private void startDrainerIfNeeded() {
        if (running || held || closed || queue.isEmpty()) return;
        running = true;
        drainer = Thread.ofVirtual().name("foy-callback-" + THREADS.incrementAndGet()).unstarted(this::drain);
        drainer.start();
    }

    private void drain() {
        while (true) {
            Task task;
            lock.lock();
            try {
                task = held || closed ? null : queue.poll();
                if (task == null) {
                    running = false;
                    drainer = null;
                    idle.signalAll();
                    return;
                }
            } finally {
                lock.unlock();
            }
            runOne(task);
        }
    }

    private void runOne(Task task) {
        // Per callback: a callback may change the TCCL, the next one still gets the application's.
        Thread.currentThread().setContextClassLoader(applicationLoader);
        try {
            task.callback().run();
        } catch (Throwable t) {
            Thread.currentThread().setContextClassLoader(applicationLoader);
            try {
                task.onError().accept(t);
            } catch (Throwable e) {
                LOG.log(System.Logger.Level.WARNING, "a listener's onError threw", e);
            }
        }
    }
}
