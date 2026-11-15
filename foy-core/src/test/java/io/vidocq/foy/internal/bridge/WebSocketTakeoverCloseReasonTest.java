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
package io.vidocq.foy.internal.bridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reasons the takeover gives its listener for a close the transport initiated on a failure: they name the
 * failure and fit the 123 bytes of a close reason, which {@code jakarta.websocket.CloseReason} refuses past
 * (BUG-20261115-08).
 */
class WebSocketTakeoverCloseReasonTest {

    @Test
    void aShortPhraseIsKept() {
        assertEquals("protocol error: x", WebSocketTakeover.boundedReason("protocol error: x"));
    }

    @Test
    void aLongPhraseIsCutTo123Bytes() {
        assertEquals("a".repeat(123), WebSocketTakeover.boundedReason("a".repeat(200)));
    }

    /** 62 two-byte characters are 124 bytes: the 62nd would be split at 123, so it goes whole. */
    @Test
    void theCutNeverSplitsACharacter() {
        String cut = WebSocketTakeover.boundedReason("é".repeat(62));

        assertEquals("é".repeat(61), cut);
        assertEquals(122, cut.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * A character outside the Basic Multilingual Plane is 4 bytes in UTF-8 and 2 {@code char}s: after 120
     * bytes it ends at byte 124, so it goes whole, and no lone surrogate is left at the end.
     */
    @Test
    void theCutNeverSplitsACharacterOfFourBytes() {
        String grinning = new String(Character.toChars(0x1F600));

        String cut = WebSocketTakeover.boundedReason("a".repeat(120) + grinning);

        assertEquals("a".repeat(120), cut);
        assertEquals(120, cut.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aProtocolErrorIsNamedByItsMessageOrItsClass() {
        assertEquals("protocol error: Invalid UTF-8 in TEXT frame",
                WebSocketTakeover.failureReason(new IOException("Invalid UTF-8 in TEXT frame")));
        assertEquals("protocol error: java.io.IOException", WebSocketTakeover.failureReason(new IOException()));
    }

    @Test
    void anotherFailureIsNamedAsTheCallbackThatFailed() {
        assertEquals("callback failed: java.lang.IllegalStateException: boom",
                WebSocketTakeover.failureReason(new IllegalStateException("boom")));
    }

    @Test
    void aFailureReasonFitsACloseReason() {
        String reason = WebSocketTakeover.failureReason(new IOException("x".repeat(500)));

        assertEquals(123, reason.getBytes(StandardCharsets.UTF_8).length);
    }
}
