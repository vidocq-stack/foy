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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The deadlines of one session (WEBSOCKET.md section 5.4): the idle timeout, no frame in or out for
 * that long, and the deadline of the asynchronous send being written. Like Chappe's
 * {@code ConnectionWatchdog}, one thread keeps them all: started at the first armed deadline, it
 * parks while nothing is armed and sleeps to the nearest deadline otherwise. A frame only writes a
 * timestamp ({@link #touch()}): the thread, waking at a deadline the activity moved, sleeps again.
 *
 * <p>An expiry runs on the timer's thread, then the timer keeps going until {@link #stop()}: the
 * idle timeout is disarmed once it fired, and a send deadline fires once.
 */
final class SessionTimer {

    /** The longest timeout applied, about 73 years, so the arithmetic in nanoseconds cannot overflow. */
    private static final long MAX_NANOS = Long.MAX_VALUE / 4;

    /**
     * The deadline of one send. A class, not a record: the timer and the send race on its slot by
     * identity.
     */
    private static final class SendDeadline {
        private final long id;
        private final long nanos;
        private final Runnable onExpiry;

        private SendDeadline(long id, long nanos, Runnable onExpiry) {
            this.id = id;
            this.nanos = nanos;
            this.onExpiry = onExpiry;
        }
    }

    private final Runnable onIdle;
    private final ThreadFactory threads;
    private final AtomicReference<Thread> thread = new AtomicReference<>();
    private final AtomicReference<SendDeadline> send = new AtomicReference<>();

    /** The idle timeout in nanoseconds, {@code 0} for none. */
    private volatile long idleNanos;
    private volatile long lastActivity = System.nanoTime();
    /** The thread parks with nothing armed. */
    private volatile boolean parkedIdle;
    /** The thread sleeps towards {@link #parkedUntil}. */
    private volatile boolean parkedTimed;
    private volatile long parkedUntil;
    private volatile boolean stopped;

    /**
     * @param onIdle  runs on the timer's thread when the idle timeout passes
     * @param threads makes the timer's one thread, a virtual one
     */
    SessionTimer(Runnable onIdle, ThreadFactory threads) {
        this.onIdle = onIdle;
        this.threads = threads;
    }

    /**
     * Sets the idle timeout, counted from now or from the last frame after now.
     *
     * @param timeoutMillis the timeout, {@code 0} or less for none
     */
    void idle(long timeoutMillis) {
        long nanos = timeoutMillis <= 0 ? 0 : Math.min(TimeUnit.MILLISECONDS.toNanos(timeoutMillis), MAX_NANOS);
        touch();
        idleNanos = nanos;
        if (nanos > 0) {
            wake(lastActivity + nanos);
        }
    }

    /** A frame came in or went out: the idle timeout counts from now. */
    void touch() {
        lastActivity = System.nanoTime();
    }

    /**
     * Arms the deadline of send {@code id}, in place of the previous send's one.
     *
     * @param nanos    the deadline, a {@link System#nanoTime()} instant
     * @param onExpiry runs on the timer's thread at the deadline, unless {@link #sendDone(long)} came first
     */
    void sendDeadline(long id, long nanos, Runnable onExpiry) {
        send.set(new SendDeadline(id, nanos, onExpiry));
        wake(nanos);
    }

    /** Disarms the deadline of send {@code id}, if it is still the armed one. */
    void sendDone(long id) {
        SendDeadline armed = send.get();
        if (armed != null && armed.id == id) {
            send.compareAndSet(armed, null);
        }
    }

    /** Ends the timer's thread, if any; nothing fires afterwards. */
    void stop() {
        stopped = true;
        Thread t = thread.get();
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * Starts the thread at the first deadline, or wakes it when it parks with nothing armed or sleeps
     * towards a later deadline than {@code deadline}.
     */
    private void wake(long deadline) {
        Thread t = thread.get();
        if (t == null) {
            start();
        } else if (parkedIdle || (parkedTimed && deadline - parkedUntil < 0)) {
            LockSupport.unpark(t);
        }
    }

    /** Starts the thread once; a thread losing the race to start it is never started. */
    private void start() {
        if (stopped) {
            return;
        }
        Thread t = threads.newThread(this::watch);
        if (thread.compareAndSet(null, t)) {
            t.start();
        }
    }

    private void watch() {
        while (!stopped) {
            turn();
        }
    }

    /** One turn: fires a deadline that passed, or sleeps towards the nearest one, or parks. */
    private void turn() {
        long now = System.nanoTime();
        long idle = idleNanos;
        long idleAt = lastActivity + idle;
        if (idle > 0 && idleAt - now <= 0) {
            idleNanos = 0;
            onIdle.run();
            return;
        }
        SendDeadline armed = send.get();
        if (armed != null && armed.nanos - now <= 0) {
            // A send that ends now races on the slot: exactly one of them wins.
            if (send.compareAndSet(armed, null)) {
                armed.onExpiry.run();
            }
            return;
        }
        if (idle <= 0 && armed == null) {
            parkWhileNothingIsArmed();
        } else {
            sleepUntil(nearest(idle, idleAt, armed), now);
        }
    }

    /** The nearer of the idle deadline and the send deadline, one of which at least is armed. */
    private static long nearest(long idle, long idleAt, SendDeadline armed) {
        if (armed == null) {
            return idleAt;
        }
        return idle <= 0 || armed.nanos - idleAt < 0 ? armed.nanos : idleAt;
    }

    private void parkWhileNothingIsArmed() {
        // Dekker pairing with wake(): either this sees the new deadline, or the arming thread sees
        // the flag and unparks.
        parkedIdle = true;
        if (idleNanos <= 0 && send.get() == null && !stopped) {
            LockSupport.park(this);
        }
        parkedIdle = false;
    }

    private void sleepUntil(long until, long now) {
        // Dekker pairing with wake(): an earlier deadline armed after this point sees parkedUntil
        // and unparks; one armed before it is seen by the check below.
        parkedUntil = until;
        parkedTimed = true;
        if (!armedBefore(until) && !stopped) {
            LockSupport.parkNanos(this, until - now);
        }
        parkedTimed = false;
    }

    /** Whether a deadline earlier than {@code until} is armed. */
    private boolean armedBefore(long until) {
        long idle = idleNanos;
        SendDeadline armed = send.get();
        return (idle > 0 && lastActivity + idle - until < 0) || (armed != null && armed.nanos - until < 0);
    }
}
