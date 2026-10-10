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
}
