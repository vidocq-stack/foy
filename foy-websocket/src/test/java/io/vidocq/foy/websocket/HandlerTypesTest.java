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

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerTypesTest {

    /** A named handler. */
    static final class Texts implements MessageHandler.Whole<String> {
        @Override
        public void onMessage(String message) {
            // receives nothing in this test
        }
    }

    /** A generic base: its type argument comes from the subclass. */
    abstract static class Base<T> implements MessageHandler.Partial<T> {
        @Override
        public void onMessage(T partialMessage, boolean last) {
            // receives nothing in this test
        }
    }

    /** Binds {@link Base}'s type variable two levels up. */
    abstract static class Middle<U> extends Base<U> {
    }

    static final class Bytes extends Middle<byte[]> {
    }

    /** A non-generic base handler. */
    static class TextBase implements MessageHandler.Whole<String> {
        @Override
        public void onMessage(String message) {
            // receives nothing in this test
        }
    }

    static final class BinaryHandler implements MessageHandler.Whole<ByteBuffer> {
        @Override
        public void onMessage(ByteBuffer message) {
            // receives nothing in this test
        }
    }

    static final class ReaderHandler implements MessageHandler.Whole<Reader> {
        @Override
        public void onMessage(Reader message) {
            // receives nothing in this test
        }
    }

    static final class StreamHandler implements MessageHandler.Whole<InputStream> {
        @Override
        public void onMessage(InputStream message) {
            // receives nothing in this test
        }
    }

    static final class PongHandler implements MessageHandler.Whole<PongMessage> {
        @Override
        public void onMessage(PongMessage message) {
            // receives nothing in this test
        }
    }

    /** No signature of its own: its type comes from its superclass. */
    static final class InheritedText extends TextBase {
    }

    /** A raw subclass: the type variable of its superclass stays unbound. */
    @SuppressWarnings("rawtypes")
    static final class RawBytes extends Middle {
    }

    /** A handler whose type stays a type variable. */
    static final class Unbound<T> implements MessageHandler.Whole<T> {
        @Override
        public void onMessage(T message) {
            // receives nothing in this test
        }
    }

    @Test
    void theOneArgumentFormResolvesStringAndByteBuffer() {
        assertEquals("Ljava/lang/String;", HandlerTypes.messageType(Texts.class));
        assertEquals("Ljava/nio/ByteBuffer;", HandlerTypes.messageType(BinaryHandler.class));
        assertEquals("Ljava/io/Reader;", HandlerTypes.messageType(ReaderHandler.class));
        assertEquals("Ljava/io/InputStream;", HandlerTypes.messageType(StreamHandler.class));
        assertEquals("Ljakarta/websocket/PongMessage;", HandlerTypes.messageType(PongHandler.class));
        assertEquals("[B", HandlerTypes.messageType(Bytes.class), "a type argument bound by a subclass");
        assertEquals("Ljava/lang/String;", HandlerTypes.messageType(InheritedText.class), "a type inherited");
    }

    @Test
    void aLambdaHandlerWithoutTypeThrows() {
        MessageHandler.Whole<String> lambda = message -> { };
        Class<?> lambdaClass = lambda.getClass();

        var thrown = assertThrows(IllegalStateException.class, () -> HandlerTypes.messageType(lambdaClass));
        assertTrue(thrown.getMessage().contains("addMessageHandler(Class"), thrown.getMessage());
    }

    @Test
    void aTypeVariableNobodyBindsThrows() {
        assertThrows(IllegalStateException.class, () -> HandlerTypes.messageType(Unbound.class));
        assertThrows(IllegalStateException.class, () -> HandlerTypes.messageType(RawBytes.class));
        assertThrows(IllegalStateException.class, () -> HandlerTypes.messageType(String.class));
    }
}
