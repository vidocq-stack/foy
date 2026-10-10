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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BasicAuthenticatorTest {

    private static final InMemoryIdentityStore USERS = InMemoryIdentityStore.builder()
            .user("alice", "pa:ss".toCharArray(), "admin").build();
    private final BasicAuthenticator basic = new BasicAuthenticator(USERS, "Corp");

    private static String b64(byte[] raw) { return Base64.getEncoder().encodeToString(raw); }
    private static String b64(String raw) { return b64(raw.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void validHeaderAuthenticatesAndSplitsOnTheFirstColon() {
        assertEquals("alice", basic.tryAuthenticate("Basic " + b64("alice:pa:ss")).orElseThrow().name());
        assertEquals("alice", basic.tryAuthenticate("basic " + b64("alice:pa:ss")).orElseThrow().name());
    }

    @Test
    void malformedHeadersAreRefusedWithoutException() {
        String[] bad = {
                null, "", "Basic", "Basic ", "Bearer " + b64("alice:pa:ss"), "Basic\t" + b64("alice:pa:ss"),
                "Basic %%%%", "Basic " + b64("alice"), "Basic " + b64(":pa:ss"),
                "Basic " + b64("ali\u0001ce:pa:ss"), "Basic " + b64("alice:pa\nss"),
                "Basic " + b64(new byte[] {(byte) 0xC3, (byte) 0x28, ':', 'x'}),
                "Basic " + "A".repeat(BasicAuthenticator.MAX_CREDENTIALS_LENGTH),
                "Basic " + b64("alice:pa:ss") + " trailing"
        };
        for (String h : bad) assertNull(BasicAuthenticator.parse(h), String.valueOf(h));
        for (String h : bad) assertTrue(basic.tryAuthenticate(h).isEmpty(), String.valueOf(h));
    }

    @Test
    void wrongPasswordIsRefused() {
        assertTrue(basic.tryAuthenticate("Basic " + b64("alice:nope")).isEmpty());
    }

    @Test
    void challengeQuotesTheRealm() {
        assertEquals("Basic realm=\"Corp\", charset=\"UTF-8\"", basic.challengeHeaderValue());
        assertEquals("Basic realm=\"a\\\"b\\\\c\", charset=\"UTF-8\"",
                new BasicAuthenticator(USERS, "a\"b\\c").challengeHeaderValue());
        assertEquals("Restricted", BasicAuthenticator.DEFAULT_REALM);
    }
}
