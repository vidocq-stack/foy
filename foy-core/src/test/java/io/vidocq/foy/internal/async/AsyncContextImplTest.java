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
package io.vidocq.foy.internal.async;

import io.vidocq.foy.internal.async.AsyncContextImpl.CycleEnd;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AsyncContextImplTest {

    /** Records listener events in order, with the context each event carried. */
    private static class Recorder implements AsyncListener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<AsyncContext> lastContext = new AtomicReference<>();
        @Override public void onComplete(AsyncEvent e) throws IOException { record("onComplete", e); }
        @Override public void onTimeout(AsyncEvent e) throws IOException { record("onTimeout", e); }
        @Override public void onError(AsyncEvent e) throws IOException { record("onError", e); }
        @Override public void onStartAsync(AsyncEvent e) throws IOException { record("onStartAsync", e); }
        void record(String name, AsyncEvent e) {
            events.add(name);
            lastContext.set(e.getAsyncContext());
        }
    }

    private static final Runnable NO_FENCE = () -> {};

    @Test
    void completeEndsTheCycle() throws Exception {
        var ctx = newAsync();
        CompletableFuture<CycleEnd> future = CompletableFuture.supplyAsync(() -> ctx.awaitCycleEnd(NO_FENCE));
        Thread.sleep(20);
        assertFalse(future.isDone());
        ctx.complete();
        assertEquals(CycleEnd.COMPLETE, future.get(1, TimeUnit.SECONDS));
        assertTrue(ctx.isCompleted());
    }

    @Test
    void dispatchRecordsPathAndEndsTheCycle() throws Exception {
        var ctx = newAsync();
        CompletableFuture<CycleEnd> future = CompletableFuture.supplyAsync(() -> ctx.awaitCycleEnd(NO_FENCE));
        ctx.dispatch("/target");
        assertEquals(CycleEnd.DISPATCH, future.get(1, TimeUnit.SECONDS));
        assertTrue(ctx.hasDispatch());
        assertEquals("/target", ctx.dispatchPath());
    }

    @Test
    void timeoutFiresOnTimeoutAndEndsWithTimeout() {
        var ctx = newAsync();
        ctx.setTimeout(50);
        var recorder = new Recorder();
        ctx.addListener(recorder);
        long start = System.nanoTime();
        assertEquals(CycleEnd.TIMEOUT, ctx.awaitCycleEnd(NO_FENCE));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 45, "elapsed=" + elapsedMs);
        assertTrue(ctx.timedOut());
        assertEquals(List.of("onTimeout"), recorder.events);
        // The container completes the timed-out cycle: a late dispatch is refused.
        assertThrows(IllegalStateException.class, () -> ctx.dispatch("/late"));
    }

    @Test
    void theFenceRunsBeforeOnTimeout() {
        var ctx = newAsync();
        ctx.setTimeout(10);
        var order = new CopyOnWriteArrayList<String>();
        ctx.addListener(new Recorder() {
            @Override public void onTimeout(AsyncEvent e) { order.add("onTimeout"); }
        });
        ctx.awaitCycleEnd(() -> order.add("fence"));
        assertEquals(List.of("fence", "onTimeout"), order);
    }

    /** Fix round 1: a complete() that lands between the timeout and onTimeout wins, no onTimeout. */
    @Test
    void aCompleteRacingTheTimeoutSuppressesOnTimeout() {
        var ctx = newAsync();
        ctx.setTimeout(10);
        var recorder = new Recorder();
        ctx.addListener(recorder);
        // onResume runs after the wait timed out and before the listeners: the app completes there.
        assertEquals(CycleEnd.COMPLETE, ctx.awaitCycleEnd(ctx::complete));
        assertFalse(ctx.timedOut());
        assertEquals(List.of(), recorder.events);
    }

    @Test
    void aCompleteRacingAFailureSuppressesOnError() {
        var ctx = newAsync();
        var recorder = new Recorder();
        ctx.addListener(recorder);
        ctx.fail(new IOException("client gone"));
        assertEquals(CycleEnd.COMPLETE, ctx.awaitCycleEnd(ctx::complete));
        assertEquals(List.of(), recorder.events);
    }

    @Test
    void aContainerCompletedCycleRefusesDispatchAndIgnoresComplete() {
        var ctx = newAsync();
        ctx.fail(new IllegalStateException("servlet threw"));
        assertEquals(CycleEnd.ERROR, ctx.awaitCycleEnd(NO_FENCE));
        assertTrue(ctx.containerCompleted());
        assertThrows(IllegalStateException.class, () -> ctx.dispatch("/late"));
        assertDoesNotThrow(ctx::complete);
    }

    @Test
    void startRegistersItsThreadBeforeRunning() throws Exception {
        var ctx = newAsync();
        CompletableFuture<Thread> captured = new CompletableFuture<>();
        ctx.start(() -> captured.complete(Thread.currentThread()));
        assertTrue(ctx.startedThreads().contains(captured.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void listenerCompletingInOnTimeoutEndsWithComplete() {
        var ctx = newAsync();
        ctx.setTimeout(20);
        ctx.addListener(new Recorder() {
            @Override public void onTimeout(AsyncEvent e) { e.getAsyncContext().complete(); }
        });
        assertEquals(CycleEnd.COMPLETE, ctx.awaitCycleEnd(NO_FENCE));
    }

    @Test
    void listenerDispatchingInOnTimeoutEndsWithDispatch() {
        var ctx = newAsync();
        ctx.setTimeout(20);
        ctx.addListener(new Recorder() {
            @Override public void onTimeout(AsyncEvent e) { e.getAsyncContext().dispatch("/after"); }
        });
        assertEquals(CycleEnd.DISPATCH, ctx.awaitCycleEnd(NO_FENCE));
        assertEquals("/after", ctx.dispatchPath());
    }

    @Test
    void onCompleteWaitsForTheEndOfTheCycle() {
        var ctx = newAsync();
        var recorder = new Recorder();
        ctx.addListener(recorder);
        ctx.complete();
        assertEquals(List.of(), recorder.events, "onComplete must not fire inside complete()");
        assertEquals(CycleEnd.COMPLETE, ctx.awaitCycleEnd(NO_FENCE));
        ctx.endCycle();
        ctx.endCycle();
        assertEquals(List.of("onComplete"), recorder.events);
    }

    @Test
    void dispatchDoesNotFireOnComplete() {
        var ctx = newAsync();
        var recorder = new Recorder();
        ctx.addListener(recorder);
        ctx.dispatch("/target");
        assertEquals(CycleEnd.DISPATCH, ctx.awaitCycleEnd(NO_FENCE));
        assertEquals(List.of(), recorder.events);
    }

    @Test
    void startRunnableExceptionEndsWithErrorAfterOnError() {
        var ctx = newAsync();
        var recorder = new Recorder();
        ctx.addListener(recorder);
        var boom = new IllegalStateException("boom");
        ctx.start(() -> { throw boom; });
        assertEquals(CycleEnd.ERROR, ctx.awaitCycleEnd(NO_FENCE));
        assertSame(boom, ctx.error());
        assertEquals(List.of("onError"), recorder.events);
    }

    @Test
    void aFailureAfterCompleteIsIgnored() {
        var ctx = newAsync();
        ctx.complete();
        ctx.fail(new IOException("client gone"));
        assertEquals(CycleEnd.COMPLETE, ctx.awaitCycleEnd(NO_FENCE));
        assertNull(ctx.error());
    }

    @Test
    void handOverFiresOnStartAsyncWithTheNewContextAndDropsTheListeners() {
        var first = newAsync();
        var recorder = new Recorder();
        first.addListener(recorder);
        var second = newAsync();
        first.handOverTo(second);
        assertEquals(List.of("onStartAsync"), recorder.events);
        assertSame(second, recorder.lastContext.get());
        first.endCycle();
        second.endCycle();
        assertEquals(List.of("onStartAsync"), recorder.events, "listeners are not carried over");
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers() {
        var ctx = newAsync();
        ctx.addListener(new Recorder() {
            @Override public void onComplete(AsyncEvent e) throws IOException { throw new IOException("listener failure"); }
        });
        var recorder = new Recorder();
        ctx.addListener(recorder);
        ctx.complete();
        ctx.awaitCycleEnd(NO_FENCE);
        ctx.endCycle();
        assertEquals(List.of("onComplete"), recorder.events);
    }

    @Test
    void startRunsRunnableOnVirtualThread() throws Exception {
        var ctx = newAsync();
        CompletableFuture<Thread> captured = new CompletableFuture<>();
        ctx.start(() -> captured.complete(Thread.currentThread()));
        Thread t = captured.get(1, TimeUnit.SECONDS);
        assertTrue(t.isVirtual(), "expected virtual thread: " + t);
    }

    @Test
    void setTimeoutZeroOrNegativeBlocksUntilComplete() throws Exception {
        var ctx = newAsync();
        ctx.setTimeout(0);
        CompletableFuture<CycleEnd> future = CompletableFuture.supplyAsync(() -> ctx.awaitCycleEnd(NO_FENCE));
        Thread.sleep(30);
        assertFalse(future.isDone());
        ctx.complete();
        assertEquals(CycleEnd.COMPLETE, future.get(1, TimeUnit.SECONDS));
    }

    private static AsyncContextImpl newAsync() {
        return new AsyncContextImpl(null, null, new VidocqServletContext("/"), true);
    }
}
