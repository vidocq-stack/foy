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

import io.vidocq.foy.internal.container.ContextLoaderScope;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /**
     * A callback that may throw; the exception goes to the {@code onError} consumer.
     *
     * @param <E> the checked exception the callback may throw
     */
    @FunctionalInterface
    interface ThrowingRunnable<E extends Exception> {
        void run() throws E;
    }

    private static final System.Logger LOG = System.getLogger(CallbackSerializer.class.getName());
    private static final AtomicLong THREADS = new AtomicLong();

    private record Task(ThrowingRunnable<?> callback, Consumer<Throwable> onError) {}

    /** The application class loader of the next callback, read per callback; {@code null}: TCCL untouched. */
    private final Supplier<ClassLoader> applicationLoader;
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

    /** Callbacks run with {@code applicationLoader} as context loader ({@code null}: left untouched). */
    CallbackSerializer(ClassLoader applicationLoader) {
        this(() -> applicationLoader);
    }

    /**
     * Callbacks run with the loader {@code applicationLoader} answers when each one starts: the owner
     * (a request) may hand its callbacks to another application, the one that opened its async cycle.
     */
    CallbackSerializer(Supplier<ClassLoader> applicationLoader) {
        this.applicationLoader = Objects.requireNonNull(applicationLoader, "applicationLoader");
    }

    /** Queues {@code r}; runs on a virtual thread after any running callback; exceptions go to {@code onError}. */
    void submit(ThrowingRunnable<?> r, Consumer<Throwable> onError) {
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

    /**
     * {@link #close()} that may also be called from a callback (an upgraded connection closed by
     * its own listener): from the callback thread it does not wait for itself. Idempotent.
     */
    void shutdown() {
        lock.lock();
        try {
            closed = true;
            queue.clear();
            if (!Thread.currentThread().equals(drainer)) awaitIdle();
        } finally {
            lock.unlock();
        }
    }

    /** Whether {@link #hold()} has taken effect (no queued callback starts until {@link #release()}). */
    boolean isHeld() {
        lock.lock();
        try {
            return held;
        } finally {
            lock.unlock();
        }
    }

    /** Whether the calling thread is the one running callbacks (it is inside a callback). */
    boolean isCallbackThread() {
        lock.lock();
        try {
            return Thread.currentThread().equals(drainer);
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
        if (Thread.currentThread().equals(drainer)) {
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
        for (Task task = next(); task != null; task = next()) {
            runOne(task);
        }
    }

    /** The next callback to run, or {@code null} when the drain stops (queue empty, held or closed). */
    private Task next() {
        lock.lock();
        try {
            Task task = held || closed ? null : queue.poll();
            if (task == null) {
                running = false;
                drainer = null;
                idle.signalAll();
            }
            return task;
        } finally {
            lock.unlock();
        }
    }

    private void runOne(Task task) {
        ClassLoader loader = applicationLoader.get();
        Throwable failure = failureOf(loader, task.callback());
        if (failure == null) return;
        Throwable onErrorFailure = failureOf(loader, () -> task.onError().accept(failure));
        if (onErrorFailure != null) LOG.log(System.Logger.Level.WARNING, "a listener's onError threw", onErrorFailure);
    }

    /**
     * Runs {@code code} with {@code loader}, the application's class loader, as the thread context
     * class loader, installed for each call and restored after it (a callback may change it, the next
     * one still gets the application's; BUG-20261022-06), and returns what it threw, or {@code null}.
     * This is the boundary of the application code on the callback thread: whatever a callback
     * throws, an Error included, goes to its {@code onError} (whose Servlet API signature takes a
     * {@code Throwable}), and the drain thread survives it.
     */
    private static Throwable failureOf(ClassLoader loader, ThrowingRunnable<?> code) {
        try (var _ = ContextLoaderScope.enter(loader)) {
            code.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
