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

import io.vidocq.chappe.api.Headers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chappe {@code Response} built for a servlet response carrying trailer fields. Chappe's
 * HTTP/2 writer sends {@code trailers()} as the final HEADERS frame, and no HTTP/2 client is
 * available to the tests (the JDK client cannot speak h2c with prior knowledge), so the HTTP/2
 * side is covered here at the {@code Response} contract both writers consume.
 */
class ResponseTrailerFieldsTest {

    private static List<String> lines(Headers headers) {
        var out = new ArrayList<String>();
        for (var e : headers) out.add(e.name() + ": " + e.value());
        return out;
    }

    @Test
    void trailersOverHttp2_suppliedLazilyOnceAfterTheBody() {
        var res = new HttpServletResponseImpl();
        var calls = new AtomicInteger();
        res.setTrailerFields(() -> {
            calls.incrementAndGet();
            return Map.of("grpc-status", "0");
        });
        var response = ChappeServletBridge.toChappeResponse(res);
        assertEquals(0, calls.get(), "the supplier runs only once the body is complete");
        assertEquals(-1, response.body().contentLength(), "a response with trailers is chunked");
        assertEquals(List.of("grpc-status: 0"), lines(response.trailers()));
        assertEquals(List.of("grpc-status: 0"), lines(response.trailers()));
        assertEquals(1, calls.get());
    }

    @Test
    void noSupplierMeansNoTrailersAndAKnownLength() {
        var res = new HttpServletResponseImpl();
        var response = ChappeServletBridge.toChappeResponse(res);
        assertTrue(response.trailers().isEmpty());
        assertEquals(0, response.body().contentLength());
        assertNull(res.getTrailerFields());
    }

    @Test
    void aFailingOrNullSupplierSendsNoTrailers() {
        var res = new HttpServletResponseImpl();
        res.setTrailerFields(() -> null);
        assertTrue(ChappeServletBridge.toChappeResponse(res).trailers().isEmpty());
        var failing = new HttpServletResponseImpl();
        failing.setTrailerFields(() -> { throw new IllegalStateException("boom"); });
        assertTrue(ChappeServletBridge.toChappeResponse(failing).trailers().isEmpty());
    }

    @Test
    void getTrailerFieldsReturnsTheSupplier() {
        var res = new HttpServletResponseImpl();
        java.util.function.Supplier<Map<String, String>> supplier = Map::of;
        res.setTrailerFields(supplier);
        assertSame(supplier, res.getTrailerFields());
    }
}
