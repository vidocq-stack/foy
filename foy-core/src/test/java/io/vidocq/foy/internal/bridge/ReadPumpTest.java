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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The body pump's reads: a source answering 0 bytes, the end of the stream, a failing source. */
class ReadPumpTest {

    /** Answers 0 to every bulk read; single-byte reads come from {@code bytes}. */
    private static final class ZeroThenSingleBytes extends InputStream {
        private final int[] bytes;
        private int next;

        ZeroThenSingleBytes(int... bytes) {
            this.bytes = bytes;
        }

        @Override public int read() {
            return next < bytes.length ? bytes[next++] : -1;
        }

        @Override public int read(byte[] b, int off, int len) {
            return 0;
        }
    }

    /** Counts the reports of the pump. */
    private static final class Events implements ReadPump.Listener {
        final CountDownLatch data = new CountDownLatch(1);
        final CountDownLatch end = new CountDownLatch(1);
        final AtomicReference<IOException> failure = new AtomicReference<>();
        final CountDownLatch failed = new CountDownLatch(1);

        @Override public void dataAvailable() { data.countDown(); }
        @Override public void endOfStream() { end.countDown(); }
        @Override public void failed(IOException e) {
            failure.set(e);
            failed.countDown();
        }
    }

    @Test
    void aZeroByteReadFallsBackToASingleByteRead() throws InterruptedException {
        var events = new Events();
        var pump = new ReadPump(new ZeroThenSingleBytes('x'), events);
        pump.start();
        assertTrue(events.data.await(5, TimeUnit.SECONDS));
        byte[] b = new byte[4];
        assertEquals(1, pump.take(b, 0, b.length));
        assertEquals('x', b[0]);
        assertTrue(events.end.await(5, TimeUnit.SECONDS));
        assertTrue(pump.atEof());
        assertEquals(-1, pump.take(b, 0, b.length));
        pump.awaitExit();
    }

    @Test
    void aZeroByteReadThenTheEndOfTheStreamEndsThePump() throws InterruptedException {
        var events = new Events();
        var pump = new ReadPump(new ZeroThenSingleBytes(), events);
        pump.start();
        assertTrue(events.end.await(5, TimeUnit.SECONDS));
        assertEquals(1L, events.data.getCount());
        pump.awaitExit();
    }

    @Test
    void aRuntimeExceptionOfTheSourceIsReportedAsAnIoFailure() throws InterruptedException {
        var boom = new IllegalStateException("boom");
        var events = new Events();
        var pump = new ReadPump(new InputStream() {
            @Override public int read() {
                throw boom;
            }

            @Override public int read(byte[] b, int off, int len) {
                throw boom;
            }
        }, events);
        pump.start();
        assertTrue(events.failed.await(5, TimeUnit.SECONDS));
        assertSame(boom, events.failure.get().getCause());
        assertSame(events.failure.get(), pump.error());
        pump.awaitExit();
    }
}
