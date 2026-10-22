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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP Basic authentication (RFC 7617, Servlet 6.1 section 13.6.1). The header is parsed strictly,
 * and any deviation is a refusal, never an exception:
 * <ul>
 *   <li>the scheme is {@code Basic} (any case) followed by spaces, then one Base64 token and
 *       nothing else;</li>
 *   <li>the decoded bytes are strict UTF-8 with no control character;</li>
 *   <li>the user-id is non-empty and ends at the first colon.</li>
 * </ul>
 * The password only ever lives in a {@code char[]} that is wiped after validation.
 */
public final class BasicAuthenticator implements Authenticator {

    /** Realm sent when {@code login-config} names none: neutral, no product name. */
    public static final String DEFAULT_REALM = "Restricted";
    /** Longest accepted header value, in characters. */
    static final int MAX_CREDENTIALS_LENGTH = 8192;

    private final IdentityStore store;
    private final String realm;

    public BasicAuthenticator(IdentityStore store, String realm) {
        this.store = Objects.requireNonNull(store, "store");
        this.realm = Objects.requireNonNull(realm, "realm");
    }

    public String realm() { return realm; }

    /** The identity carried by an {@code Authorization} header value; empty when absent, malformed or refused. */
    public Optional<Identity> tryAuthenticate(String authorizationHeader) {
        UserPassword credentials = parse(authorizationHeader);
        if (credentials == null) return Optional.empty();
        return CredentialCheck.validate(store, credentials.user(), credentials.password());
    }

    public String challengeHeaderValue() {
        return "Basic realm=\"" + realm.replace("\\", "\\\\").replace("\"", "\\\"") + "\", charset=\"UTF-8\"";
    }

    @Override public String authType() { return jakarta.servlet.http.HttpServletRequest.BASIC_AUTH; }

    @Override
    public Optional<Identity> authenticate(io.vidocq.foy.internal.bridge.HttpServletRequestImpl req) {
        return tryAuthenticate(req.getHeader("Authorization"));
    }

    @Override
    public void challenge(io.vidocq.foy.internal.bridge.HttpServletRequestImpl req,
                          jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        res.setHeader("WWW-Authenticate", challengeHeaderValue());
        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
    }

    /** The user and password of a Basic header; the receiver clears the password once used. */
    static final class UserPassword {
        private final String user;
        private final char[] password;

        UserPassword(String user, char[] password) {
            this.user = user;
            this.password = password;
        }

        String user() { return user; }

        char[] password() { return password; }
    }

    /** The user and password of a Basic header, or {@code null} when anything is off. */
    static UserPassword parse(String header) {
        String token = token(header);
        if (token == null) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException _) {
            return null;
        }
        try {
            return split(decoded);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    /** The Base64 token of a Basic {@code header}, or {@code null} when it is not one. */
    private static String token(String header) {
        if (header == null || header.length() > MAX_CREDENTIALS_LENGTH) return null;
        if (header.length() < 6 || !header.regionMatches(true, 0, "Basic", 0, 5) || header.charAt(5) != ' ') {
            return null;
        }
        int start = 5;
        while (start < header.length() && header.charAt(start) == ' ') start++;
        String token = header.substring(start);
        return token.isEmpty() ? null : token;
    }

    /** {@code user:password} decoded, or {@code null} when malformed; the intermediate chars are cleared. */
    private static UserPassword split(byte[] decoded) {
        char[] all = decodeUtf8(decoded);
        if (all == null) return null;
        try {
            int colon = colonIndex(all);
            if (colon <= 0) return null;
            return new UserPassword(new String(all, 0, colon), Arrays.copyOfRange(all, colon + 1, all.length));
        } finally {
            Arrays.fill(all, '\0');
        }
    }

    /** The strict UTF-8 decoding of {@code bytes}, or {@code null} when malformed; the decoder's buffer is cleared. */
    private static char[] decodeUtf8(byte[] bytes) {
        CharBuffer chars;
        try {
            chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException _) {
            return null;
        }
        char[] all = new char[chars.remaining()];
        chars.get(all);
        if (chars.hasArray()) Arrays.fill(chars.array(), '\0');
        return all;
    }

    /** The index of the first colon of {@code chars}; {@code -1} without one or with a control character. */
    private static int colonIndex(char[] chars) {
        int colon = -1;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c < 0x20 || c == 0x7f) return -1;
            if (colon < 0 && c == ':') colon = i;
        }
        return colon;
    }
}
