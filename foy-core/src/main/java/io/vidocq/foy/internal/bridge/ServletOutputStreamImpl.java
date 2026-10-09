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

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * {@link ServletOutputStream} which accumulates in an internal {@link ByteArrayOutputStream}.
 * The content is transferred to {@link io.vidocq.chappe.api.Response} at the end of dispatch.
 *
 * <p><em>Non-blocking I/O is not implemented in this milestone.</em></p>
 */
public final class ServletOutputStreamImpl extends ServletOutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean closed;
    private boolean discarding;
    private Runnable onFlush = () -> {};

    /** Hook executed every flush() — typically marks the committed response. */
    public void setFlushListener(Runnable onFlush) {
        this.onFlush = onFlush == null ? () -> {} : onFlush;
    }

    /** While discarding, writes are silently dropped (response closed by sendError/sendRedirect). */
    void setDiscarding(boolean discarding) { this.discarding = discarding; }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        throw new UnsupportedOperationException("non-blocking write not implemented");
    }

    @Override
    public void write(int b) throws IOException {
        if (discarding) return;
        if (closed) throw new IOException("stream closed");
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (discarding) return;
        if (closed) throw new IOException("stream closed");
        buffer.write(b, off, len);
    }

    @Override
    public void flush() {
        // No real I/O: the body is handed to the bridge at the end of dispatch.
        // Semantically though, flush() must mark the response as committed
        // (Servlet 6.1 §5.2): this is delegated to the listener.
        onFlush.run();
    }

    @Override
    public void close() {
        closed = true;
    }

    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    public int size() {
        return buffer.size();
    }

    public void resetBuffer() {
        buffer.reset();
    }
}
