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

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CallbackSerializerTest {

    @Test
    void callbacksRunInOrderOneAtATime() throws Exception {
        var serializer = new CallbackSerializer(getClass().getClassLoader());
        var inside = new AtomicBoolean();
        var overlap = new AtomicBoolean();
        var order = new CopyOnWriteArrayList<Integer>();
        var done = new CountDownLatch(50);
        for (int i = 0; i < 50; i++) {
            int n = i;
            serializer.submit(() -> {
                if (!inside.compareAndSet(false, true)) overlap.set(true);
                Thread.yield();
                order.add(n);
                inside.set(false);
                done.countDown();
            }, t -> fail(t));
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(overlap.get());
        for (int i = 0; i < 50; i++) assertEquals(i, order.get(i));
    }

    @Test
    void callbacksRunWithTheApplicationClassLoader() throws Exception {
        var loader = new URLClassLoader(new URL[0], getClass().getClassLoader());
        var serializer = new CallbackSerializer(loader);
        var seen = new AtomicReference<ClassLoader>();
        var done = new CountDownLatch(1);
        serializer.submit(() -> {
            seen.set(Thread.currentThread().getContextClassLoader());
            assertTrue(Thread.currentThread().isVirtual());
            done.countDown();
        }, t -> fail(t));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertSame(loader, seen.get());
    }

    @Test
    void anExceptionGoesToItsOnError() throws Exception {
        var serializer = new CallbackSerializer(getClass().getClassLoader());
        var boom = new IllegalStateException("boom");
        var received = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        serializer.submit(() -> { throw boom; }, t -> { received.set(t); done.countDown(); });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertSame(boom, received.get());
    }

    @Test
    void holdWaitsForTheRunningCallbackAndDefersTheQueuedOnes() throws Exception {
        var serializer = new CallbackSerializer(getClass().getClassLoader());
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        serializer.submit(() -> {
            entered.countDown();
            assertTrue(proceed.await(5, TimeUnit.SECONDS));
            events.add("first");
        }, t -> fail(t));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var second = new CountDownLatch(1);
        serializer.submit(() -> { events.add("second"); second.countDown(); }, t -> fail(t));

        var holder = Thread.ofVirtual().start(() -> {
            serializer.hold();
            events.add("held");
        });
        proceed.countDown();
        holder.join(5_000);
        assertFalse(holder.isAlive());
        // Held: the queued callback does not start.
        assertFalse(second.await(200, TimeUnit.MILLISECONDS));
        assertEquals(List.of("first", "held"), events);

        serializer.release();
        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("first", "held", "second"), events);
    }

    @Test
    void closeDropsQueuedCallbacksAndIgnoresLaterOnes() throws Exception {
        var serializer = new CallbackSerializer(getClass().getClassLoader());
        serializer.hold();
        var ran = new AtomicBoolean();
        serializer.submit(() -> ran.set(true), t -> fail(t));
        serializer.close();
        serializer.release();
        serializer.submit(() -> ran.set(true), t -> fail(t));
        // A drain thread starts synchronously in submit/release: once closed, none ever starts.
        assertFalse(serializer.isRunning());
        assertFalse(ran.get());
    }
}
