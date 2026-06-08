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
package io.vidocq.foy.internal.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPageRegistryTest {

    @Test
    void statusCodeLookupReturnsRegisteredPath() {
        var reg = new ErrorPageRegistry().register(404, "/404.html");
        assertEquals("/404.html", reg.findByStatus(404).orElseThrow());
    }

    @Test
    void statusCodeOutOf4xx5xxIsRejected() {
        var reg = new ErrorPageRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.register(200, "/ok"));
        assertThrows(IllegalArgumentException.class, () -> reg.register(600, "/high"));
    }

    @Test
    void exceptionLookupReturnsMostSpecific() {
        var reg = new ErrorPageRegistry()
                .register(RuntimeException.class, "/rt.html")
                .register(IllegalArgumentException.class, "/iae.html");
        assertEquals("/iae.html", reg.findByException(new IllegalArgumentException()).orElseThrow());
        assertEquals("/rt.html", reg.findByException(new IllegalStateException()).orElseThrow());
    }

    @Test
    void exceptionLookupWalksSuperclassChain() {
        var reg = new ErrorPageRegistry().register(Throwable.class, "/any.html");
        assertEquals("/any.html", reg.findByException(new IOException()).orElseThrow());
    }

    @Test
    void exceptionLookupInspectsCauseChain() {
        var reg = new ErrorPageRegistry().register(NumberFormatException.class, "/nfe.html");
        var wrapper = new RuntimeException("wrapper", new NumberFormatException("root"));
        assertEquals("/nfe.html", reg.findByException(wrapper).orElseThrow());
    }

    @Test
    void absentMappingReturnsEmpty() {
        var reg = new ErrorPageRegistry();
        assertTrue(reg.findByStatus(500).isEmpty());
        assertTrue(reg.findByException(new RuntimeException()).isEmpty());
    }

    @Test
    void sizeReportsTotal() {
        var reg = new ErrorPageRegistry()
                .register(404, "/404.html")
                .register(500, "/500.html")
                .register(RuntimeException.class, "/rt.html");
        assertEquals(3, reg.size());
    }
}
