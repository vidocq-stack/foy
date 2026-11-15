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

import jakarta.enterprise.context.RequestScoped;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The servlet request of the current CDI request context: {@link ServletRequestBinding} puts it here once Foy has
 * activated the context for a request, and {@link ServletBuiltInBeans} produces the {@code HttpServletRequest} bean
 * from it. Request scoped, it lives exactly as long as the request context, with no thread-bound state of its own:
 * where the request context is active for a servlet request (the filters and servlets, a forward, an include, an
 * {@code AsyncListener}, an {@code AsyncContext.start} task), it holds that request.
 */
@RequestScoped
class ServletRequestHolder {

    /** Why there is no request where the request context is active without one. */
    static final String NO_REQUEST = "No servlet request in this request context: the HttpServletRequest bean is "
            + "injectable while Foy serves a request (a request listener, a filter, a servlet, an AsyncListener, an "
            + "AsyncContext.start task), not where the CDI request context was activated another way";

    private HttpServletRequest request;

    /** Holds the request Foy serves in this request context. */
    void hold(HttpServletRequest servedRequest) {
        this.request = servedRequest;
    }

    /**
     * The request Foy serves in this request context.
     *
     * @throws IllegalStateException when the request context was activated without a servlet request
     */
    HttpServletRequest request() {
        if (request == null) {
            throw new IllegalStateException(NO_REQUEST);
        }
        return request;
    }
}
