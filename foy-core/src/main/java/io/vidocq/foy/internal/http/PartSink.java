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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The content of one part while it is parsed: in memory while it is at most {@code threshold}
 * bytes, then in a container temporary file ({@code MultipartConfigElement.getFileSizeThreshold()}:
 * 0 means any non-empty content goes to disk), never more than {@code maxFileSize} bytes ({@code -1}:
 * no limit). Closing a sink that did not {@link #finish} deletes its temporary file. Confined to
 * the parsing thread.
 */
final class PartSink implements AutoCloseable {

    private final PartStorage storage;
    private final int threshold;
    private final long maxFileSize;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private Path file;
    private OutputStream out;
    private long size;
    private boolean finished;

    PartSink(PartStorage storage, int threshold, long maxFileSize) {
        this.storage = storage;
        this.threshold = Math.max(0, threshold);
        this.maxFileSize = maxFileSize;
    }

    /** @throws MultipartLimitException once the content exceeds maxFileSize */
    void write(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return;
        size += length;
        if (maxFileSize >= 0 && size > maxFileSize) {
            throw new MultipartLimitException("a part exceeds maxFileSize (" + maxFileSize + " bytes)");
        }
        if (out == null && size > threshold) spill();
        if (out != null) out.write(bytes, offset, length);
        else memory.write(bytes, offset, length);
    }

    private void spill() throws IOException {
        file = storage.createTempFile();
        out = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING));
        memory.writeTo(out);
        memory = null;
    }

    PartImpl finish(String name, String submittedFileName, String contentType, List<PartImpl.Header> headers)
            throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
        PartImpl part = file == null
                ? PartImpl.inMemory(name, submittedFileName, contentType, headers, memory.toByteArray(), storage)
                : PartImpl.onDisk(name, submittedFileName, contentType, headers, file, size, storage);
        finished = true; // only now does the part own the file; before, close() deletes it
        return part;
    }

    @Override
    public void close() {
        if (out != null) {
            try { out.close(); } catch (IOException ignored) { /* the file is deleted below */ }
            out = null;
        }
        if (!finished && file != null) {
            try { storage.discard(file); } catch (IOException ignored) { /* PartStorage.close() retries */ }
        }
    }
}
