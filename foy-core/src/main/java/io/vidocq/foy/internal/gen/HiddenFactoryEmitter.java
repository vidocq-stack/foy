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
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

/**
 * Emits a hidden {@link Supplier} class calling the no-arg constructor ({@code java.lang.classfile}).
 *
 * <p>The hidden class is defined in foy-core's own package, through foy-core's full-privilege
 * lookup, so it works whatever the component's module (another named module, or the unnamed
 * module of a web application class loader). Its class data is the component's no-arg
 * constructor handle, obtained through a private lookup in the component class: package-private
 * constructors are reachable, and a package that is not open to foy-core is reported as an
 * {@link IllegalAccessException}. The emitted {@code get()} loads the handle with a dynamic
 * constant bootstrapped by {@link MethodHandles#classData} and calls it with
 * {@code invokeExact}. The class file uses the running JDK's version ({@code ClassFile.of()} default).
 */
public final class HiddenFactoryEmitter {

    private static final ClassDesc SELF = ClassDesc.of(HiddenFactoryEmitter.class.getPackageName() + ".FoyFactory");
    private static final DynamicConstantDesc<MethodHandle> CONSTRUCTOR = DynamicConstantDesc.ofNamed(
            ConstantDescs.BSM_CLASS_DATA, ConstantDescs.DEFAULT_NAME, ConstantDescs.CD_MethodHandle);
    private static final byte[] FACTORY_BYTES = ClassFile.of().build(SELF, cb -> cb
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC)
            .withInterfaceSymbols(ClassDesc.of("java.util.function.Supplier"))
            .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, c -> c
                    .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                    .return_())
            .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, c -> c
                    .ldc(CONSTRUCTOR)
                    .invokevirtual(ConstantDescs.CD_MethodHandle, "invokeExact",
                            MethodTypeDesc.of(ConstantDescs.CD_Object))
                    .areturn()));

    private HiddenFactoryEmitter() {
    }

    /**
     * Emits and defines a factory creating a fresh instance of {@code type} on every call.
     *
     * @param type the class to instantiate
     * @return a supplier invoking the no-arg constructor of {@code type}
     * @throws IllegalAccessException when the type's package is not open to foy-core, or when
     *         the type has no non-private no-arg constructor or is abstract
     */
    public static Supplier<Object> factoryFor(Class<?> type) throws IllegalAccessException {
        checkInstantiable(type);
        HiddenFactoryEmitter.class.getModule().addReads(type.getModule());
        MethodHandle constructor;
        try {
            constructor = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findConstructor(type, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
        } catch (NoSuchMethodException e) {
            var failure = new IllegalAccessException(type.getName() + " has no no-arg constructor");
            failure.initCause(e);
            throw failure;
        }
        MethodHandles.Lookup hidden = MethodHandles.lookup()
                .defineHiddenClassWithClassData(FACTORY_BYTES, constructor, true);
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
        } catch (RuntimeException e) {
            return; // unreadable bytes: let the constructor lookup decide
        }
        if (abstractType) {
            throw new IllegalAccessException(type.getName() + " is abstract or an interface and cannot be instantiated");
        }
        if (!hasNoArg) {
            throw new IllegalAccessException(type.getName() + " has no non-private no-arg constructor");
        }
    }
}
