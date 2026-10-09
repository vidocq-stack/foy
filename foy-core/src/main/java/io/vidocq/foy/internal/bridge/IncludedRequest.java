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
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Wrapper for an include (Servlet 6.1 section 9.3): the path methods reflect the original
 * request, the include query is merged into the parameters (section 9.1.1), and the
 * {@code jakarta.servlet.include.*} attributes are held by this wrapper, so they exist for the
 * duration of the include only and the caller's values (or their absence) come back when it
 * returns. A named include (section 9.3.1) sets no attribute and adds no parameter.
 */
public final class IncludedRequest extends HttpServletRequestWrapper {

    private static final String PREFIX = "jakarta.servlet.include.";

    private final DispatchTarget target;
    private final DispatchParameters.View parameters;
    /** The include attributes; a {@code null} value hides the caller's attribute of that name. */
    private final Map<String, Object> includeAttributes = new LinkedHashMap<>();

    public IncludedRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
        this.parameters = new DispatchParameters.View(super::getParameterMap,
                target.named() ? null : target.queryString(), StandardCharsets.UTF_8);
        if (!target.named()) {
            String ctx = original.getContextPath();
            includeAttributes.put(RequestDispatcher.INCLUDE_REQUEST_URI, ctx + target.path());
            includeAttributes.put(RequestDispatcher.INCLUDE_CONTEXT_PATH, ctx);
            includeAttributes.put(RequestDispatcher.INCLUDE_SERVLET_PATH, target.servletPath());
            includeAttributes.put(RequestDispatcher.INCLUDE_PATH_INFO, target.pathInfo());
            includeAttributes.put(RequestDispatcher.INCLUDE_QUERY_STRING, target.queryString());
            includeAttributes.put(RequestDispatcher.INCLUDE_MAPPING, target.mapping());
        }
    }

    DispatchTarget target() { return target; }

    @Override public DispatcherType getDispatcherType() { return DispatcherType.INCLUDE; }

    @Override public Object getAttribute(String name) {
        if (includeAttributes.containsKey(name)) return includeAttributes.get(name);
        return super.getAttribute(name);
    }
    @Override public Enumeration<String> getAttributeNames() {
        if (includeAttributes.isEmpty()) return super.getAttributeNames();
        var names = new LinkedHashSet<String>();
        super.getAttributeNames().asIterator().forEachRemaining(names::add);
        includeAttributes.forEach((k, v) -> { if (v == null) names.remove(k); else names.add(k); });
        return Collections.enumeration(names);
    }
    @Override public void setAttribute(String name, Object value) {
        if (owns(name)) includeAttributes.put(name, value);
        else super.setAttribute(name, value);
    }
    @Override public void removeAttribute(String name) {
        if (owns(name)) includeAttributes.put(name, null);
        else super.removeAttribute(name);
    }

    private boolean owns(String name) {
        return !includeAttributes.isEmpty() && name != null && name.startsWith(PREFIX);
    }

    /** Section 9.1: a relative path is resolved against the included path. */
    @Override public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null || path.startsWith("/") || target.named()) return super.getRequestDispatcher(path);
        return super.getRequestDispatcher(
                DispatchParameters.resolveRelative(target.servletPath(), target.pathInfo(), path));
    }

    @Override public String getParameter(String name) { return parameters.first(name); }
    @Override public String[] getParameterValues(String name) { return parameters.values(name); }
    @Override public Map<String, String[]> getParameterMap() { return parameters.map(); }
    @Override public Enumeration<String> getParameterNames() { return parameters.names(); }
}
