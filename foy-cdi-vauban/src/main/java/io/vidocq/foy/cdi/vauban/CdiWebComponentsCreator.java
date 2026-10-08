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
package io.vidocq.foy.cdi.vauban;

import io.vidocq.foy.spi.cdi.CdiWebComponents;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates the {@link CdiWebComponents} synthetic bean registered by {@link FoyWebExtension}.
 *
 * <p>The component classes arrive as one comma-joined {@code String} of binary names (vauban's
 * build-time synthetic metadata does not carry {@code Class<?>[]} parameters). Each name is
 * loaded, without initialisation, with the thread context class loader first: the deployer sets
 * it to the application loader while it boots the container and Foy, and foy-cdi-vauban may sit in
 * a parent loader or layer that cannot see the application classes. When there is no context
 * loader, or it cannot see the class (a flat class path where it is some unrelated loader), the
 * loader of this class is tried. Order is kept; the list is immutable.</p>
 */
public final class CdiWebComponentsCreator implements SyntheticBeanCreator<CdiWebComponents> {

    /** Public no-arg constructor, required by the container. */
    public CdiWebComponentsCreator() {}

    @Override
    public CdiWebComponents create(Instance<Object> lookup, Parameters params) {
        String names = params.get(FoyWebExtension.CLASSES_PARAM, String.class, "");
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names.split(",")) {
            String className = name.strip();
            if (!className.isEmpty()) classes.add(load(className));
        }
        List<Class<?>> componentClasses = List.copyOf(classes);
        return () -> componentClasses;
    }

    private static Class<?> load(String className) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader own = CdiWebComponentsCreator.class.getClassLoader();
        ClassNotFoundException failure = null;
        for (ClassLoader loader : new ClassLoader[] {context, own}) {
            if (loader == null) continue;
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        throw new IllegalStateException("foy: web component class " + className
                + " indexed at build time is not visible from the context or foy-cdi-vauban class loader", failure);
    }
}
