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

import io.vidocq.foy.internal.dispatcher.DispatchTarget;
import io.vidocq.foy.internal.http.RequestPaths;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Wrapper for a redispatch initiated by {@link jakarta.servlet.AsyncContext#dispatch(String)}:
 * reports {@link DispatcherType#ASYNC} and the new {@code servletPath/pathInfo/queryString}.
 */
public final class AsyncDispatchRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;
    private final DispatchParameters.View parameters;
    private final ServletContext overrideContext;
    private final String overrideContextPath;

    public AsyncDispatchRequest(HttpServletRequest original, DispatchTarget target) {
        this(original, target, null, null);
    }

    /**
     * Built a wrapper for cross-context async dispatch: {@code overrideContext} is
     * the target {@link ServletContext} (different from the original context) and its contextPath
     * is substituted in {@link #getRequestURI()} and {@link #getContextPath()}.
     */
    public AsyncDispatchRequest(HttpServletRequest original, DispatchTarget target,
                                ServletContext overrideContext, String overrideContextPath) {
        super(original);
        this.target = target;
        this.parameters = new DispatchParameters.View(super::getParameterMap, target.queryString(),
                java.nio.charset.StandardCharsets.UTF_8);
        this.overrideContext = overrideContext;
        this.overrideContextPath = overrideContextPath;
    }

    @Override public String getParameter(String name) { return parameters.first(name); }
    @Override public String[] getParameterValues(String name) { return parameters.values(name); }
    @Override public java.util.Map<String, String[]> getParameterMap() { return parameters.map(); }
    @Override public java.util.Enumeration<String> getParameterNames() { return parameters.names(); }

    @Override public DispatcherType getDispatcherType() { return DispatcherType.ASYNC; }
    @Override public jakarta.servlet.http.HttpServletMapping getHttpServletMapping() {
        return target.mapping() != null ? target.mapping() : super.getHttpServletMapping();
    }
    @Override public String getServletPath() { return target.servletPath(); }
    @Override public String getPathInfo() { return target.pathInfo(); }
    @Override public String getQueryString() { return target.queryString(); }
    @Override public String getContextPath() {
        return overrideContextPath != null ? overrideContextPath : super.getContextPath();
    }
    @Override public ServletContext getServletContext() {
        return overrideContext != null ? overrideContext : super.getServletContext();
    }
    @Override public String getRequestURI() {
        String ctx = getContextPath();
        String path = RequestPaths.encodeUriPath(target.path());
        return ctx.equals("/") ? path : ctx + path;
    }
    @Override public StringBuffer getRequestURL() { return new StringBuffer(RequestUrls.of(this)); }

    DispatchTarget target() { return target; }
    // §2.3.3.3 : isAsyncStarted reste géré par la request originale
    // (false si consommé par dispatch, true si re-startAsync).
}
