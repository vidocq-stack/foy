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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.spi.security.SecurityProvider;

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Applies the {@code @ServletSecurity} constraints of a servlet before invoking it. The
 * constraints come from the servlet's descriptor ({@link ServletSecurityElement}, built at build
 * time or read from the class bytes), never from runtime annotation reflection.
 *
 * <p>MVP support:</p>
 * <ul>
 *   <li>roles allowed: if not empty, requires the user to be authenticated AND to have at
 *       least one listed role;</li>
 *   <li>{@link ServletSecurity.EmptyRoleSemantic#DENY} on empty roles: always refuses;</li>
 *   <li>{@link HttpMethodConstraintElement}: overrides the class constraint for its HTTP method.</li>
 * </ul>
 *
 * <p>Returns {@code false} if the request was rejected (401/403) — the caller does not invoke the servlet.</p>
 */
public final class SecurityConstraintEnforcer {

    private final BasicAuthenticator authenticator;

    public SecurityConstraintEnforcer(SecurityProvider provider) {
        this.authenticator = new BasicAuthenticator(provider);
    }

    /**
     * @param security the servlet's constraints, {@code null} when it declares none
     * @return {@code false} if the request was rejected
     */
    public boolean enforce(ServletSecurityElement security, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        if (security == null) return true;

        HttpConstraintElement effective = resolveEffective(security, req.getMethod());
        String[] roles = effective.getRolesAllowed();

        if (roles.length == 0) {
            if (effective.getEmptyRoleSemantic() == ServletSecurity.EmptyRoleSemantic.DENY) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                return false;
            }
            return true; // PERMIT
        }

        // Non-empty roles: authentication is required.
        if (req instanceof HttpServletRequestImpl impl && impl.currentUser() == null) {
            tryBasicAuth(impl);
        }
        AuthenticatedUser user = extractUser(req);
        if (user == null) {
            res.setHeader("WWW-Authenticate", authenticator.challengeHeaderValue());
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return false;
        }
        for (String r : roles) {
            if (user.hasRole(r)) return true;
        }
        res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
        return false;
    }

    private void tryBasicAuth(HttpServletRequestImpl req) {
        String header = req.getHeader("Authorization");
        if (header == null) return;
        authenticator.tryAuthenticate(header)
                .ifPresent(u -> req.bindAuthenticated(u, "BASIC"));
    }

    private static AuthenticatedUser extractUser(HttpServletRequest req) {
        if (req instanceof HttpServletRequestImpl impl) return impl.currentUser();
        var p = req.getUserPrincipal();
        return p instanceof AuthenticatedUser u ? u : null;
    }

    private static HttpConstraintElement resolveEffective(ServletSecurityElement security, String method) {
        for (HttpMethodConstraintElement m : security.getHttpMethodConstraints()) {
            if (m.getMethodName().equals(method)) return m;
        }
        return security;
    }
}
