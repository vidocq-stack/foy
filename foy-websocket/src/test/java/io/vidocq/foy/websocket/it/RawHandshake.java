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
package io.vidocq.foy.websocket.it;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A WebSocket opening handshake written on a plain socket, for the tests that read the response head as sent. */
final class RawHandshake {

    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private RawHandshake() {
    }

    /**
     * Sends a valid handshake for {@code path}, plus the extra header lines, and returns the response
     * head (status line and headers, up to the blank line). The socket is closed afterwards.
     */
    static String send(int port, String path, String... extraHeaderLines) throws IOException {
        return sendWithHost(port, "127.0.0.1:" + port, path, extraHeaderLines);
    }

    /** As {@link #send}, with {@code host} as the value of the {@code Host} line. */
    static String sendWithHost(int port, String host, String path, String... extraHeaderLines) throws IOException {
        try (var socket = open(port, host, path, extraHeaderLines)) {
            return readHead(socket.getInputStream());
        }
    }

    /**
     * Sends a valid handshake for {@code path} and returns the connected socket, its read timeout set,
     * with nothing read yet: the caller reads the response and owns the socket.
     */
    static Socket connect(int port, String path) throws IOException {
        return open(port, "127.0.0.1:" + port, path);
    }

    private static Socket open(int port, String host, String path, String... extraHeaderLines) throws IOException {
        var request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        for (String line : extraHeaderLines) {
            request.append(line).append("\r\n");
        }
        request.append("\r\n");
        var socket = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static String readHead(InputStream in) throws IOException {
        var head = new StringBuilder();
        int c = 0;
        while (c != -1 && !head.toString().endsWith("\r\n\r\n")) {
            c = in.read();
            if (c != -1) {
                head.append((char) c);
            }
        }
        return head.toString();
    }
}
