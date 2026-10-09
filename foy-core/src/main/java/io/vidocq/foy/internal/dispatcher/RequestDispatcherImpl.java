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

import io.vidocq.foy.internal.bridge.ForwardedRequest;
import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.bridge.HttpServletResponseImpl;
import io.vidocq.foy.internal.bridge.IncludedRequest;
import io.vidocq.foy.internal.bridge.IncludedResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
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
 */
public final class RequestDispatcherImpl implements RequestDispatcher {

    public interface Invoker {
        /** Executes the dispatch: filter chain applicable + service() on the target servlet. */
        void invoke(DispatchTarget target, HttpServletRequest req, HttpServletResponse res,
                    DispatcherType type)
                throws IOException, ServletException;
    }

    private final DispatchTarget target;
    private final Invoker invoker;

    public RequestDispatcherImpl(DispatchTarget target, Invoker invoker) {
        this.target = target;
        this.invoker = invoker;
    }

    /** Dispatcher stub for a path without resources — forward/include emit 404. */
    public static RequestDispatcher notFound(String path) {
        return new RequestDispatcher() {
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
    public void forward(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        HttpServletRequest req = unwrapHttp(request);
        HttpServletResponse res = unwrapHttpResponse(response);
        if (req == null || res == null) {
            throw new ServletException("non-HTTP dispatch");
        }
        if (res.isCommitted()) {
            throw new IllegalStateException("response already committed");
        }
        res.resetBuffer();

        // Section 9.4.2: a named forward sets no attribute; a nested forward keeps the values of the
        // original request, so the attributes are only set when a previous forward has not.
        if (!target.named() && req.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) == null) {
            req.setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, req.getRequestURI());
            req.setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, req.getContextPath());
            req.setAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, req.getServletPath());
            req.setAttribute(RequestDispatcher.FORWARD_PATH_INFO, req.getPathInfo());
            req.setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, req.getQueryString());
            if (req.getAttribute(RequestDispatcher.FORWARD_MAPPING) == null) {
                req.setAttribute(RequestDispatcher.FORWARD_MAPPING, req.getHttpServletMapping());
            }
        }

        // Exceptions of the target reach the caller unchanged (no wrapping).
        invoker.invoke(target, new ForwardedRequest(req, target), res, DispatcherType.FORWARD);

        // Section 9.4: the response is committed and closed once the forward returns, unless the
        // target started async processing or the forward happens inside an include (the including
        // servlet still owns the response).
        if (!asyncEntered(req) && !insideInclude(response)) {
            HttpServletResponseImpl impl = unwrapImpl(response);
            if (impl != null) impl.closeAfterForward();
            else if (!res.isCommitted()) res.flushBuffer();
        }
    }

    @Override
    public void include(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        HttpServletRequest req = unwrapHttp(request);
        HttpServletResponse res = unwrapHttpResponse(response);
        if (req == null || res == null) {
            throw new ServletException("non-HTTP dispatch");
        }
        // The include.* attributes (none for a named include) and the merged parameters live in the
        // wrapper: they last for the include only. Exceptions of the target propagate unchanged.
        invoker.invoke(target, new IncludedRequest(req, target), new IncludedResponse(res),
                DispatcherType.INCLUDE);
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

    /** Unwrap via {@link jakarta.servlet.ServletRequestWrapper#getRequest()} until
     *  finding a {@link HttpServletRequest}. Allows a base (non-HTTP)
     *  {@link jakarta.servlet.ServletRequestWrapper} to trigger forward/include. */
    private static HttpServletRequest unwrapHttp(ServletRequest r) {
        while (r != null) {
            if (r instanceof HttpServletRequest h) return h;
            if (r instanceof jakarta.servlet.ServletRequestWrapper w) r = w.getRequest();
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
