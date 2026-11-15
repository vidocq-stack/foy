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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * A runtime that takes over the authentication of a web application, for instance a Jakarta Authentication
 * implementation that arranges for an {@code HttpAuthenticationMechanism} to be placed in service
 * (Jakarta Security 4.0, section 2.5).
 *
 * <p>When one is installed, it is asked for <em>every</em> request, protected or not, before the constraint of the
 * resource is applied; it replaces the {@code login-config} mechanism of the container. It knows nothing of the
 * container's classes: Foy defines this contract and depends on no implementation of it.
 */
public interface RequestAuthentication {

    /**
     * Authenticates one request.
     *
     * @param mandatory {@code true} when the resource is protected by a constraint, so the caller must be known
     *                  to proceed (Jakarta Authentication: the {@code javax.security.auth.message.MessagePolicy}
     *                  of the request is mandatory)
     * @return what became of the request; never {@code null}
     */
    Outcome validateRequest(HttpServletRequest request, HttpServletResponse response, boolean mandatory)
            throws IOException, ServletException;

    /** Forgets the caller of {@code request}: called when the application logs out. */
    default void cleanSubject(HttpServletRequest request) {
        // Nothing to forget by default.
    }

    /** What an installed {@link RequestAuthentication} made of a request. */
    sealed interface Outcome permits Outcome.Authenticated, Outcome.Anonymous, Outcome.ResponseSent {

        /** The request carries a caller; {@code authType} is the value of {@code getAuthType()}. */
        record Authenticated(Identity identity, String authType) implements Outcome {
            public Authenticated {
                Objects.requireNonNull(identity, "identity");
                Objects.requireNonNull(authType, "authType");
            }
        }

        /** Nobody was identified and the request may go on: refused if the resource is protected. */
        record Anonymous() implements Outcome { }

        /** The hook already produced the response (a redirect, a challenge, an error): the request stops. */
        record ResponseSent() implements Outcome { }
    }

    /** See {@link Outcome.Authenticated}. */
    static Outcome authenticated(Identity identity, String authType) { return new Outcome.Authenticated(identity, authType); }

    /** See {@link Outcome.Anonymous}. */
    static Outcome anonymous() { return new Outcome.Anonymous(); }

    /** See {@link Outcome.ResponseSent}. */
    static Outcome responseSent() { return new Outcome.ResponseSent(); }
}
