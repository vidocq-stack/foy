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
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Form based authentication (Servlet 6.1 sections 13.6.3 and 13.6.3.1).
 *
 * <ul>
 *   <li><b>Challenge.</b> The target of a GET or HEAD is saved server-side in the session. It is
 *       rebuilt from the request URI (without {@code ;jsessionid}) and the query, never from a
 *       parameter or a header. The request is then forwarded to {@code form-login-page}, so the
 *       browser keeps the original URL. Any other method saves nothing: a protected POST is not
 *       replayed.</li>
 *   <li><b>{@code POST .../j_security_check}.</b> On success, the identity is bound, the session id
 *       rotated, the identity cached in the session and the client redirected (302) to the saved
 *       target, or to the context root when none is saved. The redirect carries the session id only
 *       when the client sent its id in the URL. On failure, the request is forwarded to
 *       {@code form-error-page}. Any other method gets 405.</li>
 * </ul>
 */
public final class FormAuthenticator implements Authenticator {

    public static final String LOGIN_ACTION = "/j_security_check";
    static final String IDENTITY_NOTE = FormAuthenticator.class.getName() + ".identity";
    static final String SAVED_TARGET_NOTE = FormAuthenticator.class.getName() + ".savedTarget";

    private final IdentityStore store;
    private final String loginPage;
    private final String errorPage;

    public FormAuthenticator(IdentityStore store, String loginPage, String errorPage) {
        this.store = Objects.requireNonNull(store, "store");
        this.loginPage = page(loginPage);
        this.errorPage = page(errorPage);
    }

    private static String page(String p) {
        if (p == null || p.isBlank()) return null;
        String t = p.trim();
        return t.startsWith("/") ? t : "/" + t;
    }

    @Override public String authType() { return HttpServletRequest.FORM_AUTH; }
    @Override public Optional<Identity> authenticate(HttpServletRequestImpl req) { return Optional.empty(); }
    @Override public boolean sessionBased() { return true; }

    public boolean isLoginAction(String canonicalPath) {
        return canonicalPath.endsWith(LOGIN_ACTION);
    }

    @Override
    public void challenge(HttpServletRequestImpl req, HttpServletResponse res) throws IOException, ServletException {
        if (loginPage == null) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String method = req.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            ((HttpSessionImpl) req.getSession(true)).setNote(SAVED_TARGET_NOTE, savedTarget(req));
        } else if (req.getSession(false) instanceof HttpSessionImpl session) {
            session.removeNote(SAVED_TARGET_NOTE);
        }
        forward(req, res, loginPage);
    }

    void processLogin(HttpServletRequestImpl req, HttpServletResponse res, WebSecurity security)
            throws IOException, ServletException {
        if (!"POST".equals(req.getMethod())) {
            res.setHeader("Allow", "POST");
            res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        String user = req.getParameter("j_username");
        String password = req.getParameter("j_password");
        Optional<Identity> identity = CredentialCheck.validate(store, user,
                password == null ? null : password.toCharArray());
        if (identity.isEmpty()) {
            if (errorPage == null) res.sendError(HttpServletResponse.SC_FORBIDDEN);
            else forward(req, res, errorPage);
            return;
        }
        security.loggedIn(req, identity.get(), authType());
        Object saved = req.getSession(false) instanceof HttpSessionImpl s ? s.removeNote(SAVED_TARGET_NOTE) : null;
        String target = safeTarget(saved instanceof String t ? t : null, req.getContextPath());
        // A client that sends its id in a cookie keeps a clean URL: the new id never reaches the
        // address bar, the history or a later Referer.
        res.sendRedirect(req.isRequestedSessionIdFromURL() ? res.encodeRedirectURL(target) : target);
    }

    /** The request URI without any {@code ;jsessionid=} parameter, plus the query: a context path. */
    static String savedTarget(HttpServletRequest req) {
        String uri = req.getRequestURI().replaceAll("(?i);jsessionid=[^;/?#]*", "");
        String query = req.getQueryString();
        return query == null ? uri : uri + '?' + query;
    }

    /**
     * {@code target} when it is a path of this context with no scheme, authority, backslash or
     * control character; the context root otherwise. A defence in depth: the target is built
     * server-side.
     */
    static String safeTarget(String target, String contextPath) {
        String root = contextPath + "/";
        if (target == null || target.startsWith("//") || target.indexOf('\\') >= 0) return root;
        if (!(target.equals(contextPath) && !contextPath.isEmpty()) && !target.startsWith(root)) return root;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c < 0x20 || c == 0x7f) return root;
        }
        return target;
    }

    /**
     * Forwards to the login or error page, marked uncacheable first: the login page answers 200 at
     * the protected URL, and a cache keeping it there would serve it again after the login (a login
     * loop) or to another user. A forward clears only the buffer, so the headers survive whatever
     * the page writes; only a page that explicitly sets {@code Cache-Control}/{@code Pragma} or calls
     * {@code reset()} removes them, which is that page's deliberate choice.
     */
    private static void forward(HttpServletRequestImpl req, HttpServletResponse res, String page)
            throws IOException, ServletException {
        RequestDispatcher rd = req.getServletContext().getRequestDispatcher(page);
        if (rd == null) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Pragma", "no-cache");
        rd.forward(req, res);
    }
}
