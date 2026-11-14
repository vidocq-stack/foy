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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Mirrors Chappe's {@code WebSocketOriginsTest} case for case. */
class OriginPolicyTest {

    private static final String LISTED = "https://admin.example.com, http://localhost:5173";

    @Test
    void noOriginIsAllowed() {
        assertTrue(OriginPolicy.parse(null).allows(null, "a:8080"));
    }

    @Test
    void aBlankParameterMeansSameOriginOnly() {
        assertFalse(OriginPolicy.parse("  ").allows("http://evil.example", "a:8080"));
    }

    @Test
    void theSameHostAndPortIsAllowed() {
        assertTrue(OriginPolicy.parse(null).allows("http://a:8080", "a:8080"));
    }

    @Test
    void allowsTheOriginOfATlsTerminatingProxy() {
        assertTrue(OriginPolicy.parse(null).allows("https://app.example.com", "app.example.com"));
    }

    @Test
    void anotherPortIsRefused() {
        assertFalse(OriginPolicy.parse(null).allows("http://localhost:5173", "localhost:8080"));
    }

    @Test
    void theNullOriginIsRefused() {
        assertFalse(OriginPolicy.parse(null).allows("null", "a"));
    }

    @Test
    void aListedOriginIsAllowed() {
        var origins = OriginPolicy.parse(LISTED);

        assertTrue(origins.allows("https://admin.example.com", "a:8080"));
        assertTrue(origins.allows("http://localhost:5173", "a:8080"));
    }

    @Test
    void aListedOriginMustMatchItsScheme() {
        assertFalse(OriginPolicy.parse(LISTED).allows("http://admin.example.com", "a:8080"));
    }

    @Test
    void starAllowsAny() {
        var origins = OriginPolicy.parse("http://localhost:5173, *");

        assertTrue(origins.allowsAny());
        assertSame(OriginPolicy.parse("*"), origins);
        assertTrue(origins.allows("http://evil.example", "a:8080"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://x", "https://x/p", "x.example", "https://u@x"})
    void aMalformedEntryNamesItAndTheParameter(String entry) {
        var list = "http://localhost:5173, " + entry;

        var e = assertThrows(IllegalArgumentException.class, () -> OriginPolicy.parse(list));

        assertTrue(e.getMessage().contains(entry), e.getMessage());
        assertTrue(e.getMessage().contains("io.vidocq.foy.websocket.allowed-origins"), e.getMessage());
    }

    /** Every entry is checked, a {@code *} does not skip the others, and a trailing slash is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"*, ftp://x", "https://x/"})
    void aBadListThrows(String list) {
        assertThrows(IllegalArgumentException.class, () -> OriginPolicy.parse(list));
    }

    @Test
    void aListedOriginIsLowerCased() {
        var origins = OriginPolicy.parse("HTTPS://Admin.Example.COM");

        assertTrue(origins.allows("https://admin.example.com", "a:8080"));
    }

    /** A {@code Host} value: a name or an IPv4 address, or an IPv6 literal in brackets, and a port up to 65535. */
    @ParameterizedTest
    @ValueSource(strings = {"example.com", "Example.COM:8080", "127.0.0.1:0", "[::1]", "[2001:db8::1]:65535"})
    void anAuthorityIsAHostAndAnOptionalPort(String host) {
        assertTrue(OriginPolicy.isAuthority(host));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x/admin?", "x/admin", "x#", "user@x", "x:65536", "x:", "[::1", "x y"})
    void anythingElseIsNotAnAuthority(String host) {
        assertFalse(OriginPolicy.isAuthority(host));
    }

    @Test
    void anAbsentHostIsNotAnAuthority() {
        assertFalse(OriginPolicy.isAuthority(null));
    }
}
