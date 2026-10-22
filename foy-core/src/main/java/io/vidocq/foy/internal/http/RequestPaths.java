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
 *       character ({@code U+0000}–{@code U+001F}, {@code U+007F}) or a non-ASCII character. The last
 *       one is Foy's own choice: the request-target grammar (RFC 7230 section 5.3, RFC 3986) is ASCII
 *       only, non-ASCII names must arrive percent-encoded.</li>
 *   <li>Remove the path parameters of every segment ({@code ;name=value…} up to the next {@code /}),
 *       {@code ;jsessionid=} included.</li>
 *   <li>Refuse an encoded separator or NUL ({@code %2F}, {@code %5C}, {@code %00}, any case): decoding
 *       them would let a client forge segments. Tomcat's default {@code encodedSolidusHandling=reject}
 *       ({@code CoyoteAdapter.postParseRequest}, {@code UDecoder.convert}) answers 400 the same way.</li>
 *   <li>Percent-decode once as UTF-8; a malformed escape or an invalid UTF-8 sequence (overlong
 *       forms and surrogates included) is refused, and so is a decoded control character, and a
 *       segment other than {@code .}/{@code ..} made only of dots and spaces ({@code ...},
 *       {@code ..%20}: Windows name aliasing, defence in depth).</li>
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
     * Removes the context path from a raw request path on a segment boundary: the context path must
     * be followed by the end of the path, a {@code /}, or path parameters of its last segment
     * ({@code /ctx;x=1/a} → {@code /a}). Returns {@code "/"} for the context root itself and
     * {@code null} when the raw path is not under the context ({@code /ctx2/a} for {@code /ctx}).
     * The root context ({@code ""} or {@code "/"}) leaves the path unchanged.
     */
    public static String stripContextPath(String raw, String contextPath) {
        if (raw == null) return null;
        if (contextPath == null || contextPath.isEmpty() || contextPath.equals("/")) return raw;
        if (!raw.startsWith(contextPath)) return null;
        String rest = raw.substring(contextPath.length());
        if (rest.isEmpty()) return "/";
        char c = rest.charAt(0);
        if (c == '/') return rest;
        if (c != ';') return null;
        int slash = rest.indexOf('/');
        return slash < 0 ? "/" : rest.substring(slash);
    }

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
        if (decoded == null || hasDotOrSpaceOnlySegment(decoded)) return null;
        return normalize(decoded);
    }

    /**
     * Whether a segment other than {@code .} and {@code ..} is made only of dots and spaces
     * ({@code ...}, {@code .. }, a lone space): Windows file systems alias such names to their
     * parent or to {@code ..}, so they are refused as defence in depth.
     */
    private static boolean hasDotOrSpaceOnlySegment(String path) {
        int start = 1;
        while (start <= path.length()) {
            int end = path.indexOf('/', start);
            if (end < 0) end = path.length();
            if (end > start && isDotOrSpaceAlias(path.substring(start, end))) return true;
            start = end + 1;
        }
        return false;
    }

    /** A non-empty segment other than {@code .} and {@code ..} made only of dots and spaces. */
    private static boolean isDotOrSpaceAlias(String segment) {
        if (segment.equals(".") || segment.equals("..")) return false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '.' && c != ' ') return false;
        }
        return true;
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
            if (!appendSegment(out, path.substring(i, end), end >= n)) return null;
            i = end;
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    /**
     * Appends one segment of {@link #normalize} to {@code out}: a {@code .} is dropped, a {@code ..}
     * removes its parent, an empty segment is a trailing slash run. {@code false} when a {@code ..}
     * climbs above the root.
     */
    private static boolean appendSegment(StringBuilder out, String segment, boolean last) {
        if (segment.equals(".")) {
            if (last) out.append('/');
        } else if (segment.equals("..")) {
            int cut = out.lastIndexOf("/");
            if (cut < 0) return false; // above the root
            out.setLength(cut);
            if (last) out.append('/');
        } else if (segment.isEmpty()) {
            // trailing slash run
            out.append('/');
        } else {
            out.append('/').append(segment);
        }
        return true;
    }

    /**
     * Encodes a decoded, context-relative dispatch path for a request URI (RFC 3986): every
     * character other than a {@code pchar} or {@code /} is percent-encoded as UTF-8 bytes, so a
     * decoded {@code ?} or space cannot be read back as a query or a separator. A {@code %} already
     * followed by two hex digits is kept, any other {@code %} becomes {@code %25}; the encoding is
     * therefore idempotent. Existing {@code %XX} triplets are kept as they are, so a dispatch path
     * that literally holds {@code %20} yields a URI that decodes to a space: the deliberate
     * trade-off that avoids double encoding. A lone or mismatched surrogate is encoded as U+FFFD
     * ({@code %EF%BF%BD}). Unlike the directory-redirect encoder of the bridge, {@code ;} (a
     * sub-delim) is kept.
     */
    public static String encodeUriPath(String path) {
        if (path == null) return null;
        StringBuilder out = null;
        int n = path.length();
        int i = 0;
        while (i < n) {
            if (isPlain(path, i)) {
                if (out != null) out.append(path.charAt(i));
                i++;
            } else {
                if (out == null) out = new StringBuilder(n + 16).append(path, 0, i);
                i = appendEncoded(out, path, i);
            }
        }
        return out == null ? path : out.toString();
    }

    /**
     * Percent-encodes the character at {@code i} as UTF-8 bytes, a surrogate pair as its code point
     * and a lone surrogate as U+FFFD; returns the index after it.
     */
    private static int appendEncoded(StringBuilder out, String path, int i) {
        char c = path.charAt(i);
        if (i + 1 < path.length() && Character.isSurrogatePair(c, path.charAt(i + 1))) {
            appendPercentEncoded(out, path.substring(i, i + 2));
            return i + 2;
        }
        appendPercentEncoded(out, Character.isSurrogate(c) ? "\uFFFD" : String.valueOf(c)); // lone surrogate
        return i + 1;
    }

    /** Whether the character at {@code i} is kept as is: a {@code pchar}, a {@code /} or a {@code %XX} triplet. */
    private static boolean isPlain(String path, int i) {
        char c = path.charAt(i);
        if (c == '%') return i + 2 < path.length() && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2));
        return c < 0x80 && (isAsciiAlphaNumeric(c) || "-._~!$&'()*+,;=:@/".indexOf(c) >= 0);
    }

    private static void appendPercentEncoded(StringBuilder out, String chars) {
        for (byte b : chars.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xff;
            out.append('%').append(HEX.charAt(v >> 4)).append(HEX.charAt(v & 15));
        }
    }

    private static final String HEX = "0123456789ABCDEF";

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isAsciiAlphaNumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static String stripPathParameters(String raw) {
        if (raw.indexOf(';') < 0) return raw;
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == ';') {
                // The parameters run up to the next segment, or to the end of the path.
                int slash = raw.indexOf('/', i);
                i = slash < 0 ? raw.length() : slash;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * The byte of the {@code %XX} escape at {@code i}; {@code -1} when malformed, or for an encoded
     * separator, NUL or control character.
     */
    private static int escapedByte(String path, int i) {
        if (i + 2 >= path.length()) return -1;
        int hi = Character.digit(path.charAt(i + 1), 16);
        int lo = Character.digit(path.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) return -1;
        int b = (hi << 4) | lo;
        if (b == '/' || b == '\\' || b == 0) return -1; // encoded separator or NUL
        if (b < 0x20 || b == 0x7f) return -1;           // encoded control character
        return b;
    }

    private static String decode(String path) {
        if (path.indexOf('%') < 0) return path;
        byte[] bytes = new byte[path.length()];
        int len = 0;
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '%') {
                int b = escapedByte(path, i);
                if (b < 0) return null;
                bytes[len++] = (byte) b;
                i += 3;
            } else {
                bytes[len++] = (byte) c; // ASCII only (checked by the caller)
                i++;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, len))
                    .toString();
        } catch (CharacterCodingException _) {
            return null;
        }
    }
}
