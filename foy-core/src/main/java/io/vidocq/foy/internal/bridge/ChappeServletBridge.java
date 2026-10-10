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
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.RequestContext;
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
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.security.SecurityConstraintEnforcer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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
    private final VidocqServletContext servletContext;
    private final SessionManager sessionManager;
    private final String contextPath;

    public ChappeServletBridge(ServletDispatcher dispatcher,
                               FilterRegistry filterRegistry,
                               VidocqServletContext servletContext,
                               SessionManager sessionManager,
                               String contextPath) {
        this.dispatcher = dispatcher;
        this.filterRegistry = filterRegistry;
        this.servletContext = servletContext;
        this.sessionManager = sessionManager;
        // The root context path is "" (section 3.5); "/" is accepted as its alias.
        this.contextPath = contextPath == null || "/".equals(contextPath) ? "" : contextPath;
        servletContext.setDispatchInfrastructure(new DispatchResolver(dispatcher), this);
    }

    /** Construction without sessions. */
    public ChappeServletBridge(ServletDispatcher dispatcher,
                               FilterRegistry filterRegistry,
                               VidocqServletContext servletContext,
                               String contextPath) {
        this(dispatcher, filterRegistry, servletContext, null, contextPath);
    }

    /** Minimal construction (compat tests). */
    public ChappeServletBridge(ServletDispatcher dispatcher,
                               VidocqServletContext servletContext,
                               String contextPath) {
        this(dispatcher, new FilterRegistry(List.of()), servletContext, null, contextPath);
    }

    /**
     * Runs the servlet pipeline for {@code request} on its own virtual thread and parks chappe's
     * thread until the response head is known.
     *
     * <p>Thread model: chappe's connection (or HTTP/2 stream) thread creates the response, starts
     * the pipeline thread {@code foy-request-<n>} with chappe's {@link RequestContext#CURRENT}
     * re-bound (a {@link ScopedValue} is not inherited by a plain thread) and waits on a
     * {@link ResponseHead}. The head is settled exactly once:</p>
     * <ul>
     *   <li>at the first real commit (buffer overflow, flush), from whichever thread writes
     *       (the pipeline thread or an async one): chappe gets the head and a body fed by a
     *       {@link ResponsePipe}, and writes while the servlet keeps producing;</li>
     *   <li>at the end of a pipeline that never committed: the whole buffered response, as before;</li>
     *   <li>failed, when the pipeline throws before any commit: chappe answers its plain 500.</li>
     * </ul>
     * <p>A committed pipeline ends its body ({@code finishBody}) once the request is over, or
     * aborts it when an exception escapes after the commit, so chappe drops the connection.</p>
     */
    @Override
    public Response handle(Request request) throws Exception {
        var head = new ResponseHead();
        var requests = new ArrayList<HttpServletRequestImpl>(1);
        var res = new HttpServletResponseImpl();
        res.setDefaultCharacterEncoding(servletContext.configuredResponseCharacterEncoding());
        res.bindCommitTarget(r -> commitHead(request, requests, r, head));
        Runnable pipeline = () -> runPipeline(request, requests, res, head);
        RequestContext context = RequestContext.CURRENT.isBound() ? RequestContext.CURRENT.get() : null;
        Thread.ofVirtual().name("foy-request-" + PIPELINE_THREADS.incrementAndGet()).start(context == null
                ? pipeline
                : () -> ScopedValue.where(RequestContext.CURRENT, context).run(pipeline));
        return head.await();
    }

    private static final AtomicLong PIPELINE_THREADS =
            new AtomicLong();

    /** The pipeline thread's body: every way out settles the head or ends the live body. */
    private void runPipeline(Request request, List<HttpServletRequestImpl> requests,
                             HttpServletResponseImpl res, ResponseHead head) {
        Response response = null;
        Throwable failure = null;
        try {
            response = handle(request, requests, res);
        } catch (Throwable t) {
            failure = t;
        } finally {
            // End of request processing (asynchronous processing included, awaited by handle):
            // the sessions used by the request become idle, and their last-accessed time moves.
            for (HttpServletRequestImpl r : requests) {
                try { r.endSessionAccess(); }
                catch (RuntimeException e) { LOG.log(System.Logger.Level.WARNING, "ending the session access failed", e); }
            }
        }
        try {
            if (!res.isStreaming()) {
                // Never committed: the whole response goes out now (or chappe's 500 on failure).
                if (failure != null) head.fail(failure);
                else if (response != null) head.complete(response);
                return;
            }
            if (failure != null) {
                LOG.log(System.Logger.Level.ERROR,
                        "unhandled exception after the response was committed; connection aborted", failure);
                res.abortBody(failure);
                return;
            }
            try {
                res.finishBody();
            } catch (IOException e) {
                // The client is gone while the rest of the body was pushed: nothing left to deliver.
                res.abortBody(e);
            }
        } finally {
            // Last guard: chappe's thread must never park on a head nobody settles.
            if (!head.isDone()) head.fail(new IllegalStateException("the response head was never settled"));
        }
    }

    /**
     * The commit target: runs at the first real commit, on the committing thread. The session
     * cookie is attached while the headers can still change, then the head is handed to chappe
     * with a live body, or with none for a response that carries no body on the wire.
     */
    private void commitHead(Request request, List<HttpServletRequestImpl> requests,
                            HttpServletResponseImpl res, ResponseHead head) throws IOException {
        ResponsePipe pipe = null;
        Response response;
        try {
            if (!requests.isEmpty()) maybeAttachSessionCookie(requests.getLast(), res);
            Body body;
            int status = res.getStatus();
            if (request.method() == HttpMethod.HEAD || status == 204 || status == 304) {
                res.suppressBody();
                body = Body.of(InputStream.nullInputStream(), res.declaredContentLength());
            } else {
                pipe = res.startStreaming();
                body = Body.of(pipe.reader(), res.declaredContentLength());
            }
            response = toChappeResponse(res, body);
        } catch (RuntimeException | Error e) {
            // The head cannot be built: chappe answers its plain 500 instead of waiting forever,
            // and the servlet's writes fail from now on.
            var failure = new IOException("committing the response failed", e);
            head.fail(failure);
            if (pipe == null) res.startStreaming();
            res.abortBody(failure);
            throw failure;
        }
        if (!head.complete(response) && pipe != null) {
            // chappe stopped waiting (server stop): fail the servlet's next write.
            try { pipe.reader().close(); } catch (IOException ignored) {}
        }
    }

    /**
     * The single exit of {@link #handle(Request, List, HttpServletResponseImpl)}: a response
     * committed for real already handed its head to chappe ({@code null} is returned and the
     * pipeline ends the body); otherwise {@code uncommitted} builds the whole response.
     */
    private static Response respond(HttpServletResponseImpl res, Supplier<Response> uncommitted) {
        return res.isStreaming() ? null : uncommitted.get();
    }

    /** {@link #respond} for a failure: the plain 500, or an aborted connection once committed. */
    private Response failed(HttpServletResponseImpl res, HttpServletRequestImpl req, Throwable e) {
        if (res.isStreaming()) {
            LOG.log(System.Logger.Level.ERROR,
                    "unhandled exception after the response was committed; connection aborted", e);
            res.abortBody(e);
        }
        return respond(res, () -> error(req, e));
    }

    /** The end of a request that reached the application: session cookie, then the response. */
    private Response completed(HttpServletRequestImpl req, HttpServletResponseImpl res) {
        return respond(res, () -> {
            maybeAttachSessionCookie(req, res);
            return toChappeResponse(res);
        });
    }

    private Response handle(Request request, List<HttpServletRequestImpl> requests,
                            HttpServletResponseImpl res) throws Exception {
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
            // Section 10.5: WEB-INF/ and META-INF/ are never exposed to a client request, whichever
            // servlet the path would map to (a "*.jsp" servlet included) and without the directory
            // redirect; forward, include, error and async dispatches may still target them.
            if (ResourcePaths.isProtected(path)) return rejected(request, requests, res, 404);
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
                // A client tracked by URL keeps its session across the redirect.
                redirected.setUrlSessionId(finalUrlSessionId);
                redirected.accessRequestedSession();
                try { res.sendRedirect(res.encodeRedirectURL(redirect)); } catch (IOException ignored) {}
                return completed(redirected, res);
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
            req.accessRequestedSession();
            List<Filter> filters = filterRegistry.chainFor(path, DispatcherType.REQUEST);
            if (filters.isEmpty()) {
                // Pas de mapping ni de filtre : 404 + error-page si mappée (§9.9.1).
                try { res.sendError(404); } catch (IOException ignored) {}
                try { maybeHandleError(req, res, null, null); }
                catch (ServletException e) { return failed(res, req, e); }
                if (!errorPageHandled(req)) return respond(res, ChappeServletBridge::notFound);
                return respond(res, () -> toChappeResponse(res));
            }

            registry.fireRequestInitialized(servletContext, req);
            try {
                new VidocqFilterChain(filters, null).doFilter(req, res);
            } catch (ServletException e) {
                registry.fireRequestDestroyed(servletContext, req);
                return failed(res, req, e);
            }
            registry.fireRequestDestroyed(servletContext, req);
            return completed(req, res);
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
        // §2.3.3.3: async only when the servlet and every filter of the chain support it. The REQUEST
        // chain is computed once and shared with the invocation.
        FilterRegistry.Chain requestChain = filterRegistry.chain(path, DispatcherType.REQUEST, m.servletName());
        req.setAsyncSupported(m.asyncSupported() && requestChain.asyncSupported());
        req.setUrlSessionId(finalUrlSessionId);
        req.accessRequestedSession();

        registry.fireRequestInitialized(servletContext, req);
        Throwable thrown = null;
        try {
            var enforcer = new SecurityConstraintEnforcer(
                    servletContext.securityProvider());
            if (!enforcer.enforce(m.security(), req, res)) {
                registry.fireRequestDestroyed(servletContext, req);
                return completed(req, res);
            }
            invoke(target, req, res, DispatcherType.REQUEST, requestChain);
            thrown = awaitAsyncIfStarted(req, res);
        } catch (ServletException | IOException | RuntimeException e) {
            thrown = e;
        }
        registry.fireRequestDestroyed(servletContext, req);

        try {
            maybeHandleError(req, res, thrown, target.servletName());
        } catch (ServletException e) {
            return failed(res, req, e);
        }
        if (thrown != null && !errorPageHandled(req)) {
            return failed(res, req, thrown);
        }
        return completed(req, res);
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
     * must not become the protocol-relative {@code //dir/}. The caller passes it through
     * {@code encodeRedirectURL}, so a session tracked by URL is carried over.</p>
     *
     * <p>Directory detection uses {@code ServletContext#getResourcePaths}, the only directory probe
     * the resource-provider SPI offers; it is asked only for a path the default servlet would
     * otherwise answer 404 or serve as a file, and answers {@code null} at once for a file.</p>
     */
    private String welcomeRedirect(String rawPath, String path, String query) {
        boolean contextRoot = !contextPath.isEmpty() && rawPath.equals(contextPath);
        if (!contextRoot && (path.endsWith("/") || servletContext.getResourcePaths(path + "/") == null)) {
            return null;
        }
        String location = contextRoot ? contextPath + "/" : contextPath + encodePath(path) + "/";
        return query == null || query.isEmpty() ? location : location + "?" + query;
    }

    /**
     * Percent-encodes (UTF-8) every character of a decoded path other than an RFC 3986 pchar or '/';
     * {@code ';'} is encoded too, since a literal one would start a path parameter.
     */
    private static String encodePath(String decoded) {
        var out = new StringBuilder(decoded.length() + 8);
        for (byte b : decoded.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean plain = c < 0x80 && (Character.isLetterOrDigit(c) || "-._~!$&'()*+,=:@/".indexOf(c) >= 0);
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
            // The welcome re-entry is a client REQUEST: a candidate under WEB-INF/ or META-INF/ is
            // never resolved, neither as a static resource nor through a servlet (section 10.5).
            if (!ResourcePaths.isServable(candidate)) continue;
            if (servletContext.getResourcePaths(candidate + "/") == null) {
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
        catch (ServletException e) { return failed(res, null, e); }
        if (errorPageHandled(req)) return respond(res, () -> toChappeResponse(res));
        if (status == 404) return respond(res, ChappeServletBridge::notFound);
        return respond(res, () -> Response.builder()
                .status(StatusCode.of(status))
                .header("Content-Type", "text/plain")
                .body(Body.of("Bad Request".getBytes(StandardCharsets.US_ASCII)))
                .build());
    }

    /**
     * The request an error dispatch hands its target. The container default servlet finds the
     * resource from the servlet path, so a static error page (for example {@code /error.html})
     * sees the location's paths; other targets keep the original request (unchanged behaviour,
     * to be generalised: BUG-20261009-03).
     */
    private static HttpServletRequest errorTargetRequest(HttpServletRequestImpl req, DispatchTarget target) {
        if (!(target.servlet() instanceof DefaultServlet)) return req;
        return new HttpServletRequestWrapper(req) {
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
            ServletContext targetCtx = ac.dispatchContext();
            try {
                if (targetCtx instanceof VidocqServletContext vctx
                        && vctx != servletContext) {
                    // §2.3.3.3 + §9.4 : cross-context async dispatch — route vers le bridge
                    // cible en utilisant son resolver/invoker.
                    // AsyncContext#dispatch(ServletContext, String): the path is relative to the target context.
                    String tgtCtxPath = vctx.getContextPath();
                    String relative = dispatchPath;
                    String qs = null;
                    int q = relative.indexOf('?');
                    if (q >= 0) { qs = relative.substring(q + 1); relative = relative.substring(0, q); }
                    var resolver = vctx.dispatchResolver();
                    var invoker = vctx.dispatchInvoker();
                    if (resolver == null || invoker == null) break;
                    var target = resolver.resolve(relative).orElse(null);
                    if (target == null) break;
                    if (qs != null) target = target.withQueryString(qs);
                    setAsyncAttributes(req);
                    var wrapped = new AsyncDispatchRequest(req, target, vctx, tgtCtxPath);
                    req.clearAsyncContext();
                    req.setAsyncSupported(true); // §2.3.3.3: an async dispatch starts a new cycle
                    invoker.invoke(target, wrapped, res, DispatcherType.ASYNC);
                } else {
                    // AsyncContext#dispatch(String): the path is relative to this context.
                    String relative = dispatchPath;
                    String qs = null;
                    int q = relative.indexOf('?');
                    if (q >= 0) { qs = relative.substring(q + 1); relative = relative.substring(0, q); }
                    var target = new DispatchResolver(dispatcher).resolve(relative).orElse(null);
                    if (target == null) break;
                    if (qs != null) target = target.withQueryString(qs);
                    setAsyncAttributes(req);
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
        if (ac != null && ac.timedOut() && !res.isCommitted() && !res.hasContent()) {
            try { res.sendError(503, "async timeout"); }
            catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Servlet 6.1 section 9.4 / {@code AsyncContext.ASYNC_*}: before an async dispatch, the
     * original request's URI, context path, servlet path, path info, query string and mapping are
     * published as {@code jakarta.servlet.async.*}. {@code req} is always the original request, so
     * repeated dispatches keep exposing the first request's values. A {@code null} value (no path
     * info, no query string) leaves its attribute unset.
     */
    private static void setAsyncAttributes(HttpServletRequestImpl req) {
        req.setAttribute(AsyncContext.ASYNC_REQUEST_URI, req.getRequestURI());
        req.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, req.getContextPath());
        req.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, req.getServletPath());
        if (req.getPathInfo() != null) req.setAttribute(AsyncContext.ASYNC_PATH_INFO, req.getPathInfo());
        if (req.getQueryString() != null) req.setAttribute(AsyncContext.ASYNC_QUERY_STRING, req.getQueryString());
        req.setAttribute(AsyncContext.ASYNC_MAPPING, req.getHttpServletMapping());
    }

    private void maybeHandleError(HttpServletRequestImpl req, HttpServletResponseImpl res,
                                  Throwable thrown, String servletName) throws ServletException {
        if (thrown != null && res.isCommitted() && !res.isErrorTriggered()) {
            // The response was already committed by the servlet: no error page can be dispatched and
            // the committed content stands. Do not lose the exception.
            LOG.log(System.Logger.Level.ERROR,
                    "exception after the response was committed; no error page dispatched", thrown);
            req.setAttribute("jakarta.servlet.error.handled", Boolean.TRUE);
            // A live body cannot end normally: the client would take a partial body for a whole
            // one. Abort it, so chappe drops the connection.
            if (res.isStreaming()) res.abortBody(thrown);
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
            while (root.getCause() != null && !(root instanceof UnavailableException)) {
                root = root.getCause();
            }
            if (root instanceof UnavailableException ue) {
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
            if (thrown instanceof UnavailableException
                    || (thrown != null && thrown.getCause() instanceof UnavailableException)) {
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
        invoke(target, req, res, type, filterRegistry.chain(filterPath, type, target.servletName()));
    }

    private void invoke(DispatchTarget target, HttpServletRequest req, HttpServletResponse res,
                        DispatcherType type, FilterRegistry.Chain chain) throws IOException, ServletException {
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

    private static HttpServletRequestImpl unwrapImpl(ServletRequest r) {
        while (r != null) {
            if (r instanceof HttpServletRequestImpl i) return i;
            if (r instanceof ServletRequestWrapper w) r = w.getRequest();
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
        String cookie = sessionCookieHeader(req);
        if (cookie != null) res.addHeaderInternal("Set-Cookie", cookie);
    }

    /** The {@code Set-Cookie} value {@link #maybeAttachSessionCookie} emits, or {@code null}. */
    private String sessionCookieHeader(HttpServletRequestImpl req) {
        HttpSessionImpl session = req.boundSession();
        if (session == null || session.isInvalidated()) return null;
        if (!servletContext.getEffectiveSessionTrackingModes().contains(SessionTrackingMode.COOKIE)) return null;
        String requested = req.getRequestedSessionId();
        if (session.getId().equals(requested)) return null;
        VidocqSessionCookieConfig cfg = servletContext.sessionCookieConfigInternal();
        Cookie c = new Cookie(cfg.getName(), session.getId());
        String path = cfg.getPath();
        c.setPath(path != null && !path.isEmpty() ? path : contextPath.isEmpty() ? "/" : contextPath);
        if (cfg.getDomain() != null) c.setDomain(cfg.getDomain());
        if (cfg.getMaxAge() >= 0) c.setMaxAge(cfg.getMaxAge());
        c.setSecure(cfg.isSecureExplicit() ? cfg.isSecure() : req.isSecure());
        c.setHttpOnly(cfg.isHttpOnly());
        cfg.getAttributes().forEach(c::setAttribute);
        return CookieCodec.serializeSetCookie(c);
    }

    /** A response that never committed: its whole buffered body, sent at once. */
    static Response toChappeResponse(HttpServletResponseImpl res) {
        return toChappeResponse(res, Body.of(res.bodyBytes()));
    }

    /**
     * The single place where a servlet response becomes a chappe {@link Response} (buffered at
     * the end of the request, or live at the first commit). Framing is the container's: an
     * application {@code Transfer-Encoding} is never passed on (chappe would then send the body
     * unframed), and a streamed body carries its declared length as the body length rather than as
     * a {@code Content-Length} header, which chappe would otherwise send next to its own chunking.
     */
    static Response toChappeResponse(HttpServletResponseImpl res, Body body) {
        var builder = Response.builder()
                .status(StatusCode.of(res.getStatus()))
                .body(body);
        boolean streamed = res.isStreaming();
        for (Map.Entry<String, List<String>> e : res.allHeaders().entrySet()) {
            String name = e.getKey();
            if ("Transfer-Encoding".equalsIgnoreCase(name)) continue;
            if (streamed && "Content-Length".equalsIgnoreCase(name)) continue;
            for (String v : e.getValue()) {
                builder.header(name, v);
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

    /**
     * The plain 500 answered when no error page handled {@code e}. The body is generic: the
     * exception and its message are logged, never echoed to the client. The session cookie the
     * request would have emitted is kept.
     */
    private Response error(HttpServletRequestImpl req, Throwable e) {
        LOG.log(System.Logger.Level.ERROR, "unhandled exception while serving the request", e);
        var builder = Response.builder()
                .status(StatusCode.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "text/plain");
        String cookie = req == null ? null : sessionCookieHeader(req);
        if (cookie != null) builder.header("Set-Cookie", cookie);
        return builder.body(Body.of("Internal Server Error".getBytes(StandardCharsets.US_ASCII)))
                .build();
    }
}
