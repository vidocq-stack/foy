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

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.spi.security.SecurityProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP Basic Authentication (RFC 7617).
 * <p>Parse the {@code Authorization: Basic base64(user:pass)} header and delegate
 * authentication at {@link SecurityProvider}.</p>
 */
public final class BasicAuthenticator {

    public static final String REALM_DEFAULT = "vidocq";

    private final SecurityProvider provider;
    private final String realm;

    public BasicAuthenticator(SecurityProvider provider, String realm) {
        this.provider = Objects.requireNonNull(provider);
        this.realm = Objects.requireNonNull(realm);
    }

    public BasicAuthenticator(SecurityProvider provider) { this(provider, REALM_DEFAULT); }

    public String realm() { return realm; }

    /** Attempts authentication from a {@code Authorization} header. */
    public Optional<AuthenticatedUser> tryAuthenticate(String authorizationHeader) {
        if (authorizationHeader == null) return Optional.empty();
        String prefix = "Basic ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        String encoded = authorizationHeader.substring(prefix.length()).trim();
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
        String raw = new String(decoded, StandardCharsets.UTF_8);
        int colon = raw.indexOf(':');
        if (colon < 0) return Optional.empty();
        String user = raw.substring(0, colon);
        String pass = raw.substring(colon + 1);
        return provider.authenticate(user, pass);
    }

    public String challengeHeaderValue() {
        return "Basic realm=\"" + realm + "\", charset=\"UTF-8\"";
    }
}
