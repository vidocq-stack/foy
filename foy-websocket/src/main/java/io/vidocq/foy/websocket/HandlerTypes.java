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

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.SignatureAttribute;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The message type of a handler given without its type, {@code Session.addMessageHandler(MessageHandler)}
 * (WEBSOCKET.md section 5.4). The type argument of {@code MessageHandler.Whole<T>} or
 * {@code MessageHandler.Partial<T>} is erased at run time; the compiler keeps it in the
 * {@code Signature} attribute of the handler's class file, which this class reads with the JDK
 * Class-File API instead of the reflective generic type API. It walks the superclasses, binding a
 * type variable of a generic superclass to the argument its subclass gives.
 */
final class HandlerTypes {

    private static final String WHOLE = "jakarta/websocket/MessageHandler$Whole";
    private static final String PARTIAL = "jakarta/websocket/MessageHandler$Partial";

    /** Each handler class is read once; a class whose type cannot be resolved is read at each call. */
    private static final ClassValue<String> TYPES = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return resolve(type);
        }
    };

    private HandlerTypes() {
    }

    /**
     * The descriptor of the message type of {@code handlerClass}: {@code Ljava/lang/String;},
     * {@code Ljava/nio/ByteBuffer;}, {@code [B}, {@code Ljava/io/Reader;}, {@code Ljava/io/InputStream;},
     * {@code Ljakarta/websocket/PongMessage;}, or the erasure of another type.
     *
     * @throws IllegalStateException when the type is unknown: a lambda (a hidden class has no
     *                               class file), a class file out of reach, or a type variable no
     *                               subclass binds
     */
    static String messageType(Class<?> handlerClass) {
        return TYPES.get(handlerClass);
    }

    private static String resolve(Class<?> handlerClass) {
        if (handlerClass.isHidden()) {
            throw unknown(handlerClass, "a lambda or another hidden class keeps no type argument");
        }
        // The type variables of the class being read, bound to what its subclass gave them.
        Map<String, String> bindings = Map.of();
        ClassSignature signature = signatureOf(handlerClass);
        for (Class<?> type = handlerClass; isWalked(type); type = type.getSuperclass()) {
            String descriptor = handlerType(handlerClass, signature, bindings);
            if (descriptor != null) {
                return descriptor;
            }
            Class<?> superclass = type.getSuperclass();
            ClassSignature superSignature = isWalked(superclass) ? signatureOf(superclass) : null;
            bindings = superclassBindings(signature, bindings, superSignature);
            signature = superSignature;
        }
        throw unknown(handlerClass, "neither it nor a superclass implements MessageHandler.Whole or "
                + "MessageHandler.Partial with a type argument");
    }

    private static boolean isWalked(Class<?> type) {
        return type != null && type != Object.class;
    }

    /**
     * The message type {@code signature} gives {@code MessageHandler.Whole} or {@code MessageHandler.Partial},
     * {@code null} when it implements neither (a raw class has no signature).
     */
    private static String handlerType(Class<?> handlerClass, ClassSignature signature, Map<String, String> bindings) {
        if (signature == null) {
            return null;
        }
        for (Signature.ClassTypeSig superinterface : signature.superinterfaceSignatures()) {
            String name = superinterface.className();
            if ((WHOLE.equals(name) || PARTIAL.equals(name)) && superinterface.typeArgs().size() == 1) {
                String descriptor = erasure(superinterface.typeArgs().getFirst(), bindings);
                if (descriptor == null) {
                    throw unknown(handlerClass, "its type argument is a type variable no subclass binds");
                }
                return descriptor;
            }
        }
        return null;
    }

    /**
     * The type variables of the superclass, whose signature is {@code superSignature}, bound to the
     * arguments {@code signature} gives them; none when either class is raw.
     */
    private static Map<String, String> superclassBindings(ClassSignature signature, Map<String, String> bindings,
                                                          ClassSignature superSignature) {
        if (signature == null || superSignature == null) {
            return Map.of();
        }
        List<Signature.TypeArg> arguments = signature.superclassSignature().typeArgs();
        List<Signature.TypeParam> parameters = superSignature.typeParameters();
        if (arguments.size() != parameters.size()) {
            return Map.of();
        }
        var bound = new HashMap<String, String>();
        for (int i = 0; i < arguments.size(); i++) {
            String descriptor = erasure(arguments.get(i), bindings);
            if (descriptor != null) {
                bound.put(parameters.get(i).identifier(), descriptor);
            }
        }
        return bound;
    }

    /** The descriptor of an exact type argument, {@code null} for a wildcard or an unbound variable. */
    private static String erasure(Signature.TypeArg argument, Map<String, String> bindings) {
        if (argument instanceof Signature.TypeArg.Bounded bounded
                && bounded.wildcardIndicator() == Signature.TypeArg.Bounded.WildcardIndicator.NONE) {
            return erasure(bounded.boundType(), bindings);
        }
        return null;
    }

    private static String erasure(Signature type, Map<String, String> bindings) {
        return switch (type) {
            case Signature.BaseTypeSig base -> String.valueOf(base.baseType());
            case Signature.ClassTypeSig named -> named.classDesc().descriptorString();
            case Signature.TypeVarSig variable -> bindings.get(variable.identifier());
            case Signature.ArrayTypeSig array -> {
                String component = erasure(array.componentSignature(), bindings);
                yield component == null ? null : "[" + component;
            }
        };
    }

    /** The generic signature of {@code type}'s class file, {@code null} when it has none. */
    private static ClassSignature signatureOf(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw unknown(type, "its class file cannot be read");
            }
            return ClassFile.of().parse(in.readAllBytes())
                    .findAttribute(Attributes.signature())
                    .map(SignatureAttribute::asClassSignature)
                    .orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot tell the message type of a handler: the class file of "
                    + type.getName() + " cannot be read", e);
        }
    }

    private static IllegalStateException unknown(Class<?> handlerClass, String why) {
        return new IllegalStateException("Cannot tell the message type of " + handlerClass.getName() + ": " + why
                + ". Register it with addMessageHandler(Class, handler)");
    }
}
