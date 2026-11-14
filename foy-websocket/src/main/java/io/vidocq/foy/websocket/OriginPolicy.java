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
package io.vidocq.foy.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The origins allowed to open a WebSocket: the defence against cross-site WebSocket hijacking.
 * Built from the {@code io.vidocq.foy.websocket.allowed-origins} init parameter; the rules are the
 * ones of Chappe's {@code WebSocketOrigins}, so both layers decide alike.
 * <ol>
 *   <li>a handshake without {@code Origin} is allowed (it does not come from a browser);</li>
 *   <li>{@code *} allows every origin;</li>
 *   <li>an {@code Origin} that is not {@code http(s)://host[:port]} is refused, {@code null} among them;</li>
 *   <li>the server's own origin is allowed: same host (ignoring case) and port, a {@code Host} without
 *       port standing for the origin's scheme default;</li>
 *   <li>a listed origin is allowed: same scheme, host and port; any other is refused.</li>
 * </ol>
 */
final class OriginPolicy {

    static final String INIT_PARAMETER = "io.vidocq.foy.websocket.allowed-origins";

    /** {@code host[:port]}: a registered name or an IPv4 address, or an IPv6 literal in brackets. */
    private static final String AUTHORITY = "(\\[[\\da-f:.]+\\]|[a-z\\d._~-]+)(?::(\\d{1,5}))?";

    private static final Pattern ORIGIN = Pattern.compile("(?i)(https?)://" + AUTHORITY);

    private static final Pattern HOST = Pattern.compile("(?i)" + AUTHORITY);

    private static final int MAX_PORT = 65_535;

    private static final OriginPolicy SAME_ORIGIN = new OriginPolicy(false, List.of());

    private static final OriginPolicy ANY = new OriginPolicy(true, List.of());

    /** An origin with a lower-case scheme and host, and its port: the scheme's default when it has none. */
    private record Origin(String scheme, String host, int port) {
    }

    private final boolean anyOrigin;
    private final List<Origin> listed;

    private OriginPolicy(boolean anyOrigin, List<Origin> listed) {
        this.anyOrigin = anyOrigin;
        this.listed = listed;
    }

    /**
     * The policy of a comma-separated list such as {@code https://admin.example.com, http://localhost:5173}.
     * Entries are trimmed and empty ones ignored; a {@code *} among them allows any origin.
     *
     * @param initParameter the list, or {@code *}; {@code null} or blank gives same-origin only
     * @throws IllegalArgumentException if an entry is neither {@code *} nor {@code http(s)://host[:port]}
     */
    static OriginPolicy parse(String initParameter) {
        if (initParameter == null || initParameter.isBlank()) {
            return SAME_ORIGIN;
        }
        boolean star = false;
        var origins = new ArrayList<Origin>();
        for (String raw : initParameter.split(",")) {
            String entry = raw.trim();
            if ("*".equals(entry)) {
                star = true;
            } else if (!entry.isEmpty()) {
                origins.add(requireOrigin(entry));
            }
        }
        if (star) {
            return ANY;
        }
        return origins.isEmpty() ? SAME_ORIGIN : new OriginPolicy(false, List.copyOf(origins));
    }

    /**
     * Whether a handshake may open a WebSocket.
     *
     * @param origin the {@code Origin} header, {@code null} when absent
     * @param host   the {@code Host} header, {@code null} when absent
     */
    boolean allows(String origin, String host) {
        if (origin == null || anyOrigin) {
            return true;
        }
        Origin parsed = parseOrigin(origin);
        if (parsed == null) {
            return false;
        }
        return isServerOrigin(parsed, host) || listed.contains(parsed);
    }

    /** Whether every origin is allowed. */
    boolean allowsAny() {
        return anyOrigin;
    }

    /**
     * Whether {@code host} is {@code host[:port]}, as a {@code Host} header must be (RFC 9112 section
     * 3.2): a registered name or an IPv4 address, or an IPv6 literal in brackets, and a port up to 65535.
     */
    static boolean isAuthority(String host) {
        return host != null && authority(host) != null;
    }

    private static Origin requireOrigin(String entry) {
        Origin origin = parseOrigin(entry);
        if (origin == null) {
            throw new IllegalArgumentException("Invalid WebSocket origin '" + printable(entry)
                    + "': expected http(s)://host[:port] or * (init parameter " + INIT_PARAMETER + ")");
        }
        return origin;
    }

    /** {@code value} as an origin, or {@code null} when it is not {@code http(s)://host[:port]}. */
    private static Origin parseOrigin(String value) {
        Matcher m = ORIGIN.matcher(value);
        if (!m.matches()) {
            return null;
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        int port = m.group(3) == null ? defaultPort(scheme) : Integer.parseInt(m.group(3));
        return port > MAX_PORT ? null : new Origin(scheme, m.group(2).toLowerCase(Locale.ROOT), port);
    }

    /** Whether {@code origin} is the server's: the host of {@code host}, and its port or the origin's scheme default. */
    private static boolean isServerOrigin(Origin origin, String host) {
        Matcher m = host == null ? null : authority(host);
        if (m == null) {
            return false;
        }
        int port = m.group(2) == null ? defaultPort(origin.scheme()) : Integer.parseInt(m.group(2));
        return origin.host().equalsIgnoreCase(m.group(1)) && origin.port() == port;
    }

    /** The match of {@code host} as {@code host[:port]}, {@code null} when it is not one or its port is above 65535. */
    private static Matcher authority(String host) {
        Matcher m = HOST.matcher(host);
        return m.matches() && (m.group(2) == null || Integer.parseInt(m.group(2)) <= MAX_PORT) ? m : null;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    /** {@code s} quoted, with every char outside printable ASCII escaped as {@code \}{@code uXXXX}. */
    static String printable(String s) {
        var sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.append('"').toString();
    }
}
