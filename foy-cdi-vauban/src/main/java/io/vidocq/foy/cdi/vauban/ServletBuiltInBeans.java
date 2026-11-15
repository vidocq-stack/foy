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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The beans CDI 4.1 requires of a container that implements Servlet (§3.11 of the Jakarta EE integration):
 * {@code HttpServletRequest}, {@code HttpSession} and {@code ServletContext}, qualifier {@code @Default}, with the
 * scopes Weld gives them. Producer methods compiled by vauban-processor at Foy's build, so the client proxies of the
 * three interfaces are classes generated then: no {@code java.lang.reflect.Proxy}, nothing generated at run time.
 * The beans come with foy-cdi-vauban's bean index; an application declares nothing.
 *
 * <p>An application that declares its own producer of one of these types gets an ambiguous dependency.</p>
 */
@Dependent
class ServletBuiltInBeans {

    /**
     * The request Foy serves, the container's own (a forward or an include sees the request their target was
     * called with through the method's parameter, not through this bean); in an {@code AsyncContext.start} task,
     * the request wrapper of that task. The bean type is {@code HttpServletRequest} alone, as CDI names it.
     */
    @Produces
    @RequestScoped
    @Typed(HttpServletRequest.class)
    HttpServletRequest request(ServletRequestHolder holder) {
        return holder.request();
    }

    /** The session of the request, created at the first use, as {@code getSession()} does. */
    @Produces
    @SessionScoped
    HttpSession session(ServletRequestHolder holder) {
        return holder.request().getSession();
    }

    /** The context of the application deployed on this CDI container. */
    @Produces
    @ApplicationScoped
    ServletContext servletContext(ServletContextHolder holder) {
        return holder.context();
    }
}
