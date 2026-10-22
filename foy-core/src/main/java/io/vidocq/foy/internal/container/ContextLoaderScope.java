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

/**
 * The application's class loader as the current thread's context class loader, for as long as
 * Foy runs application code on that thread: a request, an async runnable or listener, an upgrade
 * handler, a session listener (BUG-20261022-06). A library that looks things up by name through
 * the context loader ({@code ResourceBundle.getBundle}, {@code ServiceLoader.load}) then sees the
 * application's modules. {@link #close()} gives the thread back the loader it had, on the thread
 * that entered the scope:
 *
 * <pre>{@code
 * try (var _ = ContextLoaderScope.enter(servletContext.applicationClassLoader())) {
 *     // application code
 * }
 * }</pre>
 *
 * <p>Without an application loader ({@code null}), the context loader is left untouched.</p>
 */
public final class ContextLoaderScope implements AutoCloseable {

    private static final ContextLoaderScope NONE = new ContextLoaderScope(null, null);

    private final Thread thread;
    private final ClassLoader previous;

    private ContextLoaderScope(Thread thread, ClassLoader previous) {
        this.thread = thread;
        this.previous = previous;
    }

    /** Installs {@code loader} on the current thread; a no-op scope when it is {@code null}. */
    public static ContextLoaderScope enter(ClassLoader loader) {
        if (loader == null) return NONE;
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(loader);
        return new ContextLoaderScope(current, previous);
    }

    /**
     * The application class loader the deployment of {@code context} named, {@code null} when it
     * named none or {@code context} is not one of Foy's. Unlike {@code ServletContext.getClassLoader()},
     * never the caller's context loader: a scope entered with the answer is a no-op without one.
     */
    public static ClassLoader applicationLoaderOf(jakarta.servlet.ServletContext context) {
        return context instanceof VidocqServletContext v ? v.applicationClassLoader() : null;
    }

    /** Gives the thread back the context loader it had when the scope was entered. */
    @Override
    public void close() {
        if (thread != null) thread.setContextClassLoader(previous);
    }
}
