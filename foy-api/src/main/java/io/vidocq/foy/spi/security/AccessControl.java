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
package io.vidocq.foy.spi.security;

/**
 * A runtime that decides the access to web resources in place of the security constraints alone, for instance a
 * Jakarta Authorization implementation whose {@code Policy} answers {@code WebResourcePermission} checks
 * (Servlet 6.1 section 13, Jakarta Authorization 3.0).
 *
 * <p>When one is installed, it is asked for every request, once the caller is known, instead of the container
 * applying the constraints itself. The constraints stay available as {@link Constraints}: a default policy answers
 * with them. Foy defines this contract and depends on no implementation of it.
 */
@FunctionalInterface
public interface AccessControl {

    /**
     * Decides one request.
     *
     * @param path        the canonical path of the resource, without the context path
     * @param method      the HTTP method
     * @param caller      who the request is made by; {@code null} when nobody was identified
     * @param constraints what the security constraints of the application say
     * @return {@code true} when the request may go on; otherwise it is refused
     */
    boolean permits(String path, String method, Identity caller, Constraints constraints);

    /** The security constraints of the application (web.xml, {@code @ServletSecurity}). */
    @FunctionalInterface
    interface Constraints {

        /** Whether the constraints let {@code caller} ({@code null}: nobody) reach {@code path} with {@code method}. */
        boolean permits(String path, String method, Identity caller);
    }
}
