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

import jakarta.servlet.ReadListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServletInputStreamImplTest {

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

    private static final ReadListener NOOP = new ReadListener() {
        @Override public void onDataAvailable() {}
        @Override public void onAllDataRead() {}
        @Override public void onError(Throwable t) {}
    };

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void setReadListenerNullThrowsNpe() {
        var in = new ServletInputStreamImpl(bytes("x"), new Host(true));
        assertThrows(NullPointerException.class, () -> in.setReadListener(null));
    }

    @Test
    void setReadListenerWithoutAsyncThrowsIse() {
        var notAsync = new ServletInputStreamImpl(bytes("x"), new Host(false));
        assertThrows(IllegalStateException.class, () -> notAsync.setReadListener(NOOP));
        var noHost = new ServletInputStreamImpl(bytes("x"));
        assertThrows(IllegalStateException.class, () -> noHost.setReadListener(NOOP));
    }

    @Test
    void setReadListenerTwiceThrowsIse() {
        var in = new ServletInputStreamImpl(bytes("x"), new Host(true));
        try {
            in.setReadListener(NOOP);
            assertThrows(IllegalStateException.class, () -> in.setReadListener(NOOP));
        } finally {
            end(in);
        }
    }

    @Test
    void readByteBufferBlockingAndEof() throws IOException {
        var in = new ServletInputStreamImpl(bytes("abc"));
        assertThrows(NullPointerException.class, () -> in.read((ByteBuffer) null));
        assertEquals(0, in.read(ByteBuffer.allocate(0)));
        var two = ByteBuffer.allocate(2);
        assertEquals(2, in.read(two));
        assertEquals("ab", new String(two.array(), StandardCharsets.US_ASCII));
        assertEquals(0, two.remaining());
        var direct = ByteBuffer.allocateDirect(8);
        assertEquals(1, in.read(direct));
        direct.flip();
        assertEquals('c', direct.get());
        assertEquals(-1, in.read(ByteBuffer.allocate(4)));
        assertTrue(in.isFinished());
    }

    @Test
    void readWhenNotReadyThrowsIse() throws Exception {
        var pipe = new PipedOutputStream();
        var source = new PipedInputStream(pipe);
        var in = new ServletInputStreamImpl(source, new Host(true));
        try {
            in.setReadListener(NOOP);
            assertFalse(in.isReady());
            assertThrows(IllegalStateException.class, in::read);
            assertThrows(IllegalStateException.class, () -> in.read(new byte[4]));
            assertThrows(IllegalStateException.class, () -> in.read(ByteBuffer.allocate(4)));
        } finally {
            pipe.close(); // EOF: the pump's blocked read returns
            end(in);
        }
    }

    @Test
    void dataThenEofDriveOnDataAvailableThenOnAllDataRead() throws Exception {
        var pipe = new PipedOutputStream();
        var source = new PipedInputStream(pipe);
        var host = new Host(true);
        var in = new ServletInputStreamImpl(source, host);
        var events = new CopyOnWriteArrayList<String>();
        var allRead = new CountDownLatch(1);
        var firstDrained = new CountDownLatch(1);
        try {
            in.setReadListener(new ReadListener() {
                @Override public void onDataAvailable() throws IOException {
                    events.add("onDataAvailable");
                    byte[] buf = new byte[16];
                    int n;
                    while (in.isReady() && (n = in.read(buf)) != -1) {
                        events.add(new String(buf, 0, n, StandardCharsets.US_ASCII));
                    }
                    firstDrained.countDown();
                }
                @Override public void onAllDataRead() { events.add("onAllDataRead"); allRead.countDown(); }
                @Override public void onError(Throwable t) { events.add("onError"); }
            });
            pipe.write("Hello".getBytes(StandardCharsets.US_ASCII));
            pipe.flush();
            assertTrue(firstDrained.await(5, TimeUnit.SECONDS));
            pipe.write("World".getBytes(StandardCharsets.US_ASCII));
            pipe.close();
            assertTrue(allRead.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("onDataAvailable", "Hello", "onDataAvailable", "World", "onAllDataRead"),
                    List.copyOf(events));
            assertTrue(in.isFinished());
            assertTrue(host.failures.isEmpty());
        } finally {
            end(in);
        }
    }

    @Test
    void aReadErrorGoesToOnErrorAndFailsTheHost() throws Exception {
        var broken = new IOException("connection reset");
        var host = new Host(true);
        var in = new ServletInputStreamImpl(new InputStream() {
            @Override public int read() throws IOException { throw broken; }
        }, host);
        var received = new CopyOnWriteArrayList<Throwable>();
        var done = new CountDownLatch(1);
        try {
            in.setReadListener(new ReadListener() {
                @Override public void onDataAvailable() { fail("no data"); }
                @Override public void onAllDataRead() { fail("no EOF"); }
                @Override public void onError(Throwable t) { received.add(t); done.countDown(); }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(broken), List.copyOf(received));
        } finally {
            host.callbacks.close(); // waits for the callback in progress
            end(in);
        }
        assertEquals(List.of(broken), List.copyOf(host.failures));
        assertFalse(in.isReady());
    }

    @Test
    void aThrowingOnDataAvailableGoesToOnError() throws Exception {
        var boom = new IllegalStateException("boom");
        var host = new Host(true);
        var in = new ServletInputStreamImpl(bytes("x"), host);
        var received = new CopyOnWriteArrayList<Throwable>();
        var done = new CountDownLatch(1);
        try {
            in.setReadListener(new ReadListener() {
                @Override public void onDataAvailable() { throw boom; }
                @Override public void onAllDataRead() {}
                @Override public void onError(Throwable t) { received.add(t); done.countDown(); }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            host.callbacks.close(); // waits for the callback in progress
            end(in);
        }
        assertEquals(List.of(boom), List.copyOf(received));
        assertEquals(List.of(boom), List.copyOf(host.failures));
    }
    /** {@code source}, counting {@code reading} down when a read starts. */
    private static InputStream signalOnRead(InputStream source, CountDownLatch reading) {
        return new java.io.FilterInputStream(source) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                reading.countDown();
                return super.read(b, off, len);
            }
        };
    }

    /** Stops non-blocking mode and waits for the pump, as the bridge does once the response is out. */
    private static void end(ServletInputStreamImpl in) {
        in.endNonBlocking(false);
        in.awaitPumpExit();
    }

    /** A listener may read straight away in onDataAvailable: data is buffered, no isReady() needed. */
    @Test
    void onDataAvailableMayReadWithoutCallingIsReadyFirst() throws Exception {
        var host = new Host(true);
        var in = new ServletInputStreamImpl(bytes("Hello"), host);
        var got = new CopyOnWriteArrayList<String>();
        var done = new CountDownLatch(1);
        try {
            in.setReadListener(new ReadListener() {
                @Override public void onDataAvailable() throws IOException {
                    byte[] buf = new byte[16];
                    int n = in.read(buf);
                    got.add(new String(buf, 0, n, StandardCharsets.US_ASCII));
                }
                @Override public void onAllDataRead() { got.add("EOF"); done.countDown(); }
                @Override public void onError(Throwable t) { got.add("onError:" + t); done.countDown(); }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            host.callbacks.close();
            end(in);
        }
        assertEquals(List.of("Hello", "EOF"), List.copyOf(got));
        assertTrue(host.failures.isEmpty());
    }

    /** Ending non-blocking mode never waits for a blocked read; joining the pump does, until the read returns. */
    @Test
    void endNonBlockingWithThePumpBlockedOnARead() throws Exception {
        var pipe = new PipedOutputStream();
        var reading = new CountDownLatch(1);
        var in = new ServletInputStreamImpl(signalOnRead(new PipedInputStream(pipe), reading), new Host(true));
        in.setReadListener(NOOP);
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        in.endNonBlocking(false); // returns at once although the pump is blocked on the pipe
        var joiner = Thread.ofVirtual().start(in::awaitPumpExit);
        assertFalse(joiner.join(java.time.Duration.ofMillis(200)), "the pump's read is still blocked");
        pipe.close(); // the blocked read returns
        assertTrue(joiner.join(java.time.Duration.ofSeconds(5)));
    }

    /** A source that honours interrupts (HTTP/2 DATA queue) is released at once by an interrupting stop. */
    @Test
    void anInterruptingStopReleasesTheBlockedRead() throws Exception {
        var pipe = new PipedOutputStream();
        var reading = new CountDownLatch(1);
        var in = new ServletInputStreamImpl(signalOnRead(new PipedInputStream(pipe), reading), new Host(true));
        try {
            in.setReadListener(NOOP);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            in.endNonBlocking(true);
            var joiner = Thread.ofVirtual().start(in::awaitPumpExit);
            assertTrue(joiner.join(java.time.Duration.ofSeconds(5)));
        } finally {
            pipe.close();
        }
    }
}
