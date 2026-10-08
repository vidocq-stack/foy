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
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.foy.internal.dispatcher.DispatchResolver;
import io.vidocq.foy.internal.dispatcher.DispatchTarget;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.RequestDispatcherImpl;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.async.AsyncContextImpl;
import io.vidocq.foy.internal.dispatcher.VidocqFilterChain;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.internal.http.CookieCodec;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Handler} Chappe which converts a Chappe request into a servlet cycle:
 * <ol>
 *   <li>Resolves servlet via {@link ServletDispatcher}</li>
 *   <li>Constructs the applicable filter chain via {@link FilterRegistry}</li>
 *   <li>Constructed {@link HttpServletRequestImpl} + {@link HttpServletResponseImpl}</li>
 *   <li>Delegate to {@link #invoke(DispatchTarget, HttpServletRequest, HttpServletResponse, DispatcherType) dispatch interne}
 *       reused by {@link RequestDispatcherImpl} for forward/include</li>
 *   <li>Emits {@code Set-Cookie JSESSIONID} if a session has been created</li>
 *   <li>Materializes the response Immutable Chappe</li>
 * </ol>
 */
public final class ChappeServletBridge implements Handler, RequestDispatcherImpl.Invoker {

    private final ServletDispatcher dispatcher;
    private final FilterRegistry filterRegistry;
    private final io.vidocq.foy.internal.container.VidocqServletContext servletContext;
    private final SessionManager sessionManager;
    private final String contextPath;

    public ChappeServletBridge(ServletDispatcher dispatcher,
                               FilterRegistry filterRegistry,
                               io.vidocq.foy.internal.container.VidocqServletContext servletContext,
                               SessionManager sessionManager,
                               String contextPath) {
        this.dispatcher = dispatcher;
        this.filterRegistry = filterRegistry;
        this.servletContext = servletContext;
        this.sessionManager = sessionManager;
        this.contextPath = contextPath;
        servletContext.setDispatchInfrastructure(new DispatchResolver(dispatcher), this);
    }

    /** Construction without sessions. */
    public ChappeServletBridge(ServletDispatcher dispatcher,
                               FilterRegistry filterRegistry,
                               io.vidocq.foy.internal.container.VidocqServletContext servletContext,
                               String contextPath) {
        this(dispatcher, filterRegistry, servletContext, null, contextPath);
    }

    /** Minimal construction (compat tests). */
    public ChappeServletBridge(ServletDispatcher dispatcher,
                               io.vidocq.foy.internal.container.VidocqServletContext servletContext,
                               String contextPath) {
        this(dispatcher, new FilterRegistry(List.of()), servletContext, null, contextPath);
    }

    @Override
    public Response handle(Request request) throws Exception {
        String rawPath = request.path();
        // URL rewriting (§7.1) : extrait un jsessionid inline du path et le retire
        // du path utilisé pour le dispatching.
        String urlSessionId = null;
        int sidx = rawPath.indexOf(";jsessionid=");
        if (sidx >= 0) {
            int endSid = sidx + ";jsessionid=".length();
            int stop = endSid;
            while (stop < rawPath.length() && rawPath.charAt(stop) != ';'
                    && rawPath.charAt(stop) != '/' && rawPath.charAt(stop) != '?') {
                stop++;
            }
            urlSessionId = rawPath.substring(endSid, stop);
            rawPath = rawPath.substring(0, sidx) + rawPath.substring(stop);
        }
        // Strip le contextPath du path entrant avant le dispatching.
        String path = rawPath;
        if (!contextPath.isEmpty() && !"/".equals(contextPath) && rawPath.startsWith(contextPath)) {
            path = rawPath.substring(contextPath.length());
            if (path.isEmpty()) path = "/";
        }
        final String finalUrlSessionId = urlSessionId;
        Optional<ServletDispatcher.Mapping> match = dispatcher.find(path);
        ListenerRegistry registry = servletContext.listenerRegistry();

        HttpServletRequestImpl req;
        HttpServletResponseImpl res = new HttpServletResponseImpl();

        if (match.isEmpty()) {
            req = new HttpServletRequestImpl(request, servletContext, contextPath, path, null,
                    sessionManager);
            req.bindResponse(res);
            req.setUrlSessionId(finalUrlSessionId);
            List<Filter> filters = filterRegistry.chainFor(path, DispatcherType.REQUEST);
            if (filters.isEmpty()) {
                // Pas de mapping ni de filtre : 404 + error-page si mappée (§9.9.1).
                try { res.sendError(404); } catch (IOException ignored) {}
                try { maybeHandleError(req, res, null, null); }
                catch (ServletException e) { return error(e); }
                if (!errorPageHandled(req)) return notFound();
                return toChappeResponse(res);
            }

            registry.fireRequestInitialized(servletContext, req);
            try {
                new VidocqFilterChain(filters, null).doFilter(req, res);
            } catch (ServletException e) {
                registry.fireRequestDestroyed(servletContext, req);
                return error(e);
            }
            registry.fireRequestDestroyed(servletContext, req);
            if (res.getStatus() == 200 && res.bodyBytes().length == 0) {
                try { res.sendError(404); } catch (IOException ignored) {}
                try { maybeHandleError(req, res, null, null); }
                catch (ServletException e) { return error(e); }
                if (!errorPageHandled(req)) return notFound();
            }
            maybeAttachSessionCookie(req, res);
            return toChappeResponse(res);
        }

        ServletDispatcher.Mapping m = match.get();
        String servletPath = DispatchResolver.servletPathFor(m, path);
        String pathInfo = DispatchResolver.pathInfoFor(m, path, servletPath);
        DispatchTarget target = new DispatchTarget(m.servlet(), m.servletName(), path, servletPath,
                pathInfo, request.query());
        req = new HttpServletRequestImpl(request, servletContext, contextPath, servletPath, pathInfo,
                sessionManager);
        req.bindResponse(res);
        req.setAsyncSupported(m.asyncSupported());
        req.setUrlSessionId(finalUrlSessionId);

        registry.fireRequestInitialized(servletContext, req);
        Throwable thrown = null;
        try {
            var enforcer = new io.vidocq.foy.internal.security.SecurityConstraintEnforcer(
                    servletContext.securityProvider());
            if (!enforcer.enforce(m.security(), req, res)) {
                registry.fireRequestDestroyed(servletContext, req);
                return toChappeResponse(res);
            }
            invoke(target, req, res, DispatcherType.REQUEST);
            thrown = awaitAsyncIfStarted(req, res);
        } catch (ServletException | IOException | RuntimeException e) {
            thrown = e;
        }
        registry.fireRequestDestroyed(servletContext, req);

        try {
            maybeHandleError(req, res, thrown, target.servletName());
        } catch (ServletException e) {
            return error(e);
        }
        if (thrown != null && !errorPageHandled(req)) {
            return error(thrown);
        }
        maybeAttachSessionCookie(req, res);
        return toChappeResponse(res);
    }

    private boolean errorPageHandled(HttpServletRequestImpl req) {
        return req.getAttribute("jakarta.servlet.error.handled") != null;
    }

    /**
     * If the servlet started async, blocks until complete/dispatch/timeout. In case of
     * dispatch, re-resolve and re-execute the chain under {@link DispatcherType#ASYNC}.
     * Returns a {@link Throwable} if a timeout occurred and was not handled by listener.
     */
    private Throwable awaitAsyncIfStarted(HttpServletRequestImpl req, HttpServletResponseImpl res) {
        AsyncContextImpl ac = req.asyncContextInternal();
        if (ac == null) return null;
        // Boucle : le servlet re-dispatché peut appeler startAsync+dispatch à nouveau
        // (§2.3.3.3 startAsyncAgainTest*). Max 16 dispatches pour éviter les boucles.
        for (int i = 0; i < 16; i++) {
            ac.awaitCompletion();
            if (!ac.hasDispatch()) break;
            String dispatchPath = ac.dispatchPath();
            jakarta.servlet.ServletContext targetCtx = ac.dispatchContext();
            try {
                if (targetCtx instanceof io.vidocq.foy.internal.container.VidocqServletContext vctx
                        && vctx != servletContext) {
                    // §2.3.3.3 + §9.4 : cross-context async dispatch — route vers le bridge
                    // cible en utilisant son resolver/invoker.
                    String tgtCtxPath = vctx.getContextPath();
                    String relative = dispatchPath;
                    if (!tgtCtxPath.equals("/") && relative.startsWith(tgtCtxPath)) {
                        relative = relative.substring(tgtCtxPath.length());
                        if (relative.isEmpty()) relative = "/";
                    }
                    String qs = null;
                    int q = relative.indexOf('?');
                    if (q >= 0) { qs = relative.substring(q + 1); relative = relative.substring(0, q); }
                    var resolver = vctx.dispatchResolver();
                    var invoker = vctx.dispatchInvoker();
                    if (resolver == null || invoker == null) break;
                    var target = resolver.resolve(relative).orElse(null);
                    if (target == null) break;
                    if (qs != null) target = target.withQueryString(qs);
                    var wrapped = new AsyncDispatchRequest(req, target, vctx, tgtCtxPath);
                    req.clearAsyncContext();
                    invoker.invoke(target, wrapped, res, DispatcherType.ASYNC);
                } else {
                    String relative = dispatchPath.startsWith(contextPath) && !contextPath.equals("/")
                            ? dispatchPath.substring(contextPath.length()) : dispatchPath;
                    String qs = null;
                    int q = relative.indexOf('?');
                    if (q >= 0) { qs = relative.substring(q + 1); relative = relative.substring(0, q); }
                    var target = new DispatchResolver(dispatcher).resolve(relative).orElse(null);
                    if (target == null) break;
                    if (qs != null) target = target.withQueryString(qs);
                    var wrapped = new AsyncDispatchRequest(req, target);
                    req.clearAsyncContext();
                    invoke(target, wrapped, res, DispatcherType.ASYNC);
                }
            } catch (ServletException | IOException | RuntimeException e) {
                return e;
            }
            // Le servlet re-dispatché a pu (ou non) appeler startAsync+dispatch à nouveau.
            ac = req.asyncContextInternal();
            if (ac == null) break;
        }
        if (ac != null && ac.timedOut() && !res.isCommitted() && res.bodyBytes().length == 0) {
            try { res.sendError(503, "async timeout"); }
            catch (IOException ignored) {}
        }
        return null;
    }

    private void maybeHandleError(HttpServletRequestImpl req, HttpServletResponseImpl res,
                                  Throwable thrown, String servletName) throws ServletException {
        ErrorPageRegistry pages = servletContext.errorPages();
        String location = null;
        Integer errorStatus = null;
        if (thrown != null) {
            location = pages.findByException(thrown).orElse(null);
            // Servlet 6.1 §2.3.3.2 : UnavailableException remonte explicitement
            // un status 404 (permanent) ou 503 (temporary) au lieu du 500 générique.
            Throwable root = thrown;
            while (root.getCause() != null && !(root instanceof jakarta.servlet.UnavailableException)) {
                root = root.getCause();
            }
            if (root instanceof jakarta.servlet.UnavailableException ue) {
                errorStatus = ue.isPermanent() ? 404 : 503;
            } else {
                errorStatus = 500;
            }
        } else if (res.isErrorTriggered()) {
            errorStatus = res.getStatus();
            location = pages.findByStatus(errorStatus).orElse(null);
        }
        if (location == null) {
            // Pas d'error-page mappée : on applique tout de même le status approprié
            // (404/503 pour UnavailableException) et on court-circuite le error()
            // générique du handler.
            if (thrown instanceof jakarta.servlet.UnavailableException
                    || (thrown != null && thrown.getCause() instanceof jakarta.servlet.UnavailableException)) {
                res.clearErrorState();
                res.resetBuffer();
                try { res.sendError(errorStatus, thrown.getMessage()); }
                catch (IOException ignored) {}
                req.setAttribute("jakarta.servlet.error.handled", Boolean.TRUE);
            }
            return;
        }

        var target = new DispatchResolver(dispatcher).resolve(location).orElse(null);
        if (target == null) return;

        res.clearErrorState();
        res.resetBuffer();

        req.setAttribute("jakarta.servlet.error.status_code", errorStatus);
        req.setAttribute("jakarta.servlet.error.request_uri", req.getRequestURI());
        req.setAttribute("jakarta.servlet.error.servlet_name", servletName);
        if (thrown != null) {
            req.setAttribute("jakarta.servlet.error.exception", thrown);
            req.setAttribute("jakarta.servlet.error.exception_type", thrown.getClass());
            req.setAttribute("jakarta.servlet.error.message", thrown.getMessage());
        } else if (res.errorMessage() != null) {
            req.setAttribute("jakarta.servlet.error.message", res.errorMessage());
        }
        req.setAttribute("jakarta.servlet.error.handled", Boolean.TRUE);

        try {
            res.setStatus(errorStatus);
            // §10.9.2 : la request du servlet d'erreur doit retourner DispatcherType.ERROR.
            DispatcherType previous = req.getDispatcherType();
            req.setDispatcherType(DispatcherType.ERROR);
            try { invoke(target, req, res, DispatcherType.ERROR); }
            finally { req.setDispatcherType(previous); }
        } catch (IOException | RuntimeException e) {
            throw new ServletException("error dispatch failed", e);
        }
    }

    /**
     * Internal dispatch — calculates the filter chain for the given {@link DispatcherType}
     * then invokes the target servlet. Reused by {@link RequestDispatcherImpl} for
     * forward/include and by {@link #handle(Request)} for REQUEST.
     */
    @Override
    public void invoke(DispatchTarget target, HttpServletRequest req, HttpServletResponse res,
                       DispatcherType type) throws IOException, ServletException {
        List<Filter> filters = filterRegistry.chainFor(target.path(), type);
        new VidocqFilterChain(filters, target.servlet()).doFilter(req, res);
    }

    private void maybeAttachSessionCookie(HttpServletRequestImpl req, HttpServletResponseImpl res) {
        HttpSessionImpl session = req.boundSession();
        if (session == null || session.isInvalidated()) return;
        String requested = req.getRequestedSessionId();
        if (!session.getId().equals(requested)) {
            Cookie c = new Cookie(SessionManager.COOKIE_NAME, session.getId());
            c.setPath("/".equals(contextPath) ? "/" : contextPath);
            c.setHttpOnly(true);
            res.addHeader("Set-Cookie", CookieCodec.serializeSetCookie(c));
        }
    }

    static Response toChappeResponse(HttpServletResponseImpl res) {
        var builder = Response.builder()
                .status(StatusCode.of(res.getStatus()))
                .body(Body.of(res.bodyBytes()));
        for (Map.Entry<String, List<String>> e : res.allHeaders().entrySet()) {
            for (String v : e.getValue()) {
                builder.header(e.getKey(), v);
            }
        }
        return builder.build();
    }

    private static Response notFound() {
        return Response.builder()
                .status(StatusCode.NOT_FOUND)
                .header("Content-Type", "text/plain")
                .body(Body.of("Not Found".getBytes()))
                .build();
    }

    private static Response error(Throwable e) {
        // Log full stack for debugging — silent if DEBUG not set.
        if (Boolean.getBoolean("vidocq.servlet.debug")) {
            e.printStackTrace(System.err);
        }
        return Response.builder()
                .status(StatusCode.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "text/plain")
                .body(Body.of(("Servlet error: " + e.getMessage()).getBytes()))
                .build();
    }
}
