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

/**
 * The response header rule Foy shares with chappe (CHAPPE-008), applied when the application sets
 * a header so that it fails there with an {@link IllegalArgumentException} rather than at write
 * time. A name is an RFC 9110 token; a value holds no control character but HTAB (no CR, LF, NUL,
 * U+0001 to U+001F but U+0009, nor DEL) and nothing above U+00FF (the HTTP/1.1 writer emits one
 * byte per char, so U+010D U+010A would become CR LF). SP, HTAB, obs-text (U+0080 to U+00FF) and
 * the empty value stay legal. Messages name the offending code point and position, never the
 * text, which may carry a credential.
 */
public final class HeaderSyntax {

    private HeaderSyntax() {}

    /** RFC 9110 tchar beside ALPHA and DIGIT. */
    private static final String TCHAR_SYMBOLS = "!#$%&'*+-.^_`|~";

    /** Rejects a non-null {@code name} that is not an RFC 9110 token; {@code null} is left to the caller. */
    public static void checkName(String name) {
        if (name == null) return;
        if (name.isEmpty()) throw new IllegalArgumentException("empty header name");
        for (int i = 0; i < name.length(); i++) {
            if (!isTchar(name.charAt(i))) {
                throw new IllegalArgumentException("illegal character U+" + hex(name.charAt(i))
                        + " at index " + i + " of a header name");
            }
        }
    }

    /**
     * Rejects a non-null {@code value} holding a forbidden character. {@code what} names the
     * header (a name already checked, or a fixed label); the value itself is never echoed.
     */
    public static void checkValue(String what, String value) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) {
                throw new IllegalArgumentException("illegal character U+" + hex(c) + " at index " + i
                        + " in the value of " + what);
            }
        }
    }

    private static boolean isTchar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || TCHAR_SYMBOLS.indexOf(c) >= 0;
    }

    private static String hex(char c) {
        return String.format("%04X", (int) c);
    }
}
