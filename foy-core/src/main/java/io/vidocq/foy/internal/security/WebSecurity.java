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

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.security.ConstraintTable.Decision;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * The security of one web application at request time: its compiled constraints, its
 * authentication mechanism and the embedder's settings. The bridge calls {@link #admit} for every
 * REQUEST dispatch, on the canonical path, before any target. Forward, include, error and async
 * dispatches are never checked again (section 13.8).
 */
public final class WebSecurity {

    private static final System.Logger LOG = System.getLogger(WebSecurity.class.getName());

    private final ConstraintTable constraints;
    private final Authenticator authenticator;
    private final SecuritySettings settings;
    private final boolean refuseAll;

    /**
     * The security of a context the deployer has not configured: it fails closed. Every request
     * answers 403 and no login is possible, so a context built outside {@code WebAppDeployer}
     * never admits a request it was not explicitly told to admit.
     */
    public static final WebSecurity UNCONFIGURED =
            new WebSecurity(ConstraintTable.EMPTY, new DenyingAuthenticator("NONE"), SecuritySettings.DEFAULTS, true);

    public WebSecurity(ConstraintTable constraints, Authenticator authenticator, SecuritySettings settings) {
        this(constraints, authenticator, settings, false);
    }

    private WebSecurity(ConstraintTable constraints, Authenticator authenticator, SecuritySettings settings,
                        boolean refuseAll) {
        this.constraints = Objects.requireNonNull(constraints, "constraints");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.refuseAll = refuseAll;
    }

    /** No constraint, BASIC over the settings' store: chosen explicitly (tests, a bridge built by hand). */
    public static WebSecurity permitAll(SecuritySettings settings) {
        return new WebSecurity(ConstraintTable.EMPTY,
                new BasicAuthenticator(settings.identityStore(), BasicAuthenticator.DEFAULT_REALM), settings);
    }

    /** The mechanism a {@code login-config} names; none or no {@code auth-method} means BASIC (section 13.6). */
    public static Authenticator authenticatorFor(LoginConfigDef loginConfig, IdentityStore store) {
        String method = loginConfig == null || loginConfig.authMethod() == null || loginConfig.authMethod().isBlank()
                ? "BASIC" : loginConfig.authMethod().trim().toUpperCase(Locale.ROOT);
        String realm = loginConfig == null || loginConfig.realmName() == null || loginConfig.realmName().isBlank()
                ? BasicAuthenticator.DEFAULT_REALM : loginConfig.realmName().trim();
        return switch (method) {
            case "BASIC" -> new BasicAuthenticator(store, realm);
            default -> {
                LOG.log(System.Logger.Level.WARNING,
                        "auth-method {0} is not supported; protected resources answer 403", method);
                yield new DenyingAuthenticator(method);
            }
        };
    }

    public Authenticator authenticator() { return authenticator; }
    public SecuritySettings settings() { return settings; }

    /**
     * Applies the constraint of {@code canonicalPath} and the request method. Returns {@code true}
     * when the request proceeds; otherwise the response is produced (401 challenge, 403, or the
     * mechanism's challenge) and {@code false} is returned. Any exception fails closed: the caller
     * does not invoke the target.
     */
    public boolean admit(HttpServletRequestImpl req, HttpServletResponse res, String canonicalPath)
            throws IOException, ServletException {
        if (refuseAll) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        Decision decision = constraints.decide(canonicalPath, req.getMethod());
        switch (decision.access()) {
            case UNCHECKED -> { return true; }
            case EXCLUDED -> { res.sendError(HttpServletResponse.SC_FORBIDDEN); return false; }
            case ROLES -> { }
        }
        if (req.identity() == null) {
            authenticator.authenticate(req).ifPresent(id -> req.bindIdentity(id, authenticator.authType()));
        }
        if (req.identity() == null) {
            authenticator.challenge(req, res);
            return false;
        }
        if (!decision.permits(req.identity())) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
