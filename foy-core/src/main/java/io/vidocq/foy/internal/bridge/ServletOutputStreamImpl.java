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
    private Runnable onFlush = () -> {};

    /** Hook executed every flush() — typically marks the committed response. */
    public void setFlushListener(Runnable onFlush) {
        this.onFlush = onFlush == null ? () -> {} : onFlush;
    }

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
        if (closed) throw new IOException("stream closed");
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("stream closed");
        buffer.write(b, off, len);
    }

    @Override
    public void flush() {
        // En vrai, pas d'I/O : le body est transféré au bridge en fin de dispatch.
        // Mais sémantiquement, flush() doit marquer la réponse comme committed
        // (Servlet 6.1 §5.2) — on délègue ça au listener.
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
