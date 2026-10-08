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

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;

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

    // ---- targets outside foy-core's module ----

    @Test
    void targetInAnotherModuleIsSupported() throws Exception {
        // Parent = bootstrap only: the platform loader would delegate packages of boot-layer
        // modules (foy-core, jakarta.servlet) back to the application loader.
        try (var api = new URLClassLoader(new URL[]{root(HttpServlet.class)}, null);
             var app = new URLClassLoader(new URL[]{root(HiddenFactoryEmitterTest.class)}, api)) {
            Class<?> other = app.loadClass(Target.class.getName());
            assertNotSame(Target.class, other);
            assertNotSame(HiddenFactoryEmitter.class.getModule(), other.getModule());
            assertSame(other, HiddenFactoryEmitter.factoryFor(other).get().getClass());
        }
    }

    /** The class path root (directory or jar) a class was loaded from. */
    private static URL root(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        String url = type.getResource("/" + resource).toString();
        String base = url.substring(0, url.length() - resource.length());
        if (base.startsWith("jar:")) {
            base = base.substring("jar:".length(), base.length() - "!/".length());
        }
        return URI.create(base).toURL();
    }
}
