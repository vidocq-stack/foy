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

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A servlet response carrying trailer fields ({@code HttpServletResponse.setTrailerFields}).
 * Chappe queries {@link #trailers()} once the body is fully consumed (after the last chunk on
 * HTTP/1.1, as the final HEADERS frame on HTTP/2), so the application supplier runs only then,
 * and at most once.
 */
final class FoyResponse implements Response {

    private static final System.Logger LOG = System.getLogger(FoyResponse.class.getName());

    /** RFC 9110 §6.5.1: framing, routing, control and authentication fields never sent as trailers. */
    private static final Set<String> FORBIDDEN = Set.of(
            "transfer-encoding", "content-length", "host", "content-type", "content-encoding",
            "content-range", "trailer", "authorization", "set-cookie", "cache-control", "expect",
            "max-forwards", "pragma", "range", "te", "age", "expires", "date", "location",
            "retry-after", "vary", "warning", "proxy-authenticate", "www-authenticate");

    private final StatusCode status;
    private final Headers headers;
    private final Body body;
    private final Supplier<Map<String, String>> trailerSupplier;
    private Headers trailers;

    FoyResponse(StatusCode status, Headers headers, Body body, Supplier<Map<String, String>> trailerSupplier) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.trailerSupplier = trailerSupplier;
    }

    @Override public StatusCode status() { return status; }
    @Override public Headers headers() { return headers; }
    @Override public Body body() { return body; }

    @Override
    public synchronized Headers trailers() {
        if (trailers == null) trailers = evaluate();
        return trailers;
    }

    private Headers evaluate() {
        Map<String, String> fields;
        try {
            fields = trailerSupplier.get();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "the trailer fields supplier failed; no trailer sent", e);
            return Headers.empty();
        }
        if (fields == null || fields.isEmpty()) return Headers.empty();
        var builder = Headers.builder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (name == null || name.isEmpty() || value == null) continue;
            if (FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))) continue;
            try {
                HttpServletResponseImpl.checkHeaderText("name", name);
                HttpServletResponseImpl.checkHeaderText(name, value);
            } catch (IllegalArgumentException invalid) {
                // Thrown at the application for a header; a trailer is only dropped, the body is out.
                LOG.log(System.Logger.Level.WARNING, "invalid trailer field dropped: " + invalid.getMessage());
                continue;
            }
            builder.add(name, value);
        }
        return builder.build();
    }
}
