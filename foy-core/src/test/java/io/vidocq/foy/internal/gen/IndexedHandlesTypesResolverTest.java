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

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.HandlesTypes;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IndexedHandlesTypesResolverTest {

    public static class Impl implements Runnable { @Override public void run() { /* not needed by this test */ } }
    /** Marks the classes the initializer receives through their type-level annotation. */
    public @interface Mark { }
    @Mark public static class Marked {}
    public static class Other {}
    /** Implements Runnable and is itself handled by {@link HandledSci}. */
    public static class Handled implements Runnable { @Override public void run() { /* not needed by this test */ } }

    @HandlesTypes({Runnable.class, Mark.class})
    public static class Sci implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> c, ServletContext ctx) { /* not needed by this test */ }
    }
    @HandlesTypes({Runnable.class, Handled.class})
    public static class HandledSci implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> c, ServletContext ctx) { /* not needed by this test */ }
    }
    @HandlesTypes({Comparable.class})
    public static class NoMatchSci implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> c, ServletContext ctx) { /* not needed by this test */ }
    }
    @HandlesTypes({})
    public static class EmptyHandles implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> c, ServletContext ctx) { /* not needed by this test */ }
    }
    public static class NoHandles implements ServletContainerInitializer {
        @Override public void onStartup(Set<Class<?>> c, ServletContext ctx) { /* not needed by this test */ }
    }

    private final ClassLoader cl = getClass().getClassLoader();
    private final IndexedHandlesTypesResolver resolver =
            new IndexedHandlesTypesResolver(WebComponentRegistry.forClassLoader(cl), cl);

    @Test
    void matchesSubtypesAndAnnotatedClasses() {
        // Gone is unloadable (skipped), Impl is duplicated (matched once), bad lines ignored
        assertEquals(Set.of(Impl.class, Marked.class, Handled.class), resolver.resolve(new Sci()));
    }

    @Test
    void handledTypesAreExcluded() {
        assertEquals(Set.of(Impl.class), resolver.resolve(new HandledSci()));
    }

    @Test
    void handlesTypesWithoutMatchYieldsNull() {
        // ServletContainerInitializer#onStartup: "or null if there are no matches".
        assertNull(resolver.resolve(new NoMatchSci()));
    }

    @Test
    void emptyHandlesTypesYieldsNull() {
        assertNull(resolver.resolve(new EmptyHandles()));
    }

    @Test
    void noHandlesTypesYieldsNull() {
        assertNull(resolver.resolve(new NoHandles()));
    }
}
