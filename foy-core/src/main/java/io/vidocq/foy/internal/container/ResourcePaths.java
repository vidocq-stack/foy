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

import java.util.Locale;

/**
 * Provider-independent path rules for static resources.
 *
 * <p>{@link #isSafe} is the structural check every resource lookup applies: an absolute path
 * without a {@code .} or {@code ..} segment, an empty segment ({@code //}, a trailing slash
 * excepted), a backslash or a NUL character. {@link #isServable} is what the container default
 * servlet may serve over HTTP: a safe path outside the {@code WEB-INF/} and {@code META-INF/}
 * trees (first segment compared case-insensitively, since a case-insensitive file system would
 * otherwise serve {@code /web-inf/web.xml}).</p>
 *
 * <p>The default servlet receives a path that is already percent-decoded: an encoded dot,
 * slash, backslash or NUL ({@code %2e}, {@code %2f}, {@code %5c}, {@code %00}) still present at
 * that point is a double encoding and is refused, so that a provider which decodes once more
 * can never be steered outside the resource root.</p>
 */
public final class ResourcePaths {

    private ResourcePaths() {}

    /** Whether {@code path} is structurally safe to look up (see the class documentation). */
    public static boolean isSafe(String path) {
        if (path == null || !path.startsWith("/")) return false;
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        String[] segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.equals("..") || segment.equals(".")) return false;
            if (segment.isEmpty() && i != segments.length - 1) return false;
        }
        return true;
    }

    /**
     * Whether the default servlet may serve {@code path}: a safe path, free of encoded
     * separators or dots, outside the {@code WEB-INF/} and {@code META-INF/} trees.
     */
    public static boolean isServable(String path) {
        if (!isSafe(path) || hasEncodedSeparator(path)) return false;
        int slash = path.indexOf('/', 1);
        String first = (slash < 0 ? path.substring(1) : path.substring(1, slash)).toUpperCase(Locale.ROOT);
        return !first.equals("WEB-INF") && !first.equals("META-INF");
    }

    private static boolean hasEncodedSeparator(String path) {
        for (int i = path.indexOf('%'); i >= 0 && i + 2 < path.length(); i = path.indexOf('%', i + 1)) {
            String code = path.substring(i + 1, i + 3).toLowerCase(Locale.ROOT);
            if (code.equals("2e") || code.equals("2f") || code.equals("5c") || code.equals("00")) return true;
        }
        return false;
    }
}
