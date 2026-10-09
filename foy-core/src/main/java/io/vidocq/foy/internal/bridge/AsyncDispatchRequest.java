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
    private final java.util.Map<String, String[]> dispatchParams;
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
        this.dispatchParams = parseQuery(target.queryString());
        this.overrideContext = overrideContext;
        this.overrideContextPath = overrideContextPath;
    }

    private static java.util.Map<String, String[]> parseQuery(String qs) {
        if (qs == null || qs.isEmpty()) return null;
        var out = new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.isEmpty()) continue;
            k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8);
            v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            out.computeIfAbsent(k, _ -> new java.util.ArrayList<>()).add(v);
        }
        var result = new java.util.LinkedHashMap<String, String[]>();
        for (var e : out.entrySet()) result.put(e.getKey(), e.getValue().toArray(new String[0]));
        return result;
    }

    @Override public String getParameter(String name) {
        if (dispatchParams != null && dispatchParams.containsKey(name)) {
            String[] v = dispatchParams.get(name);
            return v.length == 0 ? null : v[0];
        }
        return super.getParameter(name);
    }
    @Override public String[] getParameterValues(String name) {
        if (dispatchParams != null && dispatchParams.containsKey(name)) return dispatchParams.get(name);
        return super.getParameterValues(name);
    }
    @Override public java.util.Map<String, String[]> getParameterMap() {
        if (dispatchParams == null) return super.getParameterMap();
        var out = new java.util.LinkedHashMap<>(super.getParameterMap());
        out.putAll(dispatchParams);
        return java.util.Collections.unmodifiableMap(out);
    }
    @Override public java.util.Enumeration<String> getParameterNames() {
        if (dispatchParams == null) return super.getParameterNames();
        var union = new java.util.LinkedHashSet<String>();
        super.getParameterNames().asIterator().forEachRemaining(union::add);
        union.addAll(dispatchParams.keySet());
        return java.util.Collections.enumeration(union);
    }

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
        return ctx.equals("/") ? target.path() : ctx + target.path();
    }
    // §2.3.3.3 : isAsyncStarted reste géré par la request originale
    // (false si consommé par dispatch, true si re-startAsync).
}
