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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Request path canonicalisation (Servlet 6.1 section 3.5.2, "URI Path Canonicalization").
 *
 * <p>{@link #canonicalize} turns the raw, still percent-encoded request path (the request URI
 * without the context path) into the canonical path that servlet mapping, filter mapping,
 * welcome-file resolution, the container default servlet and security constraints all use. The
 * bridge calls it once per request and keeps the result on the request. The steps, in order:</p>
 * <ol>
 *   <li>Refuse a path that does not start with {@code /}, or that holds a raw backslash, a control
 *       character ({@code U+0000}–{@code U+001F}, {@code U+007F}) or a non-ASCII character (RFC 3986
 *       allows only ASCII in a request target; Tomcat's request-line parser rejects the others too).</li>
 *   <li>Remove the path parameters of every segment ({@code ;name=value…} up to the next {@code /}),
 *       {@code ;jsessionid=} included.</li>
 *   <li>Refuse an encoded separator or NUL ({@code %2F}, {@code %5C}, {@code %00}, any case): decoding
 *       them would let a client forge segments. Tomcat's default {@code encodedSolidusHandling=reject}
 *       ({@code CoyoteAdapter.postParseRequest}, {@code UDecoder.convert}) answers 400 the same way.</li>
 *   <li>Percent-decode once as UTF-8; a malformed escape or an invalid UTF-8 sequence (overlong
 *       forms and surrogates included) is refused, and so is a decoded control character.</li>
 *   <li>Normalise ({@link #normalize}): collapse repeated {@code /}, drop {@code .} segments and resolve
 *       {@code ..} segments; a {@code ..} that would climb above the context root is refused
 *       (Tomcat's {@code CoyoteAdapter.normalize} / {@code RequestUtil.normalize}).</li>
 * </ol>
 * <p>A refused path yields {@code null}: the bridge answers 400 before any filter or servlet runs.
 * Decoding happens after the path parameters are removed, so an encoded {@code %3B} stays a literal
 * {@code ;} of the segment; normalisation happens after decoding, so {@code %2e%2e} is a dot segment.</p>
 */
public final class RequestPaths {

    private RequestPaths() {}

    /**
     * Canonical form of a raw request path (context path removed, query string excluded), or
     * {@code null} when the path must be refused with 400 (see the class documentation).
     */
    public static String canonicalize(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '/') return null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c < 0x20 || c >= 0x7f) return null;
        }
        String stripped = stripPathParameters(raw);
        String decoded = decode(stripped);
        if (decoded == null) return null;
        return normalize(decoded);
    }

    /**
     * Normalises an already decoded, context-relative path: repeated {@code /} collapse into one,
     * {@code .} segments are dropped and {@code ..} segments remove their parent. Returns {@code null}
     * for a path that does not start with {@code /} or whose {@code ..} climbs above the root. Used
     * as is for {@code getRequestDispatcher} paths (section 9.1.1), which are never decoded again.
     */
    public static String normalize(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return null;
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        int n = path.length();
        while (i < n) {
            // i is on a '/': skip the run of slashes
            while (i < n && path.charAt(i) == '/') i++;
            int end = path.indexOf('/', i);
            if (end < 0) end = n;
            String segment = path.substring(i, end);
            boolean last = end >= n;
            if (segment.equals(".")) {
                if (last) out.append('/');
            } else if (segment.equals("..")) {
                int cut = out.lastIndexOf("/");
                if (cut < 0) return null; // above the root
                out.setLength(cut);
                if (last) out.append('/');
            } else if (segment.isEmpty()) {
                // trailing slash run
                out.append('/');
            } else {
                out.append('/').append(segment);
            }
            i = end;
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    private static String stripPathParameters(String raw) {
        if (raw.indexOf(';') < 0) return raw;
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == ';') {
                int slash = raw.indexOf('/', i);
                if (slash < 0) break;
                i = slash;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String decode(String path) {
        if (path.indexOf('%') < 0) return path;
        byte[] bytes = new byte[path.length()];
        int len = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '%') {
                bytes[len++] = (byte) c; // ASCII only (checked by the caller)
                continue;
            }
            if (i + 2 >= path.length()) return null;
            int hi = Character.digit(path.charAt(i + 1), 16);
            int lo = Character.digit(path.charAt(i + 2), 16);
            if (hi < 0 || lo < 0) return null;
            int b = (hi << 4) | lo;
            if (b == '/' || b == '\\' || b == 0) return null; // encoded separator or NUL
            if (b < 0x20 || b == 0x7f) return null;           // encoded control character
            bytes[len++] = (byte) b;
            i += 2;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, len))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
