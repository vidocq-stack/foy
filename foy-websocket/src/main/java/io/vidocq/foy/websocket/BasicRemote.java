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
package io.vidocq.foy.websocket;

import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;

/**
 * The blocking remote of a session (WEBSOCKET.md section 5.4): each send returns once its message is
 * written, or throws {@link IOException}. A message sent in parts, by the partial sends, the stream or
 * the writer, is buffered and sent whole on its last part or at {@code close()}, so no other message
 * ever lands between its parts.
 */
final class BasicRemote extends FoySession.Remote implements RemoteEndpoint.Basic {

    private final StringBuilder partialText = new StringBuilder();
    private final ByteArrayOutputStream partialBinary = new ByteArrayOutputStream();

    BasicRemote(FoySession session) {
        super(session);
    }

    @Override
    public void sendText(String text) throws IOException {
        session.write(FoySession.Frame.text(text));
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        session.write(FoySession.Frame.binary(data));
    }

    @Override
    public void sendText(String partialMessage, boolean isLast) throws IOException {
        FoySession.required(partialMessage, "text");
        String whole;
        synchronized (partialText) {
            partialText.append(partialMessage);
            if (!isLast) {
                return;
            }
            whole = partialText.toString();
            partialText.setLength(0);
        }
        sendText(whole);
    }

    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException {
        FoySession.required(partialByte, "data");
        byte[] whole;
        synchronized (partialBinary) {
            partialBinary.writeBytes(FoySession.bytesOf(partialByte));
            if (!isLast) {
                return;
            }
            whole = partialBinary.toByteArray();
            partialBinary.reset();
        }
        sendBinary(ByteBuffer.wrap(whole));
    }

    /** A stream whose bytes are sent as one binary message when it is closed. */
    @Override
    public OutputStream getSendStream() {
        return new ByteArrayOutputStream() {
            private boolean sent;

            @Override
            public void close() throws IOException {
                if (!sent) {
                    sent = true;
                    sendBinary(ByteBuffer.wrap(toByteArray()));
                }
            }
        };
    }

    /** A writer whose characters are sent as one text message when it is closed. */
    @Override
    public Writer getSendWriter() {
        return new StringWriter() {
            private boolean sent;

            @Override
            public void close() throws IOException {
                if (!sent) {
                    sent = true;
                    sendText(toString());
                }
            }
        };
    }

    /**
     * Sends a {@code String} or a boxed primitive as text, a {@code ByteBuffer} or a {@code byte[]} as binary.
     *
     * @throws EncodeException for another type: encoders come with lot 2
     */
    @Override
    public void sendObject(Object data) throws IOException, EncodeException {
        session.write(FoySession.Frame.of(data));
    }
}
