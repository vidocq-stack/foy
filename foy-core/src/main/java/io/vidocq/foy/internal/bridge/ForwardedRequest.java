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
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * {@link HttpServletRequestWrapper} used for a
 * {@link RequestDispatcher#forward forward}: exposes the target's request URI, URL, servletPath,
 * pathInfo and queryString, merges the dispatch query into the parameters (section 9.1.1), and
 * reports {@link DispatcherType#FORWARD}. A named forward (section 9.4.2) keeps the caller's paths,
 * query and parameters.
 *
 * <p>The {@code jakarta.servlet.forward.*} attributes (section 9.4.2) are held by this wrapper: they
 * describe the original request, a nested forward inherits those of the first forward, a named
 * forward sets none, and they disappear when the forward returns. The {@code jakarta.servlet.include.*}
 * attributes of an enclosing include are hidden from the forward target.</p>
 */
public final class ForwardedRequest extends HttpServletRequestWrapper {

    private static final String FORWARD_PREFIX = "jakarta.servlet.forward.";
    private static final String INCLUDE_PREFIX = "jakarta.servlet.include.";

    private final DispatchTarget target;
    private final DispatchParameters.View parameters;
    /** The forward attributes; empty when inherited (nested forward) or not set (named forward). */
    private final Map<String, Object> forwardAttributes = new LinkedHashMap<>();

    public ForwardedRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
        this.parameters = new DispatchParameters.View(super::getParameterMap,
                target.named() ? null : target.queryString(), StandardCharsets.UTF_8);
        if (!target.named() && original.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) == null) {
            forwardAttributes.put(RequestDispatcher.FORWARD_REQUEST_URI, original.getRequestURI());
            forwardAttributes.put(RequestDispatcher.FORWARD_CONTEXT_PATH, original.getContextPath());
            forwardAttributes.put(RequestDispatcher.FORWARD_SERVLET_PATH, original.getServletPath());
            forwardAttributes.put(RequestDispatcher.FORWARD_PATH_INFO, original.getPathInfo());
            forwardAttributes.put(RequestDispatcher.FORWARD_QUERY_STRING, original.getQueryString());
            forwardAttributes.put(RequestDispatcher.FORWARD_MAPPING, original.getHttpServletMapping());
        }
    }

    @Override public Object getAttribute(String name) {
        if (name != null && name.startsWith(INCLUDE_PREFIX)) return null;
        if (forwardAttributes.containsKey(name)) return forwardAttributes.get(name);
        return super.getAttribute(name);
    }
    @Override public Enumeration<String> getAttributeNames() {
        var names = new LinkedHashSet<String>();
        super.getAttributeNames().asIterator().forEachRemaining(n -> {
            if (!n.startsWith(INCLUDE_PREFIX)) names.add(n);
        });
        forwardAttributes.forEach((k, v) -> { if (v == null) names.remove(k); else names.add(k); });
        return Collections.enumeration(names);
    }
    @Override public void setAttribute(String name, Object value) {
        if (ownsForward(name)) forwardAttributes.put(name, value);
        else super.setAttribute(name, value);
    }
    @Override public void removeAttribute(String name) {
        if (ownsForward(name)) forwardAttributes.put(name, null);
        else super.removeAttribute(name);
    }

    private boolean ownsForward(String name) {
        return !forwardAttributes.isEmpty() && name != null && name.startsWith(FORWARD_PREFIX);
    }

    @Override public String getRequestURI() {
        if (target.named()) return super.getRequestURI();
        return getContextPath() + target.path();
    }
    @Override public StringBuffer getRequestURL() {
        if (target.named()) return super.getRequestURL();
        StringBuffer sb = new StringBuffer();
        String scheme = getScheme();
        int port = getServerPort();
        sb.append(scheme).append("://").append(getServerName());
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            sb.append(':').append(port);
        }
        return sb.append(getRequestURI());
    }
    @Override public String getServletPath() { return target.named() ? super.getServletPath() : target.servletPath(); }
    @Override public String getPathInfo() { return target.named() ? super.getPathInfo() : target.pathInfo(); }
    @Override public String getQueryString() { return target.named() ? super.getQueryString() : target.queryString(); }
    @Override public DispatcherType getDispatcherType() { return DispatcherType.FORWARD; }
    /** The target's mapping; a named forward (no mapping) keeps the caller's. */
    @Override public HttpServletMapping getHttpServletMapping() {
        return target.named() ? super.getHttpServletMapping() : target.mapping();
    }
    /** Section 9.1: a relative path is resolved against the forwarded path. */
    @Override public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null || path.startsWith("/") || target.named()) return super.getRequestDispatcher(path);
        return super.getRequestDispatcher(DispatchParameters.resolveRelative(getServletPath(), getPathInfo(), path));
    }

    @Override public String getParameter(String name) { return parameters.first(name); }
    @Override public String[] getParameterValues(String name) { return parameters.values(name); }
    @Override public Map<String, String[]> getParameterMap() { return parameters.map(); }
    @Override public Enumeration<String> getParameterNames() { return parameters.names(); }
}
