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

import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServletOutputStreamImplTest {

    /** A host standing for an async-started request (or not). */
    private static final class Host implements NonBlockingHost {
        final boolean allowed;
        final CallbackSerializer callbacks = new CallbackSerializer(Host.class.getClassLoader());
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        Host(boolean allowed) { this.allowed = allowed; }
        @Override public boolean nonBlockingAllowed() { return allowed; }
        @Override public CallbackSerializer callbacks() { return callbacks; }
        @Override public void failed(Throwable t) { failures.add(t); }
    }

    /** Counts onWritePossible calls; each one releases a permit. */
    private static final class Listener implements WriteListener {
        final Semaphore writePossible = new Semaphore(0);
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch errored = new CountDownLatch(1);
        @Override public void onWritePossible() { writePossible.release(); }
        @Override public void onError(Throwable t) { errors.add(t); errored.countDown(); }

        void awaitWritePossible() throws InterruptedException {
            assertTrue(writePossible.tryAcquire(5, TimeUnit.SECONDS), "onWritePossible expected");
        }
    }

    private static byte[] bytes(int n, char c) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) c);
        return b;
    }

    /** A live (committed) stream over a pipe of {@code capacity} bytes, with a buffer of {@code limit} bytes. */
    private static ServletOutputStreamImpl streaming(Host host, ResponsePipe pipe, int limit) {
        var out = new ServletOutputStreamImpl();
        out.setHost(host);
        out.setLimit(limit);
        out.streamTo(pipe);
        return out;
    }

    @Test
    void setWriteListenerNullThrowsNpe() {
        var out = new ServletOutputStreamImpl();
        out.setHost(new Host(true));
        assertThrows(NullPointerException.class, () -> out.setWriteListener(null));
    }

    @Test
    void setWriteListenerWithoutAsyncThrowsIse() {
        var notAsync = new ServletOutputStreamImpl();
        notAsync.setHost(new Host(false));
        assertThrows(IllegalStateException.class, () -> notAsync.setWriteListener(new Listener()));
        var noHost = new ServletOutputStreamImpl();
        assertThrows(IllegalStateException.class, () -> noHost.setWriteListener(new Listener()));
    }

    @Test
    void setWriteListenerTwiceThrowsIse() throws Exception {
        var out = new ServletOutputStreamImpl();
        out.setHost(new Host(true));
        var listener = new Listener();
        out.setWriteListener(listener);
        assertThrows(IllegalStateException.class, () -> out.setWriteListener(new Listener()));
        listener.awaitWritePossible();
    }

    @Test
    void onWritePossibleWaitsForTheDispatchThatSetTheListener() throws Exception {
        var host = new Host(true);
        host.callbacks.hold(); // the dispatch is still running
        var out = new ServletOutputStreamImpl();
        out.setHost(host);
        var listener = new Listener();
        out.setWriteListener(listener);
        assertFalse(listener.writePossible.tryAcquire(100, TimeUnit.MILLISECONDS));
        host.callbacks.release(); // the dispatch returned
        listener.awaitWritePossible();
    }

    @Test
    void writeByteBufferWritesAll() throws IOException {
        var out = new ServletOutputStreamImpl();
        assertThrows(NullPointerException.class, () -> out.write((ByteBuffer) null));
        var heap = ByteBuffer.wrap("hello ".getBytes(StandardCharsets.US_ASCII));
        out.write(heap);
        assertFalse(heap.hasRemaining());
        var direct = ByteBuffer.allocateDirect(20_000);
        direct.put(bytes(20_000, 'x')).flip();
        out.setLimit(1 << 20);
        out.write(direct);
        assertFalse(direct.hasRemaining());
        byte[] all = out.toByteArray();
        assertEquals(6 + 20_000, all.length);
        assertEquals("hello x", new String(all, 0, 7, StandardCharsets.US_ASCII));
    }

    @Test
    void writeWhenNotReadyThrowsIse() throws Exception {
        var host = new Host(true);
        var pipe = new ResponsePipe(16);
        var out = streaming(host, pipe, 8);
        var listener = new Listener();
        out.setWriteListener(listener);
        listener.awaitWritePossible();

        out.write(bytes(16, 'a')); // fills the pipe, nothing kept: still ready
        assertTrue(out.isReady());
        out.write(bytes(16, 'b')); // kept by the stream: a write never blocks
        assertFalse(out.isReady());

        assertThrows(IllegalStateException.class, () -> out.write('c'));
        assertThrows(IllegalStateException.class, () -> out.write(bytes(4, 'c')));
        assertThrows(IllegalStateException.class, out::flush);
        var buffer = ByteBuffer.wrap(bytes(4, 'c'));
        assertThrows(IllegalStateException.class, () -> out.write(buffer));
        assertEquals(0, buffer.position(), "a refused ByteBuffer is left untouched");

        // The client reads: capacity frees up, the kept bytes go out and onWritePossible follows.
        assertEquals(16, pipe.reader().readNBytes(16).length);
        listener.awaitWritePossible();
        // Right after onWritePossible a write needs no isReady() call first.
        out.write(bytes(2, 'd'));
        out.close();
        assertEquals("b".repeat(16) + "dd",
                new String(pipe.reader().readAllBytes(), StandardCharsets.US_ASCII));
    }

    @Test
    void aWriteRightAfterOnWritePossibleNeedsNoIsReadyCall() throws Exception {
        var host = new Host(true);
        var pipe = new ResponsePipe(16);
        var out = streaming(host, pipe, 8);
        var written = new CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        out.setWriteListener(new WriteListener() {
            @Override public void onWritePossible() {
                try {
                    out.write(bytes(4, 'z'));
                } catch (Throwable t) {
                    failure.set(t);
                }
                written.countDown();
            }
            @Override public void onError(Throwable t) {}
        });
        assertTrue(written.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
    }

    @Test
    void nonBlockingCloseReturnsAtOnceAndEndsTheBodyOnceDrained() throws Exception {
        var host = new Host(true);
        var pipe = new ResponsePipe(16);
        var out = streaming(host, pipe, 8);
        var listener = new Listener();
        out.setWriteListener(listener);
        listener.awaitWritePossible();
        out.write(bytes(40, 'q')); // 16 in the pipe, 24 kept
        out.close(); // must not wait for the client
        assertEquals("q".repeat(40), new String(pipe.reader().readAllBytes(), StandardCharsets.US_ASCII));
    }

    @Test
    void largeWriteIsDeliveredInOrderAcrossManyCapacityEvents() throws Exception {
        var host = new Host(true);
        var pipe = new ResponsePipe(64);
        var out = streaming(host, pipe, 32);
        var done = new CountDownLatch(1);
        int total = 100_000;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var next = new java.util.concurrent.atomic.AtomicInteger();
        out.setWriteListener(new WriteListener() {
            @Override public void onWritePossible() throws IOException {
                calls.incrementAndGet();
                while (out.isReady()) {
                    int start = next.get();
                    if (start == total) {
                        out.close();
                        done.countDown();
                        return;
                    }
                    int n = Math.min(10, total - start);
                    byte[] chunk = new byte[n];
                    for (int i = 0; i < n; i++) chunk[i] = (byte) (start + i);
                    out.write(chunk);
                    next.addAndGet(n);
                }
            }
            @Override public void onError(Throwable t) {}
        });
        byte[] all = pipe.reader().readAllBytes();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(total, all.length);
        for (int i = 0; i < total; i++) assertEquals((byte) i, all[i], "byte " + i);
        assertTrue(calls.get() > 1, "onWritePossible once per capacity event: " + calls.get());
    }

    @Test
    void aClientGoneReachesOnErrorThenTheHost() throws Exception {
        var host = new Host(true);
        var pipe = new ResponsePipe(16);
        var out = streaming(host, pipe, 8);
        var listener = new Listener();
        out.setWriteListener(listener);
        listener.awaitWritePossible();
        out.write(bytes(40, 'q')); // 24 bytes kept, waiting for capacity
        assertFalse(out.isReady());
        pipe.reader().close(); // the client is gone
        assertTrue(listener.errored.await(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, listener.errors.getFirst());
        // host.failed runs right after onError, in the same callback slot.
        host.callbacks.close();
        assertEquals(1, host.failures.size());
    }

    @Test
    void anExceptionFromOnWritePossibleReachesOnError() throws Exception {
        var host = new Host(true);
        var out = new ServletOutputStreamImpl();
        out.setHost(host);
        var errored = new CountDownLatch(1);
        var boom = new IOException("boom");
        out.setWriteListener(new WriteListener() {
            @Override public void onWritePossible() throws IOException { throw boom; }
            @Override public void onError(Throwable t) { if (t == boom) errored.countDown(); }
        });
        assertTrue(errored.await(5, TimeUnit.SECONDS));
        host.callbacks.close();
        assertEquals(List.of(boom), host.failures);
    }

    @Test
    void endingNonBlockingModeMakesWritesBlockingAgainAndKeepsTheOrder() throws Exception {
        var host = new Host(true);
        var pipe = new ResponsePipe(16);
        var out = streaming(host, pipe, 8);
        var listener = new Listener();
        out.setWriteListener(listener);
        listener.awaitWritePossible();
        out.write(bytes(24, 'a')); // 16 in the pipe, 8 kept
        assertFalse(out.isReady());
        out.endNonBlocking(); // the async cycle ended
        assertTrue(out.isReady());
        var received = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try {
                received.complete(new String(pipe.reader().readAllBytes(), StandardCharsets.US_ASCII));
            } catch (IOException e) {
                received.completeExceptionally(e);
            }
        });
        out.write(bytes(4, 'b')); // blocking mode: no ISE
        out.finish();
        assertEquals("a".repeat(24) + "bbbb", received.get(5, TimeUnit.SECONDS));
    }
}
