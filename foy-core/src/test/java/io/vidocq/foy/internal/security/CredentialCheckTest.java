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

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.spi.security.IdentityStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CredentialCheckTest {

    @Test
    void thePasswordIsWipedWhateverTheOutcome() {
        char[] pw = "a-secret".toCharArray();
        CredentialCheck.validate(IdentityStore.none(), "alice", pw);
        for (char c : pw) assertEquals('\0', c);
    }

    @Test
    void aStoreThatThrowsRefusesAndTheLogHoldsNoCredential() {
        IdentityStore broken = (u, p) -> { throw new IllegalStateException("db down for " + u + "/" + new String(p)); };
        try (var log = LogCapture.of(CredentialCheck.class.getName())) {
            assertTrue(CredentialCheck.validate(broken, "alice", "a-secret".toCharArray()).isEmpty());
            assertFalse(log.warnings().isEmpty());
            for (String w : log.warnings()) {
                assertFalse(w.contains("alice"), w);
                assertFalse(w.contains("a-secret"), w);
                assertFalse(w.contains("db down"), w);
            }
        }
    }

    @Test
    void aStoreReturningNullRefuses() {
        assertTrue(CredentialCheck.validate((u, p) -> null, "alice", "x".toCharArray()).isEmpty());
    }
}
