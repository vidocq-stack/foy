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
 * excepted), a backslash or a NUL character.</p>
 *
 * <p>The container default servlet applies two levels. {@link #isDispatchable}, for every dispatch
 * type: a safe path free of encoded dots and separators ({@code %2e}, {@code %2f}, {@code %5c},
 * {@code %00}). {@link #isServable}, for a client request: a dispatchable path outside the
 * {@code WEB-INF/} and {@code META-INF/} trees, which section 10.5 keeps from clients but lets the
 * application expose through a {@code RequestDispatcher} (forward, include, error page). The first
 * segment is compared case-insensitively and without trailing dots or spaces, so neither a
 * case-insensitive file system ({@code /web-inf/web.xml}) nor Windows name aliasing
 * ({@code /WEB-INF./web.xml}, {@code /WEB-INF /web.xml}) reaches the protected trees.</p>
 *
 * <p><b>Decoding.</b> Foy currently hands servlets the raw, still percent-encoded request path
 * (BUG-20261009-02): the default servlet looks resources up under that raw form. Refusing the
 * encoded dots and separators keeps a provider that would decode the path from being steered
 * outside the resource root, and stays correct once the path is decoded upstream (the encoded
 * forms then only appear through double encoding).</p>
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

    /** Whether the default servlet may serve {@code path} on a forward, include, async or error dispatch. */
    public static boolean isDispatchable(String path) {
        return isSafe(path) && !hasEncodedSeparator(path);
    }

    /**
     * Whether the default servlet may serve {@code path} to a client request: a dispatchable path
     * outside the {@code WEB-INF/} and {@code META-INF/} trees.
     */
    public static boolean isServable(String path) {
        if (!isDispatchable(path)) return false;
        int slash = path.indexOf('/', 1);
        String first = slash < 0 ? path.substring(1) : path.substring(1, slash);
        int end = first.length();
        while (end > 0 && (first.charAt(end - 1) == '.' || first.charAt(end - 1) == ' ')) end--;
        String name = first.substring(0, end).toUpperCase(Locale.ROOT);
        return !name.equals("WEB-INF") && !name.equals("META-INF");
    }

    private static boolean hasEncodedSeparator(String path) {
        for (int i = path.indexOf('%'); i >= 0 && i + 2 < path.length(); i = path.indexOf('%', i + 1)) {
            String code = path.substring(i + 1, i + 3).toLowerCase(Locale.ROOT);
            if (code.equals("2e") || code.equals("2f") || code.equals("5c") || code.equals("00")) return true;
        }
        return false;
    }
}
