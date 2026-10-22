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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Reads at most {@code limit} bytes of a request body: reading or skipping one byte more throws
 * {@link RequestBodyTooLargeException}. Not thread-safe (one reader at a time, as any body stream).
 */
public final class BoundedInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    public BoundedInputStream(InputStream in, long limit) {
        Objects.requireNonNull(in, "in");
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative: " + limit);
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) counted(1);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) counted(n);
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = in.skip(n);
        if (skipped > 0) counted(skipped);
        return skipped;
    }

    @Override public boolean markSupported() { return false; }
    @Override public void mark(int readLimit) { /* not supported */ }
    @Override public void reset() throws IOException { throw new IOException("mark/reset not supported"); }

    private void counted(long n) throws RequestBodyTooLargeException {
        count += n;
        if (count > limit) throw new RequestBodyTooLargeException(limit);
    }
}
