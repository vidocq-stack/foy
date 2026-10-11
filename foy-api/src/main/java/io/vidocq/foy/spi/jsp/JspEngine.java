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

/** Optional, container-neutral binding for precompiled or translated page engines. */
@FunctionalInterface
public interface JspEngine {
    /** Opens one application before initializers/listeners. The layer defines page visibility. */
    Application deploy(ServletContext context, ModuleLayer applicationLayer) throws ServletException;

    /** Per-application state; Foy owns servlet init/destroy and closes this after undeployment. */
    interface Application extends AutoCloseable {
        /** Container default servlet for the JSP extension mappings. */
        Servlet servlet();
        /** Fresh, uninitialized servlet backing a named JSP registration. */
        Servlet servletFor(String jspFile);
        /** Releases application state after requests and servlet/listener destruction have drained. */
        @Override void close();
    }
}
