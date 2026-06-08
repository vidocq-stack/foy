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
 * attributes expose the included path.</p>
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

        String fullUri = req.getContextPath().equals("/") ? target.path()
                : req.getContextPath() + target.path();
        req.setAttribute("jakarta.servlet.forward.request_uri", req.getRequestURI());
        req.setAttribute("jakarta.servlet.forward.context_path", req.getContextPath());
        req.setAttribute("jakarta.servlet.forward.servlet_path", req.getServletPath());
        req.setAttribute("jakarta.servlet.forward.path_info", req.getPathInfo());
        req.setAttribute("jakarta.servlet.forward.query_string", req.getQueryString());

        var wrappedReq = new ForwardedRequest(req, target);
        invoker.invoke(target, wrappedReq, res, DispatcherType.FORWARD);
        // Note : fullUri n'est pas exposé directement ; il est reconstituable via getRequestURI() du wrapper.
        assert fullUri != null;
    }

    @Override
    public void include(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        HttpServletRequest req = unwrapHttp(request);
        HttpServletResponse res = unwrapHttpResponse(response);
        if (req == null || res == null) {
            throw new ServletException("non-HTTP dispatch");
        }
        String fullUri = req.getContextPath().equals("/") ? target.path()
                : req.getContextPath() + target.path();
        req.setAttribute("jakarta.servlet.include.request_uri", fullUri);
        req.setAttribute("jakarta.servlet.include.context_path", req.getContextPath());
        req.setAttribute("jakarta.servlet.include.servlet_path", target.servletPath());
        req.setAttribute("jakarta.servlet.include.path_info", target.pathInfo());
        req.setAttribute("jakarta.servlet.include.query_string", target.queryString());

        var wrappedReq = new IncludedRequest(req, target);
        var wrappedRes = new IncludedResponse(res);
        invoker.invoke(target, wrappedReq, wrappedRes, DispatcherType.INCLUDE);
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
