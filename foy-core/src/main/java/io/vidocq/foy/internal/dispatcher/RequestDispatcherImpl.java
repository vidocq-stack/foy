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
package io.vidocq.foy.internal.dispatcher;

import io.vidocq.foy.internal.bridge.AsyncDispatchRequest;
import io.vidocq.foy.internal.bridge.ErrorDispatchRequest;
import io.vidocq.foy.internal.bridge.ForwardedRequest;
import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.bridge.HttpServletResponseImpl;
import io.vidocq.foy.internal.bridge.IncludedRequest;
import io.vidocq.foy.internal.bridge.IncludedResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * {@link RequestDispatcher} for forward/include.
 *
 * <p>Servlet 6.1 contract §9.4 (forward): the response must be uncommitted, the
 * buffer is cleared before dispatch, and {@code jakarta.servlet.forward.*}
 * attributes are set to the original values.</p>
 *
 * <p>Servlet 6.1 contract §9.3 (include): the primary response keeps its headers/status
 * (the wrapper ignores mutators), and {@code jakarta.servlet.include.*}
 * attributes expose the included path for the duration of the include.</p>
 *
 * <p>Named dispatchers (section 9.1) set no dispatch attribute and keep the caller's paths.</p>
 *
 * <p>The target receives the objects the caller passed (sections 6.2.2 and 9.1). Every application
 * wrapper the caller passes, HTTP or not, is kept: the container's dispatch wrapper goes under the
 * whole application chain, right above the first container object, through
 * {@link ServletRequestWrapper#setRequest} / {@link ServletResponseWrapper#setResponse}, and the
 * application's chain is restored once the dispatch returns or throws (as Tomcat's
 * {@code ApplicationDispatcher.wrapRequest} does).</p>
 */
public final class RequestDispatcherImpl implements io.vidocq.foy.spi.jsp.JspEngine.ForwardDispatcher {

    public interface Invoker {
        /** Executes the dispatch: filter chain applicable + service() on the target servlet. */
        void invoke(DispatchTarget target, ServletRequest req, ServletResponse res,
                    DispatcherType type)
                throws IOException, ServletException;
    }

    /**
     * The RequestDispatcher Javadoc requires the objects passed to forward/include to be the ones the
     * container passed to the calling servlet, or wrappers of them.
     */
    private static final String NOT_THE_CONTAINER_OBJECTS =
            "the request and response must be, or wrap, the objects the container passed (RequestDispatcher)";

    private final DispatchTarget target;
    private final Invoker invoker;

    public RequestDispatcherImpl(DispatchTarget target, Invoker invoker) {
        this.target = target;
        this.invoker = invoker;
    }

    /** Dispatcher stub for a path without resources — forward/include emit 404. */
    public static RequestDispatcher notFound(String path) {
        return new io.vidocq.foy.spi.jsp.JspEngine.ForwardDispatcher() {
            @Override public void forwardFromJsp(ServletRequest request, ServletResponse response)
                    throws ServletException, IOException {
                var forwarded = PageForwardResponse.wrap(response);
                var httpResponse = unwrapHttpResponse(forwarded);
                if (httpResponse == null) throw new ServletException("non-HTTP JSP forward");
                if (forwarded.isCommitted()) throw new IllegalStateException("response already committed");
                forwarded.resetBuffer();
                PageForwardResponse.sendError(forwarded, httpResponse, HttpServletResponse.SC_NOT_FOUND, "No resource at " + path);
                var http = containerRequest(request);
                if (http == null || !asyncEntered(http)) close(forwarded);
            }
            @Override public void forward(ServletRequest request, ServletResponse response)
                    throws IOException {
                if (response instanceof HttpServletResponse res) {
                    if (res.isCommitted()) throw new IllegalStateException("response already committed");
                    res.resetBuffer();
                    res.sendError(HttpServletResponse.SC_NOT_FOUND, "No resource at " + path);
                }
            }
            @Override public void include(ServletRequest request, ServletResponse response) {
                // Pas de contenu inclus — la cible n'existe pas.
            }
        };
    }

    @Override
    public void forwardFromJsp(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        forward(request, PageForwardResponse.wrap(response), true);
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        forward(request, response, false);
    }

    private void forward(ServletRequest request, ServletResponse response, boolean fromJsp)
            throws ServletException, IOException {
        HttpServletRequest req = containerRequest(request);
        HttpServletResponse res = unwrapHttpResponse(response);
        if (req == null || res == null) {
            throw new ServletException(NOT_THE_CONTAINER_OBJECTS);
        }
        if (res.isCommitted()) {
            throw new IllegalStateException("response already committed");
        }
        if (fromJsp) response.resetBuffer();
        else res.resetBuffer();

        // Exceptions of the target reach the caller unchanged (no wrapping). The forward.* attributes
        // (section 9.4.2) live in the ForwardedRequest, so they last for the forward only. Every
        // application wrapper is kept (section 6.2.2): the ForwardedRequest goes under the application
        // chain for the forward only. The response chain is passed as is.
        ForwardedRequest dispatched = new ForwardedRequest(req, target);
        ServletRequestWrapper hook = hookAbove(request);
        if (hook == null) {
            invoker.invoke(target, dispatched, response, DispatcherType.FORWARD);
        } else {
            hook.setRequest(dispatched);
            try {
                invoker.invoke(target, request, response, DispatcherType.FORWARD);
            } finally {
                hook.setRequest(req);
            }
        }

        // Section 9.4: the response is committed and closed once the forward returns, unless the
        // target started async processing or the forward happens inside an include (the including
        // servlet still owns the response).
        if (!asyncEntered(req) && (fromJsp || !insideInclude(response))) close(response);
    }

    /**
     * Closes the response handed to {@code forward}, application wrappers included, so their own
     * buffers reach the container (as Tomcat does), then commits and closes the container response.
     * A response whose error was triggered is left alone: the error page still has to write it.
     */
    private static void close(ServletResponse response) throws IOException {
        HttpServletResponseImpl impl = unwrapImpl(response);
        if (impl != null && impl.isErrorTriggered()) return;
        try {
            response.getWriter().close();
        } catch (IllegalStateException streamInUse) {
            try {
                response.getOutputStream().close();
            } catch (IllegalStateException ignored) {
                // neither can be obtained: nothing buffered by a wrapper to flush
            }
        }
        if (impl != null) impl.closeAfterForward();
        else if (!response.isCommitted()) response.flushBuffer();
    }

    @Override
    public void include(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        HttpServletRequest req = containerRequest(request);
        HttpServletResponse res = containerResponse(response);
        if (req == null || res == null) {
            throw new ServletException(NOT_THE_CONTAINER_OBJECTS);
        }
        // The include.* attributes (none for a named include) and the merged parameters live in the
        // wrapper: they last for the include only. Exceptions of the target propagate unchanged.
        // Every application wrapper is kept (section 6.2.2): the dispatch wrappers go under the
        // application chains for the include only.
        IncludedRequest dispatchedRequest = new IncludedRequest(req, target);
        IncludedResponse dispatchedResponse = new IncludedResponse(res);
        ServletRequestWrapper requestHook = hookAbove(request);
        ServletResponseWrapper responseHook = hookAbove(response);
        if (requestHook != null) requestHook.setRequest(dispatchedRequest);
        try {
            if (responseHook != null) responseHook.setResponse(dispatchedResponse);
            try {
                invoker.invoke(target,
                        requestHook == null ? dispatchedRequest : request,
                        responseHook == null ? dispatchedResponse : response,
                        DispatcherType.INCLUDE);
            } finally {
                if (responseHook != null) responseHook.setResponse(res);
            }
        } finally {
            if (requestHook != null) requestHook.setRequest(req);
        }
    }

    /**
     * The application wrapper whose wrapped request is the first container request of the chain, or
     * null when the outer request is itself a container request (or the chain holds none).
     */
    private static ServletRequestWrapper hookAbove(ServletRequest outer) {
        ServletRequest r = outer;
        while (r instanceof ServletRequestWrapper w && !isContainerRequest(r)) {
            ServletRequest inner = w.getRequest();
            if (isContainerRequest(inner)) return w;
            r = inner;
        }
        return null;
    }

    /**
     * The application wrapper whose wrapped response is the first container response of the chain,
     * or null when the outer response is itself a container response (or the chain holds none).
     */
    private static ServletResponseWrapper hookAbove(ServletResponse outer) {
        ServletResponse r = outer;
        while (r instanceof ServletResponseWrapper w && !isContainerResponse(r)) {
            ServletResponse inner = w.getResponse();
            if (isContainerResponse(inner)) return w;
            r = inner;
        }
        return null;
    }

    /** The first container request of the chain, or null when it holds none. */
    private static HttpServletRequest containerRequest(ServletRequest outer) {
        ServletRequest r = outer;
        while (r != null) {
            if (isContainerRequest(r)) return (HttpServletRequest) r;
            if (r instanceof ServletRequestWrapper w) r = w.getRequest();
            else return null;
        }
        return null;
    }

    /** The first container response of the chain, or null when it holds none. */
    private static HttpServletResponse containerResponse(ServletResponse outer) {
        ServletResponse r = outer;
        while (r != null) {
            if (isContainerResponse(r)) return (HttpServletResponse) r;
            if (r instanceof ServletResponseWrapper w) r = w.getResponse();
            else return null;
        }
        return null;
    }

    /** The request types the container creates, as opposed to the application's wrappers. */
    private static boolean isContainerRequest(ServletRequest r) {
        return r instanceof HttpServletRequestImpl || r instanceof ForwardedRequest
                || r instanceof IncludedRequest || r instanceof AsyncDispatchRequest
                || r instanceof ErrorDispatchRequest;
    }

    /** The response types the container creates, as opposed to the application's wrappers. */
    private static boolean isContainerResponse(ServletResponse r) {
        return r instanceof HttpServletResponseImpl || r instanceof IncludedResponse;
    }

    /**
     * True when the target put the request into async mode. {@code isAsyncStarted()} is not enough:
     * it turns false as soon as {@code AsyncContext.dispatch()} is called, and the pending async
     * dispatch must still be able to write the response.
     */
    private static boolean asyncEntered(HttpServletRequest req) {
        ServletRequest r = req;
        while (r != null) {
            if (r instanceof HttpServletRequestImpl impl) return impl.asyncContextInternal() != null;
            if (r instanceof jakarta.servlet.ServletRequestWrapper w) r = w.getRequest();
            else break;
        }
        return req.isAsyncStarted();
    }

    private static boolean insideInclude(ServletResponse r) {
        while (r != null) {
            if (r instanceof IncludedResponse) return true;
            if (r instanceof jakarta.servlet.ServletResponseWrapper w) r = w.getResponse();
            else return false;
        }
        return false;
    }

    private static HttpServletResponseImpl unwrapImpl(ServletResponse r) {
        while (r != null) {
            if (r instanceof HttpServletResponseImpl i) return i;
            if (r instanceof jakarta.servlet.ServletResponseWrapper w) r = w.getResponse();
            else return null;
        }
        return null;
    }

    private static HttpServletResponse unwrapHttpResponse(ServletResponse r) {
        while (r != null) {
            if (r instanceof HttpServletResponse h) return h;
            if (r instanceof jakarta.servlet.ServletResponseWrapper w) r = w.getResponse();
            else return null;
        }
        return null;
    }
}
