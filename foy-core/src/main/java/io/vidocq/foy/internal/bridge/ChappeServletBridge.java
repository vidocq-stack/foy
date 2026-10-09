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
import io.vidocq.foy.internal.container.DefaultServlet;
import io.vidocq.foy.internal.container.ResourcePaths;
import io.vidocq.foy.internal.dispatcher.DispatchResolver;
import io.vidocq.foy.internal.dispatcher.DispatchTarget;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.RequestDispatcherImpl;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.async.AsyncContextImpl;
import io.vidocq.foy.internal.dispatcher.VidocqFilterChain;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.internal.http.CookieCodec;
import io.vidocq.foy.internal.http.RequestPaths;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.internal.session.VidocqSessionCookieConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
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
    private static final System.Logger LOG = System.getLogger(ChappeServletBridge.class.getName());
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
        var requests = new java.util.ArrayList<HttpServletRequestImpl>(1);
        try {
            return handle(request, requests);
        } finally {
            // End of request processing (asynchronous processing included, awaited by handle):
            // the sessions used by the request become idle, and their last-accessed time moves.
            for (HttpServletRequestImpl r : requests) r.endSessionAccess();
        }
    }

    private Response handle(Request request, List<HttpServletRequestImpl> requests) throws Exception {
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
        final String finalUrlSessionId = urlSessionId;
        HttpServletResponseImpl res = new HttpServletResponseImpl();
        res.setDefaultCharacterEncoding(servletContext.configuredResponseCharacterEncoding());

        String path;
        if ("*".equals(rawPath)) {
            // "OPTIONS *" (RFC 9110 section 9.3.7): the asterisk-form target names the server, not
            // a path; it is dispatched unchanged, as before canonicalisation existed.
            path = rawPath;
        } else {
            // Strip the context path on a segment boundary; a path outside the context is a 404.
            String relative = RequestPaths.stripContextPath(rawPath, contextPath);
            if (relative == null) return rejected(request, requests, res, 404);
            // Section 3.5.2: the canonical (decoded, normalised) path drives every mapping decision.
            path = RequestPaths.canonicalize(relative);
            if (path == null) return rejected(request, requests, res, 400);
        }

        Optional<ServletDispatcher.Mapping> match = dispatcher.find(path);
        if (!"*".equals(rawPath) && isContainerDefault(match)) {
            // Section 10.10: only the container default servlet resolves welcome files; an exact,
            // prefix or extension servlet, or an application "/" servlet, handles the path itself.
            String redirect = welcomeRedirect(rawPath, path, request.query());
            if (redirect != null) {
                var redirected = new HttpServletRequestImpl(request, servletContext, contextPath, "", null,
                        sessionManager);
                requests.add(redirected);
                redirected.bindResponse(res);
                try { res.sendRedirect(redirect); } catch (IOException ignored) {}
                return toChappeResponse(res);
            }
            String welcome = path.endsWith("/") ? welcomeTarget(path) : null;
            if (welcome != null) {
                path = welcome;
                match = dispatcher.find(path);
            }
        }
        ListenerRegistry registry = servletContext.listenerRegistry();

        HttpServletRequestImpl req;

        if (match.isEmpty()) {
            // Last-resort fallback: a deployed application always matches (its own "/" servlet or
            // the container default servlet); only a bridge built without one reaches this branch.
            req = new HttpServletRequestImpl(request, servletContext, contextPath, path, null,
                    sessionManager);
            requests.add(req);
            req.setCanonicalPath(path);
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
            maybeAttachSessionCookie(req, res);
            return toChappeResponse(res);
        }

        ServletDispatcher.Mapping m = match.get();
        String servletPath = DispatchResolver.servletPathFor(m, path);
        String pathInfo = DispatchResolver.pathInfoFor(m, path, servletPath);
        DispatchTarget target = new DispatchTarget(m.servlet(), m.servletName(), path, servletPath,
                pathInfo, request.query(), m.asyncSupported(),
                DispatchResolver.mappingFor(m, path, servletPath));
        req = new HttpServletRequestImpl(request, servletContext, contextPath, servletPath, pathInfo,
                sessionManager);
        requests.add(req);
        req.setCanonicalPath(path);
        req.setHttpServletMapping(target.mapping());
        req.bindResponse(res);
        // §2.3.3.3: async only when the servlet and every filter of the chain support it.
        req.setAsyncSupported(m.asyncSupported()
                && filterRegistry.asyncSupported(path, DispatcherType.REQUEST, m.servletName()));
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

    private static boolean isContainerDefault(Optional<ServletDispatcher.Mapping> match) {
        return match.isPresent()
                && match.get().servlet() instanceof DefaultServlet;
    }

    /**
     * The redirect location when the request names a directory without its trailing slash
     * (section 10.10), keeping the query string; {@code null} otherwise. The context root requested
     * as {@code /ctx} is such a directory.
     *
     * <p>The location is the context path plus the CANONICAL path re-encoded segment by segment
     * (RFC 3986), so it always starts with a single {@code '/'}: {@code //dir} in a root context
     * must not become the protocol-relative {@code //dir/}. A {@code ;jsessionid} path parameter of
     * the request is not carried over (the redirect target is a fresh navigation; a session tracked
     * by cookie is unaffected).</p>
     *
     * <p>Directory detection uses {@code ServletContext#getResourcePaths}, the only directory probe
     * the resource-provider SPI offers; it is asked only for a path the default servlet would
     * otherwise answer 404 or serve as a file, and answers {@code null} at once for a file.</p>
     */
    private String welcomeRedirect(String rawPath, String path, String query) {
        boolean contextRoot = !contextPath.isEmpty() && !"/".equals(contextPath) && rawPath.equals(contextPath);
        if (!contextRoot && (path.endsWith("/") || servletContext.getResourcePaths(path + "/") == null)) {
            return null;
        }
        String base = contextPath.isEmpty() || "/".equals(contextPath) ? "" : contextPath;
        String location = contextRoot ? base + "/" : base + encodePath(path) + "/";
        return query == null || query.isEmpty() ? location : location + "?" + query;
    }

    /** Percent-encodes (UTF-8) every character of a decoded path other than an RFC 3986 pchar or '/'. */
    private static String encodePath(String decoded) {
        var out = new StringBuilder(decoded.length() + 8);
        for (byte b : decoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean plain = c < 0x80 && (Character.isLetterOrDigit(c) || "-._~!$&'()*+,;=:@/".indexOf(c) >= 0);
            if (plain) out.append((char) c);
            else out.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
        }
        return out.toString();
    }

    /**
     * The first welcome-file path for the directory {@code dir} (ending with a slash) that a
     * static resource or a servlet serves, or {@code null}. Per welcome file in declaration order a
     * static resource wins over a servlet mapping (exact, prefix, extension) for the same name.
     * The caller re-dispatches the request to that path as a REQUEST (not a FORWARD): the servlet
     * path, path info and mapping describe the welcome target, {@code getRequestURI} stays the
     * client's, and the REQUEST filters of the new path apply. No {@code forward.*} attributes are
     * set (Tomcat's internal forward behaves the same).
     */
    private String welcomeTarget(String dir) {
        for (String wf : servletContext.getWelcomeFiles()) {
            String name = wf.startsWith("/") ? wf.substring(1) : wf;
            if (name.isEmpty()) continue;
            String candidate = dir + name;
            // Both branches: no dot or empty segment, backslash, NUL or encoded separator, so a
            // welcome file such as "../x.ts" can never leave the directory.
            if (!ResourcePaths.isDispatchable(candidate)) continue;
            // Static branch only: a client may not reach WEB-INF/ or META-INF/ (a welcome servlet may, section 10.10).
            if (ResourcePaths.isServable(candidate) && servletContext.getResourcePaths(candidate + "/") == null) {
                try (var in = servletContext.getResourceAsStream(candidate)) {
                    if (in != null) return candidate;
                } catch (IOException ignored) {
                    // unreadable: not a welcome file
                }
            }
            var m = dispatcher.find(candidate);
            if (m.isPresent() && !(m.get().servlet() instanceof DefaultServlet)) return candidate;
        }
        return null;
    }

    /**
     * Answers {@code status} for a request path refused before mapping: 400 for a path refused by
     * {@link RequestPaths#canonicalize} (section 3.5.2), 404 for a path outside the context. No
     * filter or servlet of the application runs and no request listener fires; an error page
     * registered for the status is honoured through the regular error dispatch.
     */
    private Response rejected(Request request, List<HttpServletRequestImpl> requests, HttpServletResponseImpl res,
                              int status) {
        var req = new HttpServletRequestImpl(request, servletContext, contextPath, "", null, sessionManager);
        requests.add(req);
        req.bindResponse(res);
        try { res.sendError(status); } catch (IOException ignored) {}
        try { maybeHandleError(req, res, null, null); }
        catch (ServletException e) { return error(e); }
        if (errorPageHandled(req)) return toChappeResponse(res);
        if (status == 404) return notFound();
        return Response.builder()
                .status(StatusCode.of(status))
                .header("Content-Type", "text/plain")
                .body(Body.of("Bad Request".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .build();
    }

    /**
     * The request an error dispatch hands its target. The container default servlet finds the
     * resource from the servlet path, so a static error page (for example {@code /error.html})
     * sees the location's paths; other targets keep the original request (unchanged behaviour,
     * to be generalised: BUG-20261009-03).
     */
    private static HttpServletRequest errorTargetRequest(HttpServletRequestImpl req, DispatchTarget target) {
        if (!(target.servlet() instanceof DefaultServlet)) return req;
        return new jakarta.servlet.http.HttpServletRequestWrapper(req) {
            @Override public String getServletPath() { return target.servletPath(); }
            @Override public String getPathInfo() { return target.pathInfo(); }
        };
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
                    req.setAttribute("jakarta.servlet.async.mapping", req.getHttpServletMapping());
                    var wrapped = new AsyncDispatchRequest(req, target, vctx, tgtCtxPath);
                    req.clearAsyncContext();
                    req.setAsyncSupported(true); // §2.3.3.3: an async dispatch starts a new cycle
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
                    req.setAttribute("jakarta.servlet.async.mapping", req.getHttpServletMapping());
                    var wrapped = new AsyncDispatchRequest(req, target);
                    req.clearAsyncContext();
                    req.setAsyncSupported(true); // §2.3.3.3: an async dispatch starts a new cycle
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
        if (thrown != null && res.isCommitted() && !res.isErrorTriggered()) {
            // The response was already committed by the servlet: no error page can be dispatched and
            // the committed content stands. Do not lose the exception.
            LOG.log(System.Logger.Level.ERROR,
                    "exception after the response was committed; no error page dispatched", thrown);
            req.setAttribute("jakarta.servlet.error.handled", Boolean.TRUE);
            return;
        }
        ErrorPageRegistry pages = servletContext.errorPages();
        String location = null;
        Integer errorStatus = null;
        Throwable matched = thrown;
        if (thrown != null) {
            var match = pages.match(thrown).orElse(null);
            if (match != null) {
                location = match.location();
                matched = match.matched();
            }
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
            // §10.9.2: no exception-type page matched, fall back to the status page; the
            // attributes then describe the original exception.
            if (location == null) location = pages.findByStatus(errorStatus).orElse(null);
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

        // Capture the sendError message before clearErrorState() erases it.
        String statusMessage = res.errorMessage();
        res.clearErrorState();
        res.resetBuffer();

        req.setAttribute("jakarta.servlet.error.status_code", errorStatus);
        req.setAttribute("jakarta.servlet.error.request_uri", req.getRequestURI());
        req.setAttribute("jakarta.servlet.error.servlet_name", servletName);
        req.setAttribute("jakarta.servlet.error.query_string", req.getQueryString());
        String message;
        if (thrown != null) {
            req.setAttribute("jakarta.servlet.error.exception", matched);
            req.setAttribute("jakarta.servlet.error.exception_type", matched.getClass());
            message = statusMessage != null ? statusMessage : matched.getMessage();
        } else {
            message = statusMessage;
        }
        req.setAttribute("jakarta.servlet.error.message", message == null ? "" : message);
        req.setAttribute("jakarta.servlet.error.handled", Boolean.TRUE);

        try {
            res.setStatus(errorStatus);
            // §10.9.2 : la request du servlet d'erreur doit retourner DispatcherType.ERROR.
            DispatcherType previous = req.getDispatcherType();
            req.setDispatcherType(DispatcherType.ERROR);
            try { invoke(target, errorTargetRequest(req, target), res, DispatcherType.ERROR); }
            finally { req.setDispatcherType(previous); }
        } catch (IOException | ServletException | RuntimeException e) {
            // The error page itself failed: log and fall through to a plain 500, never recurse.
            LOG.log(System.Logger.Level.ERROR, "error page " + location + " failed", e);
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
        // Section 6.2.5: a named dispatch matches no URL pattern, only the target's servlet-name mappings.
        String filterPath = target.named() ? null : target.path();
        FilterRegistry.Chain chain = filterRegistry.chain(filterPath, type, target.servletName());
        List<Filter> filters = chain.filters();
        // §2.3.3.3 / ServletRequest#isAsyncSupported: async stays enabled only while the request is
        // within the scope of servlets and filters that support it. Recompute for this dispatch
        // (incoming && target servlet && every filter of this dispatch's chain) and restore the
        // previous value once a forward/include returns to its caller.
        HttpServletRequestImpl impl = unwrapImpl(req);
        if (impl == null) {
            // Not provably unreachable: a caller may hand a RequestDispatcher a request that does not
            // wrap the container's own request (a spec violation, section 9.1). Run the chain, but never silently.
            LOG.log(System.Logger.Level.WARNING,
                    "async-supported not recomputed for {0} dispatch to ''{1}'': request type {2} does not wrap the container request",
                    type, target.servletName(), req.getClass().getName());
            new VidocqFilterChain(filters, target.servlet()).doFilter(req, res);
            return;
        }
        boolean previous = impl.isAsyncSupported();
        impl.setAsyncSupported(previous && target.asyncSupported()
                && chain.asyncSupported());
        try {
            new VidocqFilterChain(filters, target.servlet()).doFilter(req, res);
        } finally {
            impl.setAsyncSupported(previous);
        }
    }

    private static HttpServletRequestImpl unwrapImpl(jakarta.servlet.ServletRequest r) {
        while (r != null) {
            if (r instanceof HttpServletRequestImpl i) return i;
            if (r instanceof jakarta.servlet.ServletRequestWrapper w) r = w.getRequest();
            else return null;
        }
        return null;
    }

    /**
     * Emits the session cookie when the request ends bound to a session the client does not know
     * by that id yet (a new session, or a {@code changeSessionId}). Nothing is emitted when the
     * effective tracking modes leave out {@code COOKIE} (section 7.1.1). The cookie is
     * {@code Secure} on a secure request unless the application set the flag explicitly
     * ({@link SessionCookieConfig#setSecure}, {@code <secure>} in the descriptor).
     */
    private void maybeAttachSessionCookie(HttpServletRequestImpl req, HttpServletResponseImpl res) {
        HttpSessionImpl session = req.boundSession();
        if (session == null || session.isInvalidated()) return;
        if (!servletContext.getEffectiveSessionTrackingModes().contains(SessionTrackingMode.COOKIE)) return;
        String requested = req.getRequestedSessionId();
        if (!session.getId().equals(requested)) {
            VidocqSessionCookieConfig cfg = servletContext.sessionCookieConfigInternal();
            Cookie c = new Cookie(cfg.getName(), session.getId());
            String path = cfg.getPath();
            c.setPath(path != null && !path.isEmpty() ? path : "/".equals(contextPath) ? "/" : contextPath);
            if (cfg.getDomain() != null) c.setDomain(cfg.getDomain());
            if (cfg.getMaxAge() >= 0) c.setMaxAge(cfg.getMaxAge());
            c.setSecure(cfg.isSecureExplicit() ? cfg.isSecure() : req.isSecure());
            c.setHttpOnly(cfg.isHttpOnly());
            cfg.getAttributes().forEach(c::setAttribute);
            res.addHeaderInternal("Set-Cookie", CookieCodec.serializeSetCookie(c));
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
