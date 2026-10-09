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
package io.vidocq.foy.internal.http;

import jakarta.servlet.http.Cookie;

import java.util.ArrayList;
import java.util.List;

/**
 * Encoding/decoding of HTTP cookies (RFC 6265).
 * <p>
 * Decoding: parses a {@code Cookie} header entering a list of {@link Cookie}.
 * Encoding: serializes a {@link Cookie} into a full {@code Set-Cookie} line
 * with its attributes (Path, Domain, Max-Age, Secure, HttpOnly, SameSite).
 * </p>
 */
public final class CookieCodec {

    private CookieCodec() {}

    /** Parse the header {@code Cookie}: {@code name1=v1; name2=v2}. */
    public static List<Cookie> parseCookieHeader(String value) {
        List<Cookie> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        for (String pair : value.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            String name = trimmed.substring(0, eq).trim();
            String val = trimmed.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            // RFC 6265 §5.4 : un header Cookie client ne contient que des cookies nus
            // (name=value). Certains clients (ou le TCK) peuvent tout de même y glisser
            // des cookie-attributes (Domain, Path, Expires, Max-Age, ...) copiés depuis
            // un Set-Cookie précédent — on les ignore pour ne pas les exposer comme
            // "vrais" cookies.
            if (isReservedAttribute(name)) continue;
            // RFC 6265 : la valeur peut être encadrée de guillemets ; on préserve ceux-ci
            // (comportement attendu par TCK CookieTests.getValueQuotedTest — la valeur
            // ne doit pas être déguillementée lors de la lecture côté serveur).
            try {
                out.add(new Cookie(name, val));
            } catch (IllegalArgumentException ignored) {
                // Nom/valeur non conformes aux règles Cookie — on skip silencieusement.
            }
        }
        return out;
    }

    private static boolean isReservedAttribute(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("domain") || n.equals("path") || n.equals("expires")
                || n.equals("max-age") || n.equals("secure") || n.equals("httponly")
                || n.equals("samesite") || n.equals("partitioned") || n.equals("comment")
                || n.startsWith("$");
    }

    /** Serializes a complete {@link Cookie} into line {@code Set-Cookie}. */
    public static String serializeSetCookie(Cookie c) {
        return serializeSetCookie(c, java.time.Clock.systemUTC());
    }

    /**
     * Rejects, with an {@link IllegalArgumentException}, a cookie whose name, value, path, domain
     * or attributes hold CR, LF, NUL or a character above U+00FF: serialised into a
     * {@code Set-Cookie} header, such a character could split the header block (the HTTP/1.1
     * writer emits one byte per char).
     */
    public static void checkSerializable(Cookie c) {
        check("cookie name", c.getName());
        check("cookie value", c.getValue());
        check("cookie path", c.getPath());
        check("cookie domain", c.getDomain());
        for (var e : c.getAttributes().entrySet()) {
            check("cookie attribute", e.getKey());
            check("cookie attribute", e.getValue());
        }
    }

    private static void check(String what, String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == 0 || ch > 0xff) {
                throw new IllegalArgumentException("illegal character U+" + String.format("%04X", (int) ch)
                        + " in " + what);
            }
        }
    }

    /** RFC 7231 §7.1.1.1 IMF-fixdate, e.g. {@code Sun, 06 Nov 1994 08:49:37 GMT}. */
    public static String formatImfFixdate(java.time.Instant instant) {
        return IMF_FIXDATE.format(instant);
    }

    private static final java.time.format.DateTimeFormatter IMF_FIXDATE =
            java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                    .withZone(java.time.ZoneOffset.UTC);

    /** Package-private seam: the clock drives {@code Expires} so tests can pin it. */
    static String serializeSetCookie(Cookie c, java.time.Clock clock) {
        checkSerializable(c);
        StringBuilder sb = new StringBuilder();
        sb.append(c.getName()).append('=').append(c.getValue() == null ? "" : c.getValue());
        if (c.getPath() != null) sb.append("; Path=").append(c.getPath());
        if (c.getDomain() != null) sb.append("; Domain=").append(c.getDomain());
        int age = c.getMaxAge();
        if (age == 0) {
            // RFC 6265: Max-Age=0 expires the cookie immediately; Expires in the past
            // keeps clients that ignore Max-Age working. The TCK requires
            // that no Max-Age attribute accompanies it.
            sb.append("; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
        } else if (age > 0) {
            sb.append("; Expires=").append(formatImfFixdate(clock.instant().plusSeconds(age)));
            sb.append("; Max-Age=").append(age);
        }
        if (c.getSecure()) sb.append("; Secure");
        if (c.isHttpOnly()) sb.append("; HttpOnly");
        String sameSite = c.getAttribute("SameSite");
        if (sameSite != null) sb.append("; SameSite=").append(sameSite);
        // Servlet 6.1 §7.1 : l'attribut "Partitioned" (Chrome partitioning) est émis comme flag.
        String partitioned = c.getAttribute("Partitioned");
        // Le flag Partitioned est actif si l'attribut est défini (valeur non-null), même vide.
        if (partitioned != null && (partitioned.isEmpty() || "true".equalsIgnoreCase(partitioned))) {
            sb.append("; Partitioned");
        }
        // Autres attributs custom passés par setAttribute() sortent tels quels (clé=valeur).
        for (var e : c.getAttributes().entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String kl = k.toLowerCase(java.util.Locale.ROOT);
            if (kl.equals("samesite") || kl.equals("partitioned")
                    || kl.equals("path") || kl.equals("domain")
                    || kl.equals("max-age") || kl.equals("secure")
                    || kl.equals("httponly") || kl.equals("comment")) continue;
            sb.append("; ").append(k);
            if (e.getValue() != null && !e.getValue().isEmpty()) sb.append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
