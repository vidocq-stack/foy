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

import io.vidocq.foy.websocket.TestSessions.Recorder;
import jakarta.websocket.CloseReason.CloseCodes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTimerTest {

    private static final long WAIT = 5;
    private static final long IDLE_MILLIS = 200;

    private final FakePeer peer = new FakePeer();
    private final Recorder endpoint = new Recorder();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    /** What ended a timer thread abruptly. */
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    /** Counts the threads a timer starts, and keeps what ends them abruptly out of the test output. */
    private final ThreadFactory counting = task -> {
        Thread thread = Thread.ofVirtual().uncaughtExceptionHandler((t, e) -> uncaught.add(e)).unstarted(task);
        threads.add(thread);
        return thread;
    };

    @Test
    void theIdleTimeoutClosesWith1001() throws InterruptedException {
        FoySession session = TestSessions.open(endpoint, peer);
        session.setMaxIdleTimeout(IDLE_MILLIS);
        long lastFrame = System.nanoTime();
        session.onText("activity");

        assertTrue(peer.closed.await(WAIT, SECONDS));
        assertTrue(System.nanoTime() - lastFrame >= MILLISECONDS.toNanos(IDLE_MILLIS),
                "no frame for the whole timeout");
        assertEquals(CloseCodes.GOING_AWAY.getCode(), peer.closeCode());
        session.onClose(CloseCodes.GOING_AWAY.getCode(), "");
        assertEquals(CloseCodes.GOING_AWAY, endpoint.closes.getFirst().getCloseCode());
    }

    @Test
    void noIdleTimeoutByDefault() throws InterruptedException {
        FoySession session = TestSessions.open(endpoint, peer);

        assertEquals(0, session.getMaxIdleTimeout());
        assertFalse(peer.closed.await(2 * IDLE_MILLIS, MILLISECONDS));
        session.onClose(CloseCodes.NORMAL_CLOSURE.getCode(), "");
    }

    @Test
    void theThreadStartsAtTheFirstDeadlineAndEndsAtStop() throws InterruptedException {
        var timer = new SessionTimer(() -> { }, counting);
        timer.touch();
        timer.idle(0);
        assertEquals(List.of(), threads, "nothing armed, no thread");

        timer.idle(60_000);
        timer.sendDeadline(1, System.nanoTime() + SECONDS.toNanos(60), () -> { });
        timer.stop();

        assertEquals(1, threads.size(), "one thread for every deadline");
        assertTrue(threads.getFirst().join(Duration.ofSeconds(WAIT)));
    }

    @Test
    void aDoneSendNeverExpires() throws InterruptedException {
        var idle = new CountDownLatch(1);
        var expired = new CountDownLatch(1);
        var timer = new SessionTimer(idle::countDown, counting);

        timer.sendDeadline(1, System.nanoTime() + MILLISECONDS.toNanos(50), expired::countDown);
        timer.sendDone(1);
        timer.idle(IDLE_MILLIS);

        assertTrue(idle.await(WAIT, SECONDS));
        assertEquals(1, expired.getCount(), "the done send's earlier deadline never ran");
        timer.stop();
    }

    /**
     * An expiry that throws ends the timer's thread, not the timer: the idle timeout armed before it and a
     * send deadline armed after it still fire.
     */
    @Test
    void anExpiryThatThrowsLeavesTheTimerRunning() throws InterruptedException {
        var idle = new CountDownLatch(1);
        var expired = new CountDownLatch(1);
        var timer = new SessionTimer(idle::countDown, counting);

        timer.idle(IDLE_MILLIS);
        timer.sendDeadline(1, System.nanoTime(), () -> {
            throw new AssertionError("an expiry that fails an assertion");
        });

        assertTrue(idle.await(WAIT, SECONDS), "the idle timeout armed before the failure never fired");
        timer.sendDeadline(2, System.nanoTime(), expired::countDown);
        assertTrue(expired.await(WAIT, SECONDS), "a send deadline armed after the failure never fired");
        assertTrue(threads.getFirst().join(Duration.ofSeconds(WAIT)));
        assertEquals(1, uncaught.size(), uncaught::toString);
        assertInstanceOf(AssertionError.class, uncaught.getFirst());
        timer.stop();
    }

    @Test
    void aSendDeadlineRunsItsExpiry() throws InterruptedException {
        var expired = new CountDownLatch(1);
        var timer = new SessionTimer(() -> { }, counting);

        timer.sendDeadline(1, System.nanoTime() + MILLISECONDS.toNanos(50), expired::countDown);

        assertTrue(expired.await(WAIT, SECONDS));
        timer.stop();
    }
}
