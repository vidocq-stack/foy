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
package io.vidocq.foy.spi.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The request being served by the current thread, for code that is not handed it: a security context that answers
 * {@code getCallerPrincipal()}, a producer of a request-bound object.
 *
 * <p>Foy binds it around the whole processing of a request on the thread that serves it (listeners, filters, servlet,
 * dispatches included); inside a dispatch it is the request of the dispatch. It is empty outside a request and on a
 * thread the application started itself: pass the request on explicitly there. It is a {@link ScopedValue}, so it
 * needs no clean-up and cannot leak from one request to the next.
 */
public final class CurrentRequest {

    private static final ScopedValue<Supplier<HttpServletRequest>> SOURCE = ScopedValue.newInstance();

    private CurrentRequest() {
    }

    /** The request being served by the current thread, or empty when there is none. */
    public static Optional<HttpServletRequest> get() {
        return SOURCE.isBound() ? Optional.ofNullable(SOURCE.get().get()) : Optional.empty();
    }

    /**
     * Runs {@code body} with {@code source} as the answer of {@link #get()}. For the web container only: an
     * application never binds it. {@code source} is asked each time, so a dispatch can change the answer.
     */
    public static <T> T callWhere(Supplier<HttpServletRequest> source, Supplier<T> body) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(body, "body");
        return ScopedValue.where(SOURCE, source).call(body::get);
    }
}
