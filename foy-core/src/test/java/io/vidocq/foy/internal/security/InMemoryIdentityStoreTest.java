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
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryIdentityStoreTest {

    private static InMemoryIdentityStore store() {
        return InMemoryIdentityStore.builder()
                .user("alice", "a-secret".toCharArray(), "admin", "user")
                .user("bob", "b-secret".toCharArray(), "user")
                .build();
    }

    @Test
    void validCredentialsYieldThePrincipalAndTheRoles() {
        Identity id = store().validate("alice", "a-secret".toCharArray()).orElseThrow();
        assertEquals("alice", id.name());
        assertEquals("alice", id.principal().getName());
        assertEquals(Set.of("admin", "user"), id.roles());
    }

    @Test
    void wrongPasswordUnknownUserAndNullsAreRefused() {
        var s = store();
        assertTrue(s.validate("alice", "b-secret".toCharArray()).isEmpty());
        assertTrue(s.validate("carol", "a-secret".toCharArray()).isEmpty());
        assertTrue(s.validate(null, "a-secret".toCharArray()).isEmpty());
        assertTrue(s.validate("alice", null).isEmpty());
        assertTrue(s.validate("alice", new char[0]).isEmpty());
    }

    @Test
    void theDeclaredArrayIsNotRetained() {
        char[] pw = "a-secret".toCharArray();
        var s = InMemoryIdentityStore.builder().user("alice", pw, "admin").build();
        java.util.Arrays.fill(pw, 'x');
        assertTrue(s.validate("alice", "a-secret".toCharArray()).isPresent());
    }

    @Test
    void aUserDeclaredTwiceIsRejected() {
        var b = InMemoryIdentityStore.builder().user("alice", "p".toCharArray());
        assertThrows(IllegalArgumentException.class, () -> b.user("alice", "q".toCharArray()));
    }

    @Test
    void aPasswordWithALoneSurrogateDoesNotDigest() {
        // Before the fix both encoded to "a?" and digested to the same value.
        assertThrows(IllegalArgumentException.class,
                () -> InMemoryIdentityStore.digest(new byte[16], "a\uD800".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> InMemoryIdentityStore.digest(new byte[16], "a\uDC00".toCharArray()));
    }

    @Test
    void anEmptyPasswordStillDigests() {
        assertEquals(32, InMemoryIdentityStore.digest(new byte[16], new char[0]).length);
    }

    @Test
    void aMalformedPasswordCannotBeRegistered() {
        var b = InMemoryIdentityStore.builder();
        var e = assertThrows(IllegalArgumentException.class, () -> b.user("u", "a\uD800".toCharArray()));
        assertEquals("the password is not valid UTF-16", e.getMessage());
        // The failed call leaves the builder unchanged: the name is still free.
        assertDoesNotThrow(() -> b.user("u", "ok".toCharArray()));
    }

    @Test
    void aMalformedCandidateIsRefused() {
        var s = InMemoryIdentityStore.builder().user("u", "a?".toCharArray()).build();
        // Before the fix "a\uD800" encoded to "a?" and authenticated as u.
        assertTrue(s.validate("u", "a\uD800".toCharArray()).isEmpty());
        assertTrue(s.validate("nobody", "a\uD800".toCharArray()).isEmpty());
    }

    @Test
    void aValidNonAsciiPasswordStillAuthenticates() {
        var s = InMemoryIdentityStore.builder().user("u", "p\u00e4ssw\u00f6rd\uD83D\uDE00".toCharArray(), "r").build();
        assertTrue(s.validate("u", "p\u00e4ssw\u00f6rd\uD83D\uDE00".toCharArray()).isPresent());
    }
}
