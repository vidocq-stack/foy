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

    @Override public void close() throws IOException { socket.close(); }
}
