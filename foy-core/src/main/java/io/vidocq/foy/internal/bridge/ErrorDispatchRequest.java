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
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * The request an error dispatch hands its error-page target (Servlet 6.1 section 10.9.1): the
 * path elements, the request URI and the servlet mapping are those of the error-page location,
 * as for a forward to it. The query string, the parameters and the attributes (the
 * {@code jakarta.servlet.error.*} ones describe the failing request) stay the original request's.
 */
final class ErrorDispatchRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;

    ErrorDispatchRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
    }

    @Override public String getServletPath() {
        return target.servletPath() != null ? target.servletPath() : super.getServletPath();
    }
    @Override public String getPathInfo() {
        return target.servletPath() != null ? target.pathInfo() : super.getPathInfo();
    }
    @Override public String getRequestURI() {
        return target.path() != null ? getContextPath() + target.path() : super.getRequestURI();
    }
    @Override public StringBuffer getRequestURL() {
        if (target.path() == null) return super.getRequestURL();
        StringBuffer sb = new StringBuffer();
        String scheme = getScheme();
        int port = getServerPort();
        sb.append(scheme).append("://").append(getServerName());
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            sb.append(':').append(port);
        }
        return sb.append(getRequestURI());
    }
    @Override public HttpServletMapping getHttpServletMapping() {
        return target.mapping() != null ? target.mapping() : super.getHttpServletMapping();
    }
}
