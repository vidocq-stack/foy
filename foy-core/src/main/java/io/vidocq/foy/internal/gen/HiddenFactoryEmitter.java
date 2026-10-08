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
package io.vidocq.foy.internal.gen;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

/**
 * Emits a hidden {@link Supplier} class calling the no-arg constructor ({@code java.lang.classfile}).
 *
 * <p>The hidden class is defined in the component's own package through a private lookup, so
 * package-private constructors are reachable without reflection. The emitted class file uses
 * the running JDK's class file version ({@code ClassFile.of()} default).
 */
public final class HiddenFactoryEmitter {

    private HiddenFactoryEmitter() {
    }

    /**
     * Emits and defines a factory creating a fresh instance of {@code type} on every call.
     *
     * @param type the class to instantiate
     * @return a supplier invoking the no-arg constructor of {@code type}
     * @throws IllegalAccessException when the type's package is not open to foy-core, or when
     *         the class bytes show the type has no non-private no-arg constructor or is abstract
     */
    public static Supplier<Object> factoryFor(Class<?> type) throws IllegalAccessException {
        checkInstantiable(type);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        ClassDesc target = ClassDesc.of(type.getName());
        ClassDesc self = ClassDesc.of(type.getPackageName().isEmpty()
                ? "FoyFactory" : type.getPackageName() + ".FoyFactory");
        byte[] bytes = ClassFile.of().build(self, cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC)
                .withInterfaceSymbols(ClassDesc.of("java.util.function.Supplier"))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, c -> c
                        .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, c -> c
                        .new_(target).dup()
                        .invokespecial(target, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .areturn()));
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(bytes, true);
        try {
            @SuppressWarnings("unchecked")
            Supplier<Object> s = (Supplier<Object>) hidden.findConstructor(hidden.lookupClass(),
                    MethodType.methodType(void.class)).invoke();
            return s;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot instantiate hidden factory for " + type.getName(), t);
        }
    }

    /**
     * Rejects, from the class bytes, types the emitted {@code get()} could not instantiate.
     * When the bytes are not readable the check is skipped and the factory is emitted anyway.
     */
    private static void checkInstantiable(Class<?> type) throws IllegalAccessException {
        byte[] bytes = ClassFileDescriptorReader.bytesOf(type);
        if (bytes == null) {
            return;
        }
        boolean abstractType;
        boolean hasNoArg;
        try {
            ClassModel model = ClassFile.of().parse(bytes);
            abstractType = (model.flags().flagsMask() & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_INTERFACE)) != 0;
            hasNoArg = model.methods().stream().anyMatch(m -> m.methodName().equalsString(ConstantDescs.INIT_NAME)
                    && m.methodType().equalsString("()V")
                    && (m.flags().flagsMask() & ClassFile.ACC_PRIVATE) == 0);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (abstractType) {
            throw new IllegalAccessException(type.getName() + " is abstract or an interface and cannot be instantiated");
        }
        if (!hasNoArg) {
            throw new IllegalAccessException(type.getName() + " has no non-private no-arg constructor");
        }
    }
}
