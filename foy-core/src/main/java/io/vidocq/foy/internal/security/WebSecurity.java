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
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import io.vidocq.foy.spi.security.Identity;
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
            case "FORM" -> {
                if (loginConfig.formLoginPage() == null) {
                    LOG.log(System.Logger.Level.WARNING,
                            "FORM login-config without form-login-page; protected resources answer 403");
                }
                yield new FormAuthenticator(store, loginConfig.formLoginPage(), loginConfig.formErrorPage());
            }
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
     * does not invoke the target. Order: the identity cached in the session (FORM) is restored, a
     * CONFIDENTIAL constraint on a plain request is refused or redirected to HTTPS, then a FORM login action is handled
     * (whatever its access rule), then the access rule applies.
     */
    public boolean admit(HttpServletRequestImpl req, HttpServletResponse res, String canonicalPath)
            throws IOException, ServletException {
        if (refuseAll) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        restoreIdentity(req);
        Decision decision = constraints.decide(canonicalPath, req.getMethod());
        // The transport guarantee comes first, the login action included: a confidential
        // application never accepts credentials over plain HTTP.
        if (decision.confidential() && !req.isSecure()) {
            refuseInsecure(req, res);
            return false;
        }
        if (authenticator instanceof FormAuthenticator form && form.isLoginAction(canonicalPath)) {
            form.processLogin(req, res, this);
            return false;
        }
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

    /**
     * A CONFIDENTIAL/INTEGRAL constraint on a plain request (section 13.8). With a configured HTTPS
     * port, the client is redirected to the same URI over HTTPS, whatever the method (a request
     * body is not carried over). Otherwise, or when the Host header is not a plain host name, the
     * request is refused with 403: a hostile Host header never steers the redirect.
     */
    private void refuseInsecure(HttpServletRequestImpl req, HttpServletResponse res) throws IOException {
        int port = settings.confidentialPort();
        String host = req.getServerName();
        String uri = req.getRequestURI();
        String query = req.getQueryString();
        // The path must start with '/', or "https://host:port" + "@evil/x" would make the host userinfo.
        if (port < 0 || !isPlainHost(host) || uri == null || !uri.startsWith("/")
                || !isPrintable(uri) || (query != null && !isPrintable(query))) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        res.sendRedirect("https://" + host + (port == 443 ? "" : ":" + port) + uri
                + (query == null ? "" : "?" + query));
    }

    /** No space, control character or non-ASCII character: the raw URI is copied into a header as is. */
    private static boolean isPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return false;
        }
        return true;
    }

    /**
     * Letters, digits, {@code '.'} and {@code '-'} only, at most 253 characters: no userinfo, path,
     * port, bracketed IPv6 literal, space or CR/LF can reach a {@code Location} header.
     */
    static boolean isPlainHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** A session-based identity (section 13.7), cached by {@link #loggedIn}. */
    record CachedIdentity(Identity identity, String authType) {}

    /** Binds the identity cached in the session, if any, to a request that has none yet. */
    void restoreIdentity(HttpServletRequestImpl req) {
        if (!authenticator.sessionBased() || req.identity() != null) return;
        if (req.getSession(false) instanceof HttpSessionImpl s
                && s.note(FormAuthenticator.IDENTITY_NOTE) instanceof CachedIdentity c) {
            req.bindIdentity(c.identity(), c.authType());
        }
    }

    /**
     * A successful login (FORM action or {@code HttpServletRequest.login}). It binds the identity
     * to the request and changes the id of an existing session (session fixation;
     * {@link SecuritySettings#rotateSessionIdOnLogin}). For a session-based mechanism, it then
     * caches the identity in the session, creating one if needed.
     */
    public void loggedIn(HttpServletRequestImpl req, Identity identity, String authType) {
        req.bindIdentity(identity, authType);
        if (req.getSession(false) != null && settings.rotateSessionIdOnLogin()) req.changeSessionId();
        if (authenticator.sessionBased()) {
            ((HttpSessionImpl) req.getSession(true))
                    .setNote(FormAuthenticator.IDENTITY_NOTE, new CachedIdentity(identity, authType));
        }
    }

    /** {@code HttpServletRequest.logout}: the request's and the session's identity are cleared; the session stays. */
    public void loggedOut(HttpServletRequestImpl req) {
        req.bindIdentity(null, null);
        if (req.getSession(false) instanceof HttpSessionImpl s) {
            s.removeNote(FormAuthenticator.IDENTITY_NOTE);
        }
    }
}
