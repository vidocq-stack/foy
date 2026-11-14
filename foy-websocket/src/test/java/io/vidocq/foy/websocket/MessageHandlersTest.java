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

import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageHandlersTest {

    private final MessageHandlers handlers = new MessageHandlers();
    private final List<Object> received = new CopyOnWriteArrayList<>();

    @Test
    void aSecondTextHandlerThrows() {
        handlers.add(String.class, (MessageHandler.Whole<String>) received::add);
        MessageHandler.Partial<String> partial = (text, last) -> received.add(text);
        var reader = new MessageHandler.Whole<Reader>() {
            @Override
            public void onMessage(Reader message) {
                received.add(message);
            }
        };

        assertThrows(IllegalStateException.class, () -> handlers.add(String.class, partial));
        assertThrows(IllegalStateException.class, () -> handlers.add(reader));
        handlers.add(ByteBuffer.class, (MessageHandler.Whole<ByteBuffer>) received::add);
        handlers.add(PongMessage.class, (MessageHandler.Whole<PongMessage>) received::add);
        assertEquals(3, handlers.all().size(), "one text, one binary and one pong handler");
    }

    @Test
    void aPartialHandlerGetsTheWholeMessageAsLast() {
        handlers.add(String.class, (MessageHandler.Partial<String>) (text, last) -> received.add(text + "|" + last));
        handlers.add(byte[].class, (MessageHandler.Partial<byte[]>) (bytes, last) ->
                received.add(new String(bytes, StandardCharsets.UTF_8) + "|" + last));

        handlers.text("hello");
        handlers.binary(ByteBuffer.wrap("world".getBytes(StandardCharsets.UTF_8)));

        assertEquals(List.of("hello|true", "world|true"), received);
    }

    @Test
    void eachTypeGetsTheMessageInItsForm() {
        handlers.add(new MessageHandler.Whole<Reader>() {
            @Override
            public void onMessage(Reader message) {
                received.add(read(message));
            }
        });
        handlers.add(new MessageHandler.Whole<InputStream>() {
            @Override
            public void onMessage(InputStream message) {
                received.add(read(message));
            }
        });
        var pongs = new CopyOnWriteArrayList<ByteBuffer>();
        handlers.add(PongMessage.class, (MessageHandler.Whole<PongMessage>) pong ->
                pongs.add(pong.getApplicationData()));

        handlers.text("from a reader");
        handlers.binary(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        handlers.pong(ByteBuffer.wrap(new byte[] {9}));

        assertEquals("from a reader", received.get(0));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) received.get(1));
        assertEquals(ByteBuffer.wrap(new byte[] {9}), pongs.getFirst());
    }

    @Test
    void aTypeThatNeedsADecoderThrows() {
        MessageHandler.Whole<Integer> numbers = received::add;

        assertThrows(IllegalStateException.class, () -> handlers.add(Integer.class, numbers));
    }

    @Test
    void aPartialHandlerTakesOnlyStringsAndBytes() {
        MessageHandler.Partial<Reader> reader = (message, last) -> received.add(message);

        assertThrows(IllegalStateException.class, () -> handlers.add(Reader.class, reader));
        assertEquals(Set.of(), handlers.all());
    }

    @Test
    void aRemovedHandlerFreesItsType() {
        MessageHandler.Whole<String> first = received::add;
        handlers.add(String.class, first);

        handlers.remove(first);
        handlers.add(String.class, (MessageHandler.Whole<String>) text -> received.add("second " + text));
        handlers.text("message");

        assertEquals(List.of("second message"), received);
        assertEquals(1, handlers.all().size());
        assertEquals(Set.of(), new MessageHandlers().all());
    }

    private static String read(Reader reader) {
        try (reader) {
            var out = new StringWriter();
            reader.transferTo(out);
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
