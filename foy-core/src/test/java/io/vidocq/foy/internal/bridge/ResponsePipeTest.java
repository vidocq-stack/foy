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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ResponsePipeTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void readerSeesBytesThenEof() throws Exception {
        var pipe = new ResponsePipe(64);
        pipe.write(bytes("hello "), 0, 6);
        pipe.write(bytes("world"), 0, 5);
        pipe.finish();
        pipe.finish(); // idempotent
        InputStream in = pipe.reader();
        assertEquals("hello world", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        assertEquals(-1, in.read());
    }

    @Test
    void readerBlocksUntilBytesArrive() throws Exception {
        var pipe = new ResponsePipe(64);
        var read = CompletableFuture.supplyAsync(() -> {
            try { return pipe.reader().read(); } catch (IOException e) { throw new RuntimeException(e); }
        }, r -> Thread.ofVirtual().start(r));
        assertThrows(TimeoutException.class, () -> read.get(100, TimeUnit.MILLISECONDS));
        pipe.write(bytes("x"), 0, 1);
        assertEquals('x', read.get(5, TimeUnit.SECONDS));
    }

    @Test
    void availableReportsExactlyTheBufferedBytes() throws Exception {
        var pipe = new ResponsePipe(16);
        InputStream in = pipe.reader();
        // Nothing buffered while the servlet has not written: a reader must treat the next read as
        // blocking (chappe flushes a live known-length body only when available() <= 0).
        assertEquals(0, in.available());
        pipe.write(bytes("abcdefghij"), 0, 10);
        assertEquals(10, in.available());
        byte[] buf = new byte[4];
        assertEquals(4, in.read(buf));
        assertEquals(6, in.available());
        // Wrap around the ring buffer.
        pipe.write(bytes("klmnopqr"), 0, 8);
        assertEquals(14, in.available());
        assertEquals(14, in.readNBytes(14).length);
        assertEquals(0, in.available());
        pipe.finish();
        assertEquals(0, in.available());
    }

    @Test
    void writerBlocksWhenFullAndResumes() throws Exception {
        var pipe = new ResponsePipe(8);
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread.ofVirtual().start(() -> {
            try {
                pipe.write(bytes("0123456789ABCDEF"), 0, 16);
                pipe.finish();
            } catch (Throwable t) {
                failure.set(t);
            }
            done.countDown();
        });
        // 16 bytes cannot fit into 8: the writer stays blocked while nobody reads.
        assertFalse(done.await(200, TimeUnit.MILLISECONDS), "writer must block on a full pipe");
        InputStream in = pipe.reader();
        assertEquals("01234567", new String(in.readNBytes(8), StandardCharsets.US_ASCII));
        assertEquals("89ABCDEF", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
    }

    @Test
    void writeAfterReaderCloseThrows() throws Exception {
        var pipe = new ResponsePipe(8);
        pipe.reader().close();
        IOException e = assertThrows(IOException.class, () -> pipe.write(bytes("x"), 0, 1));
        assertEquals("client disconnected", e.getMessage());
    }

    @Test
    void blockedWriterFailsWhenTheReaderCloses() throws Exception {
        var pipe = new ResponsePipe(4);
        var outcome = new CompletableFuture<Throwable>();
        Thread.ofVirtual().start(() -> {
            try {
                pipe.write(bytes("0123456789"), 0, 10);
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        assertThrows(TimeoutException.class, () -> outcome.get(100, TimeUnit.MILLISECONDS));
        pipe.reader().close();
        assertInstanceOf(IOException.class, outcome.get(5, TimeUnit.SECONDS));
    }

    @Test
    void abortMakesTheReaderThrow() throws Exception {
        var pipe = new ResponsePipe(64);
        pipe.write(bytes("partial"), 0, 7);
        pipe.abort(new IllegalStateException("boom"));
        pipe.finish(); // no effect after an abort
        InputStream in = pipe.reader();
        IOException e = assertThrows(IOException.class, () -> in.read(new byte[16]));
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    @Test
    void abortWakesABlockedReader() throws Exception {
        var pipe = new ResponsePipe(64);
        var outcome = new CompletableFuture<Throwable>();
        Thread.ofVirtual().start(() -> {
            try {
                pipe.reader().read();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        assertThrows(TimeoutException.class, () -> outcome.get(100, TimeUnit.MILLISECONDS));
        pipe.abort(new RuntimeException("boom"));
        assertInstanceOf(IOException.class, outcome.get(5, TimeUnit.SECONDS));
    }

    @Test
    void onCapacityFiresOnce() throws Exception {
        var pipe = new ResponsePipe(4);
        pipe.write(bytes("abcd"), 0, 4);
        assertFalse(pipe.hasCapacity());
        var fired = new AtomicInteger();
        pipe.onCapacity(fired::incrementAndGet);
        assertEquals(0, fired.get(), "no capacity yet");
        InputStream in = pipe.reader();
        assertEquals('a', in.read());
        assertEquals(1, fired.get());
        assertTrue(pipe.hasCapacity());
        assertEquals('b', in.read());
        assertEquals(1, fired.get(), "one-shot callback");
    }

    @Test
    void onCapacityRunsAtOnceWhenThereIsRoom() {
        var pipe = new ResponsePipe(4);
        var fired = new AtomicInteger();
        pipe.onCapacity(fired::incrementAndGet);
        assertEquals(1, fired.get());
    }

    @Test
    void aSecondPendingCapacityCallbackIsRefused() throws Exception {
        var pipe = new ResponsePipe(4);
        pipe.write(bytes("abcd"), 0, 4);
        pipe.onCapacity(() -> {});
        assertThrows(IllegalStateException.class, () -> pipe.onCapacity(() -> {}));
    }

    @Test
    void finishFiresAPendingCapacityCallback() throws Exception {
        var pipe = new ResponsePipe(4);
        pipe.write(bytes("abcd"), 0, 4);
        var fired = new AtomicInteger();
        pipe.onCapacity(fired::incrementAndGet);
        pipe.finish();
        assertEquals(1, fired.get());
    }

    @Test
    void offerTakesWhatFitsWithoutBlocking() throws Exception {
        var pipe = new ResponsePipe(4);
        assertEquals(3, pipe.offer(bytes("abc"), 0, 3));
        assertEquals(1, pipe.offer(bytes("defg"), 0, 4));
        assertEquals(0, pipe.offer(bytes("x"), 0, 1));
        assertEquals("abcd", new String(pipe.reader().readNBytes(4), StandardCharsets.US_ASCII));
        pipe.reader().close();
        assertThrows(IOException.class, () -> pipe.offer(bytes("x"), 0, 1));
    }
}
