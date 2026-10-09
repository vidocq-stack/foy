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
import java.util.Enumeration;
import java.util.Map;

/**
 * {@link HttpServletRequestWrapper} used for a
 * {@link RequestDispatcher#forward forward}: exposes the target's request URI, URL, servletPath,
 * pathInfo and queryString, merges the dispatch query into the parameters (section 9.1.1), and
 * reports {@link DispatcherType#FORWARD}. A named forward (section 9.4.2) keeps the caller's paths,
 * query and parameters.
 *
 * <p>The original request receives {@code jakarta.servlet.forward.*}
 * attributes in {@link io.vidocq.foy.internal.dispatcher.RequestDispatcherImpl}
 * before this wrapper is invoked.</p>
 */
public final class ForwardedRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;
    private final DispatchParameters.View parameters;

    public ForwardedRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
        this.parameters = new DispatchParameters.View(super::getParameterMap,
                target.named() ? null : target.queryString(), StandardCharsets.UTF_8);
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
