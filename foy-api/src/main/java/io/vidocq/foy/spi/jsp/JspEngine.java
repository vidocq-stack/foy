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
package io.vidocq.foy.spi.jsp;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.util.EventListener;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Optional, container-neutral binding for precompiled or translated page engines. */
@FunctionalInterface
public interface JspEngine {
    /** Constructor-based TLD listener; the type is the exact factory class, deduplicated before construction. */
    record Listener(Class<? extends EventListener> type, Supplier<? extends EventListener> factory) {
        public Listener { Objects.requireNonNull(type, "type"); Objects.requireNonNull(factory, "factory"); }
        public EventListener createListener() {
            var listener = Objects.requireNonNull(factory.get(), "Listener factory returned null");
            if (listener.getClass() != type) throw new IllegalArgumentException("Listener factory must return its exact declared class");
            return listener;
        }
    }
    /** Opens one application before initializers/listeners. The layer defines page visibility. */
    Application deploy(ServletContext context, ModuleLayer applicationLayer) throws ServletException;

    /** Per-application state; Foy owns servlet init/destroy and closes this after undeployment. */
    interface Application extends AutoCloseable {
        /** Container default servlet for the JSP extension mappings. */
        Servlet servlet();
        /** Fresh, uninitialized servlet backing a named JSP registration. */
        Servlet servletFor(String jspFile);
        /** TLD listeners, in discovery order; factories are invoked only by the container. */
        default List<Listener> listeners() { return List.of(); }
        /** Releases application state after requests and servlet/listener destruction have drained. */
        @Override void close();
    }
}
