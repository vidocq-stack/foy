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
package io.vidocq.foy.internal.container;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of deployed {@link VidocqServletContext} — support for
 * {@link jakarta.servlet.ServletContext#getContext(String)} (§4.8) and
 * cross-context dispatches via {@link jakarta.servlet.AsyncContext#dispatch(
 * jakarta.servlet.ServletContext, String)}.
 */
public final class CrossContextRegistry {

    private static final Map<String, VidocqServletContext> CONTEXTS = new ConcurrentHashMap<>();

    private CrossContextRegistry() {}

    public static void register(VidocqServletContext ctx) {
        CONTEXTS.put(normalize(ctx.getContextPath()), ctx);
    }

    public static void unregister(VidocqServletContext ctx) {
        CONTEXTS.remove(normalize(ctx.getContextPath()), ctx);
    }

    /**
     * The registered context whose path is the longest one {@code uripath} lies {@linkplain #isUnder under}, on
     * a segment boundary (Servlet 6.1 section 4.8): the root context holds every URI no other context does.
     *
     * @return the context, or {@code null} when {@code uripath} does not start with {@code /} or lies under no
     *         registered context
     */
    public static VidocqServletContext lookup(String uripath) {
        if (uripath == null || !uripath.startsWith("/")) return null;
        VidocqServletContext enclosing = null;
        int longest = -1;
        for (var entry : CONTEXTS.entrySet()) {
            String path = entry.getKey();
            if (path.length() > longest && isUnder(uripath, path)) {
                enclosing = entry.getValue();
                longest = path.length();
            }
        }
        return enclosing;
    }

    /**
     * Whether {@code uripath} (starting with {@code /}) lies under {@code contextPath} on a segment boundary:
     * it is the path, or the path followed by {@code /}; every URI lies under the root context's path.
     */
    static boolean isUnder(String uripath, String contextPath) {
        String path = normalize(contextPath);
        return "/".equals(path) || uripath.equals(path) || uripath.startsWith(path + "/");
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) return "/" + path;
        if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
        return path;
    }
}
