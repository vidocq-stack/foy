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
package io.vidocq.foy.internal.container;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** BUG-20261022-06: the context class loader scope installs, restores and nests. */
class ContextLoaderScopeTest {

    private static final ClassLoader TEST = ContextLoaderScopeTest.class.getClassLoader();
    private static final ClassLoader OUTER = new ClassLoader("foy-test-outer", TEST) {};
    private static final ClassLoader INNER = new ClassLoader("foy-test-inner", TEST) {};
    private static final ClassLoader THREAD = new ClassLoader("foy-test-thread", TEST) {};

    private ClassLoader saved;

    @BeforeEach
    void install() {
        saved = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(THREAD);
    }

    @AfterEach
    void restore() {
        Thread.currentThread().setContextClassLoader(saved);
    }

    private static ClassLoader current() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Test
    void aNullLoaderLeavesTheContextLoaderUntouched() {
        try (var _ = ContextLoaderScope.enter((ClassLoader) null)) {
            assertSame(THREAD, current());
            // A change made inside a no-op scope is the caller's own: the scope does not undo it.
            Thread.currentThread().setContextClassLoader(INNER);
        }
        assertSame(INNER, current());
    }

    @Test
    void theLoaderIsInstalledThenRestored() {
        try (var _ = ContextLoaderScope.enter(OUTER)) {
            assertSame(OUTER, current());
        }
        assertSame(THREAD, current());
    }

    @Test
    void theLoaderIsRestoredWhenTheBodyThrows() {
        assertThrows(IllegalStateException.class, () -> throwWithin(OUTER));
        assertSame(THREAD, current());
    }

    /** Enters a scope for {@code loader}, then throws from its body. */
    private static void throwWithin(ClassLoader loader) {
        try (var _ = ContextLoaderScope.enter(loader)) {
            throw new IllegalStateException("thrown inside the scope of " + current().getName());
        }
    }

    @Test
    void nestedScopesUnwindInReverseOrder() {
        try (var _ = ContextLoaderScope.enter(OUTER)) {
            try (var _ = ContextLoaderScope.enter(INNER)) {
                assertSame(INNER, current());
            }
            assertSame(OUTER, current());
        }
        assertSame(THREAD, current());
    }

    @Test
    void theApplicationLoaderOfAContextIsTheDeploymentsOrNone() {
        var context = new VidocqServletContext("/");
        assertNull(ContextLoaderScope.applicationLoaderOf(context), "no loader named: none, not the caller's TCCL");
        assertNull(ContextLoaderScope.applicationLoaderOf(null));
        context.setApplicationClassLoader(OUTER);
        assertSame(OUTER, ContextLoaderScope.applicationLoaderOf(context));
    }
}
