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
package io.vidocq.foy.internal.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The private, versioned byte format of {@link SessionManager#closeExporting()}, read back by
 * {@link SessionManager#importSessions}. The outer frame, written with {@link DataOutputStream}, holds JDK
 * primitives and strings only. Each attribute and note value is serialized on its own, to its own bytes, so that a
 * value that fails to write or to read never corrupts the frame. The bytes never leave the process, and the format
 * is private to one Foy version:
 *
 * <pre>
 * "FOYS" (4 ASCII bytes), version (int, 1), session count (int)
 * per session: id (string), creation time (long), last-accessed time (long), idle since (long),
 *              maximum inactive interval (int),
 *              attribute count (int), then per attribute: name (string), length (int), bytes;
 *              note count (int), then per note: name (string), length (int), bytes
 * string: length (int), then its UTF-8 bytes
 * </pre>
 *
 * <p>The last-accessed time is what {@code getLastAccessedTime()} returns: the end of the last request that
 * completed. "Idle since" is the reference of the expiry: that time, or the time of the export for a session that a
 * request still used then, as a session in use does not expire.</p>
 */
final class SessionExport {

    private static final byte[] MAGIC = "FOYS".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    /**
     * The primitive types by name, as {@code ObjectStreamClass} names them ({@code int}, {@code void}):
     * {@link Class#forName(String, boolean, ClassLoader)} does not find them.
     */
    private static final Map<String, Class<?>> PRIMITIVES = Map.of("boolean", boolean.class, "byte", byte.class,
            "char", char.class, "short", short.class, "int", int.class, "long", long.class, "float", float.class,
            "double", double.class, "void", void.class);
    /** An export of no session; each caller gets a copy. */
    private static final byte[] EMPTY = write(List.of());

    private SessionExport() {
    }

    /** One session as written: its state, and each of its values serialized on its own. */
    record ExportedSession(String id, long creationTime, long lastAccessedTime, long idleSince, int maxInactiveInterval,
                           Map<String, byte[]> attributes, Map<String, byte[]> notes) {

        /** {@code true} when the session is idle beyond its interval at {@code now}. */
        boolean idleExpiredAt(long now) {
            return HttpSessionImpl.idleBeyond(maxInactiveInterval, idleSince, now);
        }
    }

    // ---- the frame ----

    /** An export of no session: what the export of a manager already closed returns. */
    static byte[] empty() {
        return EMPTY.clone();
    }

    static byte[] write(List<ExportedSession> sessions) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(sessions.size());
            for (ExportedSession session : sessions) {
                writeString(out, session.id());
                out.writeLong(session.creationTime());
                out.writeLong(session.lastAccessedTime());
                out.writeLong(session.idleSince());
                out.writeInt(session.maxInactiveInterval());
                writeValues(out, session.attributes());
                writeValues(out, session.notes());
            }
        } catch (IOException e) {
            // A stream over a byte array does not fail.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Reads the frame of an export, without reading any value.
     *
     * @throws IllegalArgumentException for an unknown magic or version, or a truncated or corrupt export
     */
    static List<ExportedSession> read(byte[] export) {
        try (var in = new DataInputStream(new ByteArrayInputStream(export))) {
            if (!Arrays.equals(MAGIC, in.readNBytes(MAGIC.length))) {
                throw new IllegalArgumentException("not an export of Foy sessions");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException(
                        "unknown version " + version + " of a Foy session export, this Foy reads version " + VERSION);
            }
            int count = readLength(in);
            List<ExportedSession> sessions = new ArrayList<>();
            for (int i = 0; i < count; i++) sessions.add(readSession(in));
            if (in.read() != -1) throw new IllegalArgumentException("corrupt Foy session export: trailing bytes");
            return sessions;
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated Foy session export", e);
        }
    }

    private static ExportedSession readSession(DataInputStream in) throws IOException {
        String id = readString(in);
        long creationTime = in.readLong();
        long lastAccessedTime = in.readLong();
        long idleSince = in.readLong();
        int maxInactiveInterval = in.readInt();
        Map<String, byte[]> attributes = readValues(in);
        Map<String, byte[]> notes = readValues(in);
        return new ExportedSession(id, creationTime, lastAccessedTime, idleSince, maxInactiveInterval, attributes,
                notes);
    }

    private static void writeValues(DataOutputStream out, Map<String, byte[]> values) throws IOException {
        out.writeInt(values.size());
        for (Map.Entry<String, byte[]> value : values.entrySet()) {
            writeString(out, value.getKey());
            writeBytes(out, value.getValue());
        }
    }

    private static Map<String, byte[]> readValues(DataInputStream in) throws IOException {
        int count = readLength(in);
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) values.put(readString(in), readBytes(in));
        return values;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        return in.readNBytes(readLength(in));
    }

    /** A count or a length: never negative, never beyond what is left to read. */
    private static int readLength(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new IllegalArgumentException("corrupt Foy session export: a length of " + length);
        }
        return length;
    }

    // ---- the values ----

    /**
     * Serializes one value on its own, with {@link ObjectOutputStream}.
     *
     * @throws ValueTransferException when the value cannot be written: an {@link IOException} (a value that is not
     *         serializable), a {@link RuntimeException} from its {@code writeObject}, a {@link StackOverflowError} on a
     *         graph too deep, or a {@link LinkageError} from a class it needs
     */
    static byte[] serialize(Object value) throws ValueTransferException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (IOException | RuntimeException | StackOverflowError | LinkageError e) {
            throw new ValueTransferException(value.getClass().getName(), e);
        }
        return bytes.toByteArray();
    }

    /**
     * Reads one value, resolving every class it names through {@code loader}.
     *
     * @throws ValueTransferException when the value cannot be read: a class missing or changed, an
     *         {@link IOException}, a {@link RuntimeException} from its {@code readObject}, a
     *         {@link StackOverflowError}, a {@link LinkageError} such as an {@link ExceptionInInitializerError}, or a
     *         value that reads back as {@code null} (a {@code readResolve} that returns it)
     */
    static Object deserialize(byte[] bytes, ClassLoader loader) throws ValueTransferException {
        try (var in = new LoaderInputStream(new ByteArrayInputStream(bytes), loader)) {
            return in.readValue();
        } catch (IOException e) {
            // Only the stream header can fail here: readValue reports every other failure itself.
            throw new ValueTransferException(null, e);
        }
    }

    /**
     * Resolves a class name of a serialization stream through {@code loader}, without initializing the class: a
     * primitive type by its name, any other class by {@link Class#forName(String, boolean, ClassLoader)}, which
     * reads an array name ({@code [I}, {@code [Lcom.acme.Cart;}) itself. A documented reflective fallback of Foy:
     * the stream carries the class names, and only the new application layer's loader knows those classes.
     */
    static Class<?> resolve(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> primitive = PRIMITIVES.get(name);
        return primitive != null ? primitive : Class.forName(name, false, loader);
    }

    /** An {@link ObjectInputStream} that resolves the classes of a value through the importing application's loader. */
    private static final class LoaderInputStream extends ObjectInputStream {

        private final ClassLoader loader;
        /** The first class the stream resolved: the class of the value read. */
        private String firstClass;

        LoaderInputStream(InputStream in, ClassLoader loader) throws IOException {
            super(in);
            this.loader = loader;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws ClassNotFoundException {
            String name = desc.getName();
            if (firstClass == null) firstClass = name;
            return resolve(name, loader);
        }

        Object readValue() throws ValueTransferException {
            Object value;
            try {
                value = readObject();
            } catch (ClassNotFoundException | IOException | RuntimeException | StackOverflowError | LinkageError e) {
                throw new ValueTransferException(failingClass(e), e);
            }
            // A session never holds null: the stores reject it, so a value read as null cannot be restored.
            if (value == null) throw new ValueTransferException(firstClass, null);
            return value;
        }

        /** The class missing or changed, else the class of the value whose reading failed. */
        private String failingClass(Throwable e) {
            if (e instanceof ClassNotFoundException) return e.getMessage();
            if (e instanceof InvalidClassException invalid && invalid.classname != null) return invalid.classname;
            return firstClass;
        }
    }

    /**
     * A value that could not be written or read, with the name of the class that failed when it is known. The cause
     * is what the stream threw, {@code null} for a value read back as {@code null}.
     */
    static final class ValueTransferException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String className;

        ValueTransferException(String className, Throwable cause) {
            super(cause);
            this.className = className;
        }

        /** The class that failed, {@code null} when the stream failed before naming one. */
        String className() {
            return className;
        }
    }
}
