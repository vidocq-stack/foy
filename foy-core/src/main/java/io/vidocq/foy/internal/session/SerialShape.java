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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What decides whether a class whose {@code serialVersionUID} changed between two versions can still be read with
 * the new version's descriptor: the facts of one version of a class that its stream descriptor does not carry. The
 * export writes them after each class descriptor; the import reads them back and compares them with the new class
 * (BUG-20261117-01). Both sides read them the same way, from the class as it was loaded ({@link #of}).
 *
 * @param declaresSerialVersionUid the class itself declares a field {@code serialVersionUID} of type {@code long},
 *        whatever its modifiers
 * @param externalizable the class is {@link Externalizable}, through any of its supertypes
 * @param declaresWriteObject the class itself declares a method {@code writeObject(ObjectOutputStream)} returning
 *        {@code void}, whatever its modifiers
 * @param serializableSuperclass the name of its nearest serializable superclass, empty for none
 * @param interfaces the names of the interfaces the class itself implements, in the order of its declaration
 * @param known whether the class's own members were looked up: {@code false} for a class of the JDK, an array class
 *        or a primitive type, which are not looked at, and for a class whose members could not be listed (a
 *        {@link LinkageError}). The two "declares" facts of a shape not known are unknown, and its class is never
 *        read with the new descriptor
 */
record SerialShape(boolean declaresSerialVersionUid, boolean externalizable, boolean declaresWriteObject,
        String serializableSuperclass, List<String> interfaces, boolean known) {

    static final String DECLARED_UID_CHANGED = "its declared serialVersionUID changed";
    static final String IS_EXTERNALIZABLE = "its default serialVersionUID changed and it is Externalizable";
    static final String DECLARES_WRITE_OBJECT = "its default serialVersionUID changed and it declares writeObject";
    static final String FIELDS_CHANGED =
            "its default serialVersionUID changed and its serializable fields changed";
    static final String SUPERCLASS_CHANGED =
            "its default serialVersionUID changed and its serializable superclass changed";
    static final String INTERFACES_CHANGED = "its default serialVersionUID changed and its interfaces changed";
    static final String SHAPE_UNKNOWN = "its default serialVersionUID changed and its shape could not be read";

    private static final String SERIAL_VERSION_UID = "serialVersionUID";
    private static final String WRITE_OBJECT = "writeObject";

    /**
     * The shape of a class of the JDK, an array class or a primitive type. The import never compares it: such a
     * class is the same on both sides of a swap, and the import leaves arrays to the JDK.
     */
    private static final SerialShape NONE = new SerialShape(false, false, false, "", List.of(), false);

    SerialShape {
        interfaces = List.copyOf(interfaces);
    }

    /**
     * The shape of a class as it was loaded, on both sides: the class of the export, and the class of the importing
     * loader. The two "declares" facts come from the class itself, by the same {@code getDeclaredField} and
     * {@code getDeclaredMethod} lookups {@link java.io.ObjectStreamClass} already made on it for its descriptor: no
     * member is read or invoked. Never from the class file its loader serves, which need not be the bytes the loader
     * defined: a dev swap compiles the classes again in place before the old layer exports, and a layer loader such
     * as Vauban's reads a class file from that directory when asked. A class of the JDK, an array class and a
     * primitive type are not looked at.
     */
    static SerialShape of(Class<?> type) {
        if (type.isArray() || ofTheJdk(type)) return NONE;
        boolean externalizable = Externalizable.class.isAssignableFrom(type);
        String superclass = serializableSuperclass(type);
        List<String> interfaces = Arrays.stream(type.getInterfaces()).map(Class::getName).toList();
        try {
            return new SerialShape(hasSerialVersionUidField(type), externalizable, hasWriteObjectMethod(type),
                    superclass, interfaces, true);
        } catch (LinkageError _) {
            // A type of a member's signature that cannot be loaded: the shape stays unknown.
            return new SerialShape(false, externalizable, false, superclass, interfaces, false);
        }
    }

    /** A class of the bootstrap or the platform loader, a primitive type included. */
    private static boolean ofTheJdk(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader.equals(ClassLoader.getPlatformClassLoader());
    }

    private static boolean hasSerialVersionUidField(Class<?> type) {
        try {
            return type.getDeclaredField(SERIAL_VERSION_UID).getType() == long.class;
        } catch (NoSuchFieldException _) {
            return false;
        }
    }

    private static boolean hasWriteObjectMethod(Class<?> type) {
        try {
            return type.getDeclaredMethod(WRITE_OBJECT, ObjectOutputStream.class).getReturnType() == void.class;
        } catch (NoSuchMethodException _) {
            return false;
        }
    }

    /**
     * The direct superclass when it is serializable, as {@link java.io.ObjectStreamClass} takes it for the super
     * descriptor: a serializable class has no serializable ancestor above a superclass that is not.
     */
    private static String serializableSuperclass(Class<?> type) {
        Class<?> superclass = type.getSuperclass();
        return superclass != null && Serializable.class.isAssignableFrom(superclass) ? superclass.getName() : "";
    }

    /** Writes this shape after a class descriptor of the export. */
    void writeTo(DataOutput out) throws IOException {
        out.writeBoolean(declaresSerialVersionUid);
        out.writeBoolean(externalizable);
        out.writeBoolean(declaresWriteObject);
        out.writeUTF(serializableSuperclass);
        out.writeInt(interfaces.size());
        for (String name : interfaces) out.writeUTF(name);
        out.writeBoolean(known);
    }

    /** Reads a shape that {@link #writeTo} wrote. */
    static SerialShape readFrom(DataInput in) throws IOException {
        boolean declaresUid = in.readBoolean();
        boolean externalizable = in.readBoolean();
        boolean declaresWriteObject = in.readBoolean();
        String superclass = in.readUTF();
        int count = in.readInt();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < count; i++) interfaces.add(in.readUTF());
        return new SerialShape(declaresUid, externalizable, declaresWriteObject, superclass, interfaces,
                in.readBoolean());
    }

    /**
     * Why a class whose {@code serialVersionUID} changed cannot be read with the new class's descriptor, the first
     * reason in the order of the rule; empty when it can, its serialized form being the same.
     *
     * @param exported the shape of the exported class
     * @param local the shape of the class of the importing loader
     * @param exportedFields the serializable fields of the stream's descriptor
     * @param localFields the serializable fields of the local class's descriptor
     */
    static Optional<String> refusal(SerialShape exported, SerialShape local, ObjectStreamField[] exportedFields,
            ObjectStreamField[] localFields) {
        if (exported.declaresSerialVersionUid || local.declaresSerialVersionUid) {
            // The developer's word: a declared UID that changed, was added or was removed.
            return Optional.of(DECLARED_UID_CHANGED);
        }
        // Externalizable data is whatever writeExternal wrote, and writeObject data is opaque: neither can be checked.
        if (exported.externalizable || local.externalizable) return Optional.of(IS_EXTERNALIZABLE);
        if (exported.declaresWriteObject || local.declaresWriteObject) return Optional.of(DECLARES_WRITE_OBJECT);
        if (!sameFields(exportedFields, localFields)) return Optional.of(FIELDS_CHANGED);
        if (!exported.serializableSuperclass.equals(local.serializableSuperclass)) {
            return Optional.of(SUPERCLASS_CHANGED);
        }
        // Two anonymous classes whose numbers swapped have the same name and may have the same fields.
        if (!exported.interfaces.equals(local.interfaces)) return Optional.of(INTERFACES_CHANGED);
        if (!exported.known || !local.known) return Optional.of(SHAPE_UNKNOWN);
        return Optional.empty();
    }

    /** The same fields, in the same order: by name, type code and type string. */
    private static boolean sameFields(ObjectStreamField[] exported, ObjectStreamField[] local) {
        if (exported.length != local.length) return false;
        for (int i = 0; i < exported.length; i++) {
            if (!sameField(exported[i], local[i])) return false;
        }
        return true;
    }

    private static boolean sameField(ObjectStreamField exported, ObjectStreamField local) {
        return exported.getName().equals(local.getName()) && exported.getTypeCode() == local.getTypeCode()
                && Objects.equals(exported.getTypeString(), local.getTypeString());
    }

    /** The shapes of one export or one import, each class read once. */
    static final class Cache {

        private final Map<Class<?>, SerialShape> shapes = new ConcurrentHashMap<>();

        SerialShape of(Class<?> type) {
            return shapes.computeIfAbsent(type, SerialShape::of);
        }
    }
}
