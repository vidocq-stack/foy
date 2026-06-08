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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CookieCodecTest {

    @Test
    void parsesSingleCookie() {
        List<Cookie> out = CookieCodec.parseCookieHeader("session=abc123");
        assertEquals(1, out.size());
        assertEquals("session", out.get(0).getName());
        assertEquals("abc123", out.get(0).getValue());
    }

    @Test
    void parsesMultipleCookies() {
        var out = CookieCodec.parseCookieHeader("a=1; b=2; c=3");
        assertEquals(3, out.size());
        assertEquals("1", out.get(0).getValue());
        assertEquals("3", out.get(2).getValue());
    }

    @Test
    void preservesQuotesAroundValue() {
        // RFC 6265 : la valeur cookie peut être quoted-string ; côté serveur on
        // préserve les guillemets (TCK CookieTests.getValueQuotedTest les attend).
        var out = CookieCodec.parseCookieHeader("name=\"quoted-value\"");
        assertEquals("\"quoted-value\"", out.get(0).getValue());
    }

    @Test
    void nullOrEmptyReturnsEmpty() {
        assertTrue(CookieCodec.parseCookieHeader(null).isEmpty());
        assertTrue(CookieCodec.parseCookieHeader("").isEmpty());
    }

    @Test
    void ignoresEntriesWithoutEquals() {
        var out = CookieCodec.parseCookieHeader("invalid; valid=1");
        assertEquals(1, out.size());
        assertEquals("valid", out.get(0).getName());
    }

    @Test
    void serializesMinimalCookie() {
        Cookie c = new Cookie("session", "abc");
        String s = CookieCodec.serializeSetCookie(c);
        assertEquals("session=abc", s);
    }

    @Test
    void serializesFullCookie() {
        Cookie c = new Cookie("JSESSIONID", "xyz");
        c.setPath("/app");
        c.setDomain("example.com");
        c.setMaxAge(3600);
        c.setSecure(true);
        c.setHttpOnly(true);
        String s = CookieCodec.serializeSetCookie(c);
        assertTrue(s.contains("JSESSIONID=xyz"));
        assertTrue(s.contains("Path=/app"));
        assertTrue(s.contains("Domain=example.com"));
        assertTrue(s.contains("Max-Age=3600"));
        assertTrue(s.contains("Secure"));
        assertTrue(s.contains("HttpOnly"));
    }
}
