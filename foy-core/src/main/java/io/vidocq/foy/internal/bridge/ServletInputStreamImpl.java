package io.vidocq.foy.internal.bridge;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@link ServletInputStream} adapting a {@link InputStream} Chappe.
 *
 * <p><em>Non-blocking I/O is not implemented in this milestone</em> — {@link #isReady()}
 * always returns {@code true} and {@link #setReadListener(ReadListener)} is not supported.</p>
 */
public final class ServletInputStreamImpl extends ServletInputStream {

    private final InputStream delegate;
    private boolean finished;

    public ServletInputStreamImpl(InputStream delegate) {
        this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
        int b = delegate.read();
        if (b == -1) finished = true;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = delegate.read(b, off, len);
        if (n == -1) finished = true;
        return n;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("non-blocking read not implemented");
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
