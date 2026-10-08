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

import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HiddenFactoryEmitterTest {

    public static class Target extends HttpServlet {}
    static class PackagePrivateCtor extends HttpServlet { PackagePrivateCtor() {} }

    @Test
    void factoryCreatesFreshInstances() throws Exception {
        var f = HiddenFactoryEmitter.factoryFor(Target.class);
        Object a = f.get(), b = f.get();
        assertInstanceOf(Target.class, a);
        assertNotSame(a, b);
    }

    @Test
    void packagePrivateConstructorIsReachable() throws Exception {
        assertInstanceOf(PackagePrivateCtor.class, HiddenFactoryEmitter.factoryFor(PackagePrivateCtor.class).get());
    }

    // ---- beyond the brief: classes without an accessible no-arg constructor ----

    static class PrivateCtor extends HttpServlet { private PrivateCtor() {} }
    static class ArgsOnly extends HttpServlet { ArgsOnly(String s) {} }
    abstract static class Abstract extends HttpServlet {}
    class Inner extends HttpServlet {}

    @Test
    void classesWithoutAccessibleNoArgConstructorAreRejected() {
        assertThrows(IllegalAccessException.class, () -> HiddenFactoryEmitter.factoryFor(PrivateCtor.class));
        assertThrows(IllegalAccessException.class, () -> HiddenFactoryEmitter.factoryFor(ArgsOnly.class));
        assertThrows(IllegalAccessException.class, () -> HiddenFactoryEmitter.factoryFor(Abstract.class));
        assertThrows(IllegalAccessException.class, () -> HiddenFactoryEmitter.factoryFor(Inner.class));
    }
}
