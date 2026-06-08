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
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.HttpMethodConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/**
 * Applies {@code @ServletSecurity} constraints before invoking the servlet.
 *
 * <p>MVP support:</p>
 * <ul>
 *   <li>{@link HttpConstraint}#rolesAllowed: if not empty, requires the user to
 *       is authenticated AND has at least one listed role.</li>
 *   <li>{@link ServletSecurity.EmptyRoleSemantic}#DENY on empty rolesAllowed: always refuses.</li>
 *   <li>{@link HttpMethodConstraint}: override by HTTP method if present.</li>
 * </ul>
 *
 * <p>Returns {@code false} if the request was rejected (401/403) — the caller does not invoke the servlet.</p>
 */
public final class SecurityConstraintEnforcer {

    private final BasicAuthenticator authenticator;

    public SecurityConstraintEnforcer(SecurityProvider provider) {
        this.authenticator = new BasicAuthenticator(provider);
    }

    public boolean enforce(Class<?> servletClass, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        ServletSecurity annotation = servletClass.getAnnotation(ServletSecurity.class);
        if (annotation == null) return true;
        ServletSecurityElement element = toElement(annotation);

        HttpConstraint effective = resolveEffective(annotation, req.getMethod());
        String[] roles = effective.rolesAllowed();
        var semantic = effective.value();

        if (roles.length == 0) {
            if (semantic == ServletSecurity.EmptyRoleSemantic.DENY) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                return false;
            }
            return true; // PERMIT
        }

        // rolesAllowed non vide → exige authentification.
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

    private static HttpConstraint resolveEffective(ServletSecurity annotation, String method) {
        for (HttpMethodConstraint m : annotation.httpMethodConstraints()) {
            if (m.value().equals(method)) {
                // Construire un HttpConstraint virtuel via proxy — pour MVP on lit directement les champs.
                return new MethodConstraintAsHttpConstraint(m);
            }
        }
        return annotation.value();
    }

    private static ServletSecurityElement toElement(ServletSecurity annotation) {
        return new ServletSecurityElement(annotation);
    }

    /** Minimum adapt: ​​{@link HttpMethodConstraint} to {@link HttpConstraint}. */
    private record MethodConstraintAsHttpConstraint(HttpMethodConstraint methodConstraint)
            implements HttpConstraint {
        @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return HttpConstraint.class; }
        @Override public ServletSecurity.EmptyRoleSemantic value() { return methodConstraint.emptyRoleSemantic(); }
        @Override public ServletSecurity.TransportGuarantee transportGuarantee() { return methodConstraint.transportGuarantee(); }
        @Override public String[] rolesAllowed() { return methodConstraint.rolesAllowed(); }
    }

    public static Set<String> rolesOf(Class<?> servletClass) {
        ServletSecurity ann = servletClass.getAnnotation(ServletSecurity.class);
        if (ann == null) return Set.of();
        return new java.util.HashSet<>(Arrays.asList(ann.value().rolesAllowed()));
    }
}
