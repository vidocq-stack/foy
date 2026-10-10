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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-20261010-01: once the pipeline thread claims the response output at the end of an async
 * cycle, another thread's writes fail instead of racing it, until a new cycle opens the output.
 */
class ResponseOutputFenceTest {

    private static String body(HttpServletResponseImpl res) {
        return new String(res.bodyBytes(), StandardCharsets.ISO_8859_1);
    }

    /** Runs {@code action} on another thread and returns what it threw, or {@code null}. */
    private static Throwable onOtherThread(ThrowingRunnable action) throws Exception {
        var outcome = new CompletableFuture<Throwable>();
        Thread.ofVirtual().start(() -> {
            try { action.run(); outcome.complete(null); }
            catch (Throwable t) { outcome.complete(t); }
        });
        return outcome.get(5, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    @Test
    void anotherThreadsStreamWriteFailsOnceTheOutputIsClaimed() throws Exception {
        var res = new HttpServletResponseImpl();
        var out = res.getOutputStream();
        out.write('a');
        res.claimOutput();

        assertInstanceOf(IOException.class, onOtherThread(() -> out.write('b')));
        assertInstanceOf(IOException.class, onOtherThread(out::flush));
        out.write('c'); // the claiming thread keeps writing
        assertEquals("ac", body(res));
    }

    @Test
    void anotherThreadsWriterWriteIsDiscardedAndReportedThroughCheckError() throws Exception {
        var res = new HttpServletResponseImpl();
        var writer = res.getWriter();
        writer.write("a");
        res.claimOutput();

        var error = new AtomicReference<Boolean>();
        assertNull(onOtherThread(() -> { writer.write("stale"); error.set(writer.checkError()); }));
        assertTrue(error.get(), "a fenced writer write must set checkError()");
        writer.write("b");
        assertEquals("ab", body(res), "the stale characters must not reach the body later");
    }

    @Test
    void openingANewCycleLiftsTheFence() throws Exception {
        var res = new HttpServletResponseImpl();
        var out = res.getOutputStream();
        res.claimOutput();
        res.openOutput(_ -> {});
        assertNull(onOtherThread(() -> out.write('z')));
        assertEquals("z", body(res));
    }

    @Test
    void aPipeWriteFailureIsReportedToTheOpenCycle() throws Exception {
        var res = new HttpServletResponseImpl();
        var pipe = new AtomicReference<ResponsePipe>();
        res.bindCommitTarget(r -> pipe.set(r.startStreaming()));
        var failures = new CopyOnWriteArrayList<IOException>();
        res.openOutput(failures::add);
        var out = res.getOutputStream();
        out.write('a');
        out.flush(); // commits: the body is live
        pipe.get().reader().close(); // the client is gone

        out.write('b');
        assertThrows(IOException.class, out::flush);
        assertEquals(1, failures.size(), "the cycle hears the failure (onError)");

        res.claimOutput(); // the cycle ended: later failures are not reported to it
        assertThrows(IOException.class, () -> { out.write('c'); out.flush(); });
        assertEquals(1, failures.size());
    }

    /** Fix round 1: println/printf (writer monitor first) against flushBuffer (stream lock first). */
    @Test
    void printlnOnOneThreadAndFlushBufferOnAnotherDoNotDeadlock() throws Exception {
        var res = new HttpServletResponseImpl();
        res.setBufferSize(1 << 22);
        var writer = res.getWriter();
        var go = new CountDownLatch(1);
        var printer = new CompletableFuture<Throwable>();
        var flusher = new CompletableFuture<Throwable>();
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                for (int i = 0; i < 5_000; i++) {
                    writer.println("line");
                    writer.printf("%d%n", i);
                    writer.println(i);
                }
                printer.complete(null);
            } catch (Throwable t) { printer.complete(t); }
        });
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                for (int i = 0; i < 5_000; i++) res.flushBuffer();
                flusher.complete(null);
            } catch (Throwable t) { flusher.complete(t); }
        });
        go.countDown();
        assertNull(printer.get(10, TimeUnit.SECONDS), "println/printf side hung or failed");
        assertNull(flusher.get(10, TimeUnit.SECONDS), "flushBuffer side hung or failed");
    }

    @Test
    void aRetiredCycleThreadStaysRefusedAfterANewCycleOpensTheOutput() throws Exception {
        var res = new HttpServletResponseImpl();
        var out = res.getOutputStream();
        var stale = new CompletableFuture<Throwable>();
        var release = new CountDownLatch(1);
        Thread staleThread = Thread.ofVirtual().unstarted(() -> {
            try { release.await(); out.write('s'); stale.complete(null); }
            catch (Throwable t) { stale.complete(t); }
        });
        res.claimOutput(java.util.List.of(staleThread)); // cycle N ends
        res.openOutput(_ -> {});                         // cycle N+1 opens the output again
        staleThread.start();
        release.countDown();
        assertInstanceOf(IOException.class, stale.get(5, TimeUnit.SECONDS));
        assertNull(onOtherThread(() -> out.write('n')), "a thread of the new cycle may write");
        assertEquals("n", body(res));
    }

    @Test
    void finishThenAbortLeavesAWholeBody() throws Exception {
        var res = new HttpServletResponseImpl();
        var pipe = new AtomicReference<ResponsePipe>();
        res.bindCommitTarget(r -> pipe.set(r.startStreaming()));
        var out = res.getOutputStream();
        out.write('a');
        out.flush();
        res.finishBody();
        res.abortBody(new IOException("late abort"));
        assertArrayEquals(new byte[] {'a'}, pipe.get().reader().readAllBytes());
    }

    @Test
    void abortThenFinishLeavesAnAbortedBody() throws Exception {
        var res = new HttpServletResponseImpl();
        var pipe = new AtomicReference<ResponsePipe>();
        res.bindCommitTarget(r -> pipe.set(r.startStreaming()));
        var out = res.getOutputStream();
        out.write('a');
        out.flush();
        res.abortBody(new IOException("abort"));
        res.finishBody();
        assertThrows(IOException.class, () -> pipe.get().reader().readAllBytes());
    }

    @Test
    void writersOnTwoThreadsAreSerialised() throws Exception {
        var res = new HttpServletResponseImpl();
        res.setBufferSize(1 << 20);
        var out = res.getOutputStream();
        int perThread = 20_000;
        var go = new CountDownLatch(1);
        var a = new CompletableFuture<Throwable>();
        var b = new CompletableFuture<Throwable>();
        for (var pair : new Object[][] {{a, (byte) 'a'}, {b, (byte) 'b'}}) {
            @SuppressWarnings("unchecked") var done = (CompletableFuture<Throwable>) pair[0];
            byte value = (Byte) pair[1];
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) out.write(value);
                    done.complete(null);
                } catch (Throwable t) { done.complete(t); }
            });
        }
        go.countDown();
        assertNull(a.get(5, TimeUnit.SECONDS));
        assertNull(b.get(5, TimeUnit.SECONDS));
        assertEquals(2 * perThread, res.bodyBytes().length, "no byte may be lost to a racing write");
    }
}
