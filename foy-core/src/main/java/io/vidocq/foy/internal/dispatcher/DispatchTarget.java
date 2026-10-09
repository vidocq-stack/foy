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
package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletMapping;

/**
 * Resolved target of a servlet dispatch: the servlet and contact details
 * URL it will see ({@code servletPath}, {@code pathInfo}, {@code queryString}).
 * {@code mapping} is the {@link HttpServletMapping} of the match, or {@code null} for a
 * named dispatch (which keeps the caller's mapping).
 */
public record DispatchTarget(Servlet servlet,
                             String servletName,
                             String path,
                             String servletPath,
                             String pathInfo,
                             String queryString,
                             boolean asyncSupported,
                             HttpServletMapping mapping) {
    /** True for a named dispatch (section 9.1 {@code getNamedDispatcher}): no path, no mapping. */
    public boolean named() { return mapping == null; }

    /** Clone with a new queryString (used for async dispatches). */
    public DispatchTarget withQueryString(String qs) {
        return new DispatchTarget(servlet, servletName, path, servletPath, pathInfo, qs, asyncSupported, mapping);
    }
}
