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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A response whose body runs a hook once chappe released it ({@link Body#release()}): after the
 * body was written, or the exchange failed. On HTTP/1.1 chappe releases the handler's response
 * body before it drains the unread request body (or closes a failed connection), so the hook is
 * where Foy's {@link ReadPump} lets go of the request body: the response is already delivered, and
 * chappe's drain never reads concurrently with the pump.
 *
 * <p>The wrapper is a plain {@link Body} to chappe: a {@code FileBody} delegate loses chappe's
 * sendfile path (its bytes are copied through {@link Body#asInputStream()}). The bridge never wraps
 * an upgrade response, which chappe recognises by its type.</p>
 *
 * <p>Everything else (status, headers, trailers, the body's bytes and length) is the delegate's,
 * read lazily, so trailers are still evaluated after the body ({@link FoyResponse}).</p>
 */
final class InputHandBackResponse implements Response {

    private final Response delegate;
    private final Body body;

    InputHandBackResponse(Response delegate, Runnable afterRelease) {
        this.delegate = delegate;
        this.body = new HookedBody(delegate.body(), afterRelease);
    }

    @Override public StatusCode status() { return delegate.status(); }
    @Override public Headers headers() { return delegate.headers(); }
    @Override public Body body() { return body; }
    @Override public Headers trailers() { return delegate.trailers(); }

    private static final class HookedBody implements Body {
        private final Body delegate;
        private final Runnable afterRelease;
        private final AtomicBoolean released = new AtomicBoolean();

        HookedBody(Body delegate, Runnable afterRelease) {
            this.delegate = delegate == null ? Body.empty() : delegate;
            this.afterRelease = afterRelease;
        }

        @Override public long contentLength() { return delegate.contentLength(); }
        @Override public InputStream asInputStream() { return delegate.asInputStream(); }
        @Override public Flow.Publisher<ByteBuffer> asPublisher() { return delegate.asPublisher(); }

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) return;
            try {
                delegate.release();
            } catch (RuntimeException ignored) {
                // Body.release never throws; the hook must run regardless
            } finally {
                afterRelease.run();
            }
        }
    }
}
