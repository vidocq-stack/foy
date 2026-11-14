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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The message handlers of one session: at most one per native message type, text, binary and pong
 * (WEBSOCKET.md section 5.4). A handler receives the message in the form its type asks for; a
 * partial handler receives each whole message as one part, with {@code last = true}. A binary
 * message is copied before it reaches a handler, since the transport's buffer is only valid for its
 * callback. Types that need a decoder come with lot 2.
 */
final class MessageHandlers {

    private static final String STRING = "Ljava/lang/String;";
    private static final String READER = "Ljava/io/Reader;";
    private static final String BYTE_BUFFER = "Ljava/nio/ByteBuffer;";
    private static final String BYTES = "[B";
    private static final String INPUT_STREAM = "Ljava/io/InputStream;";
    private static final String PONG_MESSAGE = "Ljakarta/websocket/PongMessage;";

    /** The native message types, as the refusal of a second handler names them. */
    private static final String TEXT_KIND = "text";
    private static final String BINARY_KIND = "binary";

    /** A registered handler and how a message reaches it. */
    private record Slot<M>(MessageHandler handler, Consumer<M> delivery) {
    }

    private final AtomicReference<Slot<String>> textSlot = new AtomicReference<>();
    private final AtomicReference<Slot<ByteBuffer>> binarySlot = new AtomicReference<>();
    private final AtomicReference<Slot<ByteBuffer>> pongSlot = new AtomicReference<>();

    /**
     * Registers a whole-message handler of {@code type}.
     *
     * @throws IllegalStateException when a handler of the same native type is registered, or the
     *                               type needs a decoder
     */
    <T> void add(Class<T> type, MessageHandler.Whole<T> handler) {
        register(type.descriptorString(), handler, false);
    }

    /**
     * Registers a partial-message handler of {@code type}: {@code String}, {@code ByteBuffer} or {@code byte[]}.
     *
     * @throws IllegalStateException when a handler of the same native type is registered, or the
     *                               type is another one
     */
    <T> void add(Class<T> type, MessageHandler.Partial<T> handler) {
        register(type.descriptorString(), handler, true);
    }

    /**
     * Registers a handler whose type its class file tells ({@link HandlerTypes}).
     *
     * @throws IllegalStateException when the type cannot be told (a lambda), a handler of the same
     *                               native type is registered, or the type needs a decoder
     */
    void add(MessageHandler handler) {
        register(HandlerTypes.messageType(handler.getClass()), handler, handler instanceof MessageHandler.Partial<?>);
    }

    /** Unregisters {@code handler}; no effect when it is not registered. */
    void remove(MessageHandler handler) {
        textSlot.getAndUpdate(slot -> holds(slot, handler) ? null : slot);
        binarySlot.getAndUpdate(slot -> holds(slot, handler) ? null : slot);
        pongSlot.getAndUpdate(slot -> holds(slot, handler) ? null : slot);
    }

    /** The registered handlers. */
    Set<MessageHandler> all() {
        return Stream.of(textSlot.get(), binarySlot.get(), pongSlot.get())
                .filter(Objects::nonNull)
                .map(Slot::handler)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Delivers a text message to the text handler, if any. */
    void text(String message) {
        deliver(textSlot, message);
    }

    /** Delivers a binary message to the binary handler, if any; {@code data} is valid for this call only. */
    void binary(ByteBuffer data) {
        deliver(binarySlot, data);
    }

    /** Delivers a pong to the pong handler, if any; {@code data} is valid for this call only. */
    void pong(ByteBuffer data) {
        deliver(pongSlot, data);
    }

    private static <M> void deliver(AtomicReference<Slot<M>> slot, M message) {
        Slot<M> registered = slot.get();
        if (registered != null) {
            registered.delivery().accept(message);
        }
    }

    private void register(String type, MessageHandler handler, boolean partial) {
        switch (type) {
            case STRING -> claim(textSlot, TEXT_KIND, handler, delivery(handler, partial, (String message) -> message));
            case BYTE_BUFFER -> claim(binarySlot, BINARY_KIND, handler, delivery(handler, partial,
                    (ByteBuffer data) -> ByteBuffer.wrap(FoySession.bytesOf(data))));
            case BYTES -> claim(binarySlot, BINARY_KIND, handler, delivery(handler, partial, FoySession::bytesOf));
            case READER -> {
                requireWhole(partial, type);
                claim(textSlot, TEXT_KIND, handler, delivery(handler, false, StringReader::new));
            }
            case INPUT_STREAM -> {
                requireWhole(partial, type);
                claim(binarySlot, BINARY_KIND, handler, delivery(handler, false,
                        (ByteBuffer data) -> new ByteArrayInputStream(FoySession.bytesOf(data))));
            }
            case PONG_MESSAGE -> {
                requireWhole(partial, type);
                claim(pongSlot, "pong", handler, delivery(handler, false, MessageHandlers::pongOf));
            }
            default -> throw new IllegalStateException("A message handler of " + type + " needs a decoder: "
                    + "decoders are not supported yet (Foy WebSocket lot 2)");
        }
    }

    /**
     * How a message reaches {@code handler}, in the form {@code form} gives it. The descriptor of
     * the handler's type chose {@code form}, so the handler accepts what it returns.
     */
    @SuppressWarnings("unchecked")
    private static <M> Consumer<M> delivery(MessageHandler handler, boolean partial, Function<M, ?> form) {
        if (partial) {
            var target = (MessageHandler.Partial<Object>) handler;
            return message -> target.onMessage(form.apply(message), true);
        }
        if (!(handler instanceof MessageHandler.Whole<?>)) {
            throw new IllegalStateException(handler.getClass().getName()
                    + " is neither a MessageHandler.Whole nor a MessageHandler.Partial");
        }
        var target = (MessageHandler.Whole<Object>) handler;
        return message -> target.onMessage(form.apply(message));
    }

    private static void requireWhole(boolean partial, String type) {
        if (partial) {
            throw new IllegalStateException("A partial message handler takes String, ByteBuffer or byte[], not "
                    + type);
        }
    }

    private static PongMessage pongOf(ByteBuffer data) {
        ByteBuffer copy = ByteBuffer.wrap(FoySession.bytesOf(data));
        return () -> copy;
    }

    private static <M> void claim(AtomicReference<Slot<M>> slot, String kind, MessageHandler handler,
                                  Consumer<M> delivery) {
        if (!slot.compareAndSet(null, new Slot<>(handler, delivery))) {
            throw new IllegalStateException("A " + kind + " message handler is already registered");
        }
    }

    private static boolean holds(Slot<?> slot, MessageHandler handler) {
        return slot != null && slot.handler() == handler;
    }
}
