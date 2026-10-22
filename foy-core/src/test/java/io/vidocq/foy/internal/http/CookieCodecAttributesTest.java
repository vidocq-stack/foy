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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the {@code Set-Cookie} attributes {@link CookieCodec} writes besides the standard ones. */
class CookieCodecAttributesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void customAttributesFollowTheStandardOnesAndABlankValueIsABareName() {
        Cookie c = new Cookie("a", "b");
        c.setPath("/p");
        c.setAttribute("Priority", "High");
        c.setAttribute("Flag", "");
        c.setAttribute("SameSite", "Lax");
        c.setAttribute("Partitioned", "true");
        assertEquals("a=b; Path=/p; SameSite=Lax; Partitioned; Flag; Priority=High",
                CookieCodec.serializeSetCookie(c, CLOCK));
    }

    @Test
    void theStandardAttributesAreNotRepeatedAsCustomOnes() {
        Cookie c = new Cookie("a", "b");
        c.setDomain("example.com");
        c.setMaxAge(60);
        c.setSecure(true);
        c.setHttpOnly(true);
        c.setAttribute("partitioned", "no");
        assertEquals("a=b; Domain=example.com; Expires=Thu, 01 Jan 2026 00:01:00 GMT; Max-Age=60; Secure; HttpOnly",
                CookieCodec.serializeSetCookie(c, CLOCK));
    }
}
