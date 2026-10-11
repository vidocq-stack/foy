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

import jakarta.servlet.http.Part;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link Part} of a {@code multipart/form-data} request (Servlet 6.1 section 3.2). Its content is in
 * memory up to the {@code fileSizeThreshold}, else in a container temporary file ({@link PartSink});
 * the container deletes its temporary files at the end of the request ({@link PartStorage#close()}).
 *
 * <p>{@link #getSubmittedFileName()} is the client's value, raw: it may hold {@code ../}, a drive
 * letter or a backslash path. The container never builds a path from it; an application must
 * sanitise it first, and in particular never pass it to {@link #write} as is.</p>
 */
public final class PartImpl implements Part {

    /** One part header as received; names compare case-insensitively. */
    public record Header(String name, String value) {
        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    private final String name;
    private final String submittedFileName;
    private final String contentType;
    /** Values by lower-case name; {@link #headerNames} keeps each name's first spelling, in order. */
    private final Map<String, List<String>> headers;
    private final List<String> headerNames;
    private final long size;
    private final PartStorage storage;
    private final ReentrantLock lock = new ReentrantLock();
    /** Guarded by {@link #lock}: the content in memory, or {@code null}. */
    private byte[] memory;
    /** Guarded by {@link #lock}: the file holding the content, or {@code null}. */
    private Path file;
    /** Guarded by {@link #lock}: {@link #file} is a container temporary file. */
    private boolean temporary;
    /** Guarded by {@link #lock}. */
    private boolean deleted;

    private PartImpl(String name, String submittedFileName, String contentType, List<Header> received,
                     byte[] memory, Path file, long size, PartStorage storage) {
        this.name = Objects.requireNonNull(name, "name");
        this.submittedFileName = submittedFileName;
        this.contentType = contentType;
        var values = new LinkedHashMap<String, List<String>>();
        var names = new ArrayList<String>();
        for (Header h : received) {
            String key = h.name().toLowerCase(Locale.ROOT);
            if (!values.containsKey(key)) names.add(h.name());
            values.computeIfAbsent(key, _ -> new ArrayList<>()).add(h.value());
        }
        values.replaceAll((k, v) -> List.copyOf(v));
        this.headers = Map.copyOf(values);
        this.headerNames = List.copyOf(names);
        this.memory = memory;
        this.file = file;
        this.temporary = file != null;
        this.size = size;
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    static PartImpl inMemory(String name, String submittedFileName, String contentType, List<Header> headers,
                             byte[] content, PartStorage storage) {
        return new PartImpl(name, submittedFileName, contentType, headers, content, null, content.length, storage);
    }

    static PartImpl onDisk(String name, String submittedFileName, String contentType, List<Header> headers,
                           Path file, long size, PartStorage storage) {
        return new PartImpl(name, submittedFileName, contentType, headers, null, file, size, storage);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        lock.lock();
        try {
            if (deleted) throw new IOException("the part " + name + " was deleted");
            return memory != null ? new ByteArrayInputStream(memory) : Files.newInputStream(file);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getContentType() { return contentType; }
    @Override public String getName() { return name; }
    @Override public String getSubmittedFileName() { return submittedFileName; }
    @Override public long getSize() { return size; }

    /**
     * Stores the content at {@code fileName}.
     *
     * <ul>
     *   <li>A <b>relative</b> name resolves under the multipart location and may not leave it: no
     *       {@code ..} escape, no symbolic link leading out of it, not the name of a container
     *       temporary file ({@link PartStorage}). A link at the target itself is never followed.</li>
     *   <li>An <b>absolute</b> path is used as provided, as the {@code Part.write} Javadoc says
     *       ("the file will be created relative to the location ... if the path is relative"): it is
     *       the application's own choice and the container does not confine it.</li>
     * </ul>
     *
     * <p>A temporary file is moved there (atomic when on the same file system) and becomes the
     * application's: the container no longer deletes it. A part held in memory is written to a new
     * file created owner-only ({@code rw-------} where POSIX is supported), like the moved temporary
     * files; a part already written is copied.</p>
     */
    @Override
    public void write(String fileName) throws IOException {
        Path target = storage.writeTarget(fileName);
        boolean confined = !Path.of(fileName).isAbsolute(); // writeTarget accepted it: a valid path
        lock.lock();
        try {
            if (deleted) throw new IOException("the part " + name + " was deleted");
            if (memory != null) {
                writeMemory(target, confined);
            } else if (temporary) {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                storage.disown(file);
                file = target;
                temporary = false;
            } else if (!file.equals(target)) {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Guarded by {@link #lock}. A confined target that is a symbolic link fails instead of being followed. */
    private void writeMemory(Path target, boolean confined) throws IOException {
        Set<OpenOption> options = confined
                ? Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS)
                : Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Path dir = target.toAbsolutePath().getParent();
        try (SeekableByteChannel channel = Files.newByteChannel(target, options, PartStorage.ownerOnly(dir))) {
            ByteBuffer buffer = ByteBuffer.wrap(memory);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    /**
     * Deletes the container's storage of this part: its content in memory and its temporary file. A
     * file the application wrote with {@link #write} is the application's and stays.
     */
    @Override
    public void delete() throws IOException {
        lock.lock();
        try {
            if (deleted) return;
            deleted = true;
            memory = null;
            if (temporary) {
                temporary = false;
                storage.discard(file);
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@link #delete()}, ignoring a failure: a failed parse; {@link PartStorage#close()} retries. */
    void discard() {
        try { delete(); } catch (IOException ignored) { /* retried at the end of the request */ }
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null ? null : values.getFirst();
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public Collection<String> getHeaderNames() { return headerNames; }

    /** The content decoded with {@code charset}: the value of a field exposed as a request parameter. */
    public String text(Charset charset) throws IOException {
        try (InputStream in = getInputStream()) {
            return new String(in.readAllBytes(), charset);
        }
    }

    /** The {@code charset} parameter of this part's Content-Type when the JDK supports it, else {@code fallback}. */
    public Charset charset(Charset fallback) {
        if (contentType == null) return fallback;
        var params = new java.util.HashMap<String, String>();
        try {
            MultipartParser.parseParameters(contentType, params);
        } catch (MalformedMultipartException malformed) {
            return fallback;
        }
        String value = params.get("charset");
        if (value == null) return fallback;
        try {
            return Charset.forName(value);
        } catch (RuntimeException unsupported) {
            return fallback;
        }
    }
}
