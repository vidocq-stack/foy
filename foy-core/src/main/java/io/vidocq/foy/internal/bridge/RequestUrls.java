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

import jakarta.servlet.http.HttpServletRequest;

/** The {@code getRequestURL()} of a dispatch wrapper, built on its own {@code getRequestURI()}. */
final class RequestUrls {

    private RequestUrls() {}

    /**
     * {@code scheme://host[:port]} followed by the request's {@code getRequestURI()}; the port is
     * omitted when it is the default one of the scheme (80 for http, 443 for https). Callers wrap
     * it in the {@link StringBuffer} that {@link HttpServletRequest#getRequestURL()} returns.
     */
    static String of(HttpServletRequest request) {
        var sb = new StringBuilder();
        String scheme = request.getScheme();
        int port = request.getServerPort();
        sb.append(scheme).append("://").append(request.getServerName());
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            sb.append(':').append(port);
        }
        return sb.append(request.getRequestURI()).toString();
    }
}
