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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestLimitsTest {

    @Test
    void defaultsAreTheDocumentedOnes() {
        assertEquals(10L * 1024 * 1024, RequestLimits.DEFAULTS.maxChunkedRequestBodySize());
        assertEquals(2L * 1024 * 1024, RequestLimits.DEFAULTS.maxFormBodySize());
        assertEquals(50, RequestLimits.DEFAULTS.maxMultipartParts());
        assertEquals(8 * 1024, RequestLimits.DEFAULTS.maxMultipartPartHeaderSize());
    }

    @Test
    void minusOneLiftsACeilingAndAnythingLowerIsRefused() {
        var unlimited = RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(-1).withMaxFormBodySize(-1)
                .withMaxMultipartParts(-1);
        assertEquals(-1, unlimited.maxChunkedRequestBodySize());
        assertEquals(-1, unlimited.maxFormBodySize());
        assertEquals(-1, unlimited.maxMultipartParts());
        assertEquals(0, RequestLimits.DEFAULTS.withMaxFormBodySize(0).maxFormBodySize(), "0 accepts only an empty body");
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxFormBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxMultipartParts(0));
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxMultipartPartHeaderSize(0));
    }
}
