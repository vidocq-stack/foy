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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedInputStreamTest {

    private static InputStream bytes(int n) { return new ByteArrayInputStream(new byte[n]); }

    @Test
    void aBodyUpToTheLimitIsReadWhole() throws IOException {
        assertEquals(10, new BoundedInputStream(bytes(10), 10).readAllBytes().length);
    }

    @Test
    void oneByteMoreFailsTheRead() {
        var e = assertThrows(RequestBodyTooLargeException.class,
                () -> new BoundedInputStream(bytes(11), 10).readAllBytes());
        assertEquals(10, e.limit());
    }

    @Test
    void singleByteReadsAreCounted() throws IOException {
        var in = new BoundedInputStream(bytes(3), 2);
        in.read();
        in.read();
        assertThrows(RequestBodyTooLargeException.class, in::read);
    }

    @Test
    void skippedBytesAreCounted() {
        var in = new BoundedInputStream(bytes(20), 10);
        assertThrows(RequestBodyTooLargeException.class, () -> in.skip(15));
    }

    @Test
    void aZeroLimitAcceptsOnlyAnEmptyBody() throws IOException {
        assertEquals(0, new BoundedInputStream(bytes(0), 0).readAllBytes().length);
        assertThrows(RequestBodyTooLargeException.class, () -> new BoundedInputStream(bytes(1), 0).read());
    }
}
