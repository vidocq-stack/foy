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
package io.vidocq.foy.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.fail;

/** A raw TCP client for the end-to-end tests: sends text, reads the head and what follows. */
final class RawClient implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();

    RawClient(int port) throws IOException {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(10_000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    void send(String s) throws IOException {
        send(s.getBytes(StandardCharsets.US_ASCII));
    }

    void send(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    void shutdownOutput() throws IOException { socket.shutdownOutput(); }

    /** Aborts the connection with a TCP reset. */
    void reset() throws IOException {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    /** The response head, up to and including the blank line. */
    String readHead() throws IOException {
        var head = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            head.append((char) c);
            if (head.toString().endsWith("\r\n\r\n")) break;
        }
        return head.toString();
    }

    /** Reads until everything received after the head contains {@code marker}. */
    void readUntil(String marker) throws IOException {
        byte[] buf = new byte[1024];
        while (!received().contains(marker)) {
            int n = in.read(buf);
            if (n == -1) fail("connection closed before '" + marker + "': " + received());
            received.write(buf, 0, n);
        }
    }

    /**
     * Reads up to and including {@code marker}, without keeping what it reads: for a long stream, such as
     * what a send to a peer that did not read had queued.
     */
    void skipPast(byte[] marker) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int matched = 0;
        while (matched < marker.length) {
            int n = in.read(buf);
            if (n == -1) fail("connection closed before the marker");
            for (int i = 0; i < n && matched < marker.length; i++) {
                if (buf[i] == marker[matched]) {
                    matched++;
                } else {
                    matched = buf[i] == marker[0] ? 1 : 0;
                }
            }
        }
    }

    /** What was received after the head so far. */
    String received() { return received.toString(StandardCharsets.US_ASCII); }

    /** Reads to the end of the connection; returns what this call read. */
    String readAll() throws IOException {
        return new String(readAllBytes(), StandardCharsets.US_ASCII);
    }

    byte[] readAllBytes() throws IOException {
        var all = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
        } catch (SocketException _) {
            // a reset ends the connection too
        }
        received.writeBytes(all.toByteArray());
        return all.toByteArray();
    }

    /**
     * Reads everything up to the end of the connection, which must come within {@code bound}: the end of the
     * stream, or a reset. Keeps only the last bytes read, up to {@code tailLength}.
     *
     * @return the last bytes read before the end, at most {@code tailLength}
     */
    byte[] readToEndWithin(java.time.Duration bound, int tailLength) throws IOException {
        long deadline = System.nanoTime() + bound.toNanos();
        byte[] buf = new byte[64 * 1024];
        var tail = new java.io.ByteArrayOutputStream();
        try {
            int n = 0;
            while (n != -1) {
                long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) fail("the connection did not end within " + bound);
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
                n = in.read(buf);
                if (n > 0) tail = keepLast(tail, buf, n, tailLength);
            }
        } catch (java.net.SocketTimeoutException _) {
            fail("the connection did not end within " + bound);
        } catch (SocketException _) {
            // a reset ends the connection too
        }
        return tail.toByteArray();
    }

    /** {@code tail} followed by the first {@code n} bytes of {@code buf}, cut to its last {@code length} bytes. */
    private static java.io.ByteArrayOutputStream keepLast(java.io.ByteArrayOutputStream tail, byte[] buf, int n,
            int length) {
        tail.write(buf, 0, n);
        if (tail.size() <= length) return tail;
        byte[] all = tail.toByteArray();
        var kept = new java.io.ByteArrayOutputStream();
        kept.write(all, all.length - length, length);
        return kept;
    }

    @Override public void close() throws IOException { socket.close(); }
}
