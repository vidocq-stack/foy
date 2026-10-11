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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The multipart storage of one request: where its parts spill, and which temporary files are still
 * the container's. {@link #close()} runs at the end of the request (asynchronous processing
 * included) and deletes them; a file a part {@code write}s out of the temporary area belongs to the
 * application and is never deleted by the container.
 *
 * <p>Temporary files are created atomically with owner-only permissions ({@code rw-------} where
 * the file system supports POSIX attributes; elsewhere the platform default of
 * {@link Files#createTempFile}, which is private to the user on the JDK's default providers) and
 * random names ({@code foy-part-<random>.tmp}). The client's file name never takes part in a path
 * built by the container.</p>
 */
public final class PartStorage implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PartStorage.class.getName());
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final String PREFIX = "foy-part-";
    private static final String SUFFIX = ".tmp";

    private final Path location;
    private final ReentrantLock lock = new ReentrantLock();
    /** Guarded by {@link #lock}. */
    private final Set<Path> owned = new LinkedHashSet<>();
    /** Guarded by {@link #lock}. */
    private boolean closed;

    /** @param location the resolved multipart location ({@link #resolveLocation}); {@code null} when none */
    public PartStorage(Path location) {
        this.location = location;
    }

    /**
     * Section 3.2 / {@code MultipartConfigElement.getLocation()}: an empty location is the context
     * temporary directory ({@code jakarta.servlet.context.tempdir}); a relative one resolves under it;
     * an absolute one is used as is. {@code null} when it needs a temporary directory there is none.
     */
    public static Path resolveLocation(String location, File tempDir) {
        Path temp = tempDir == null ? null : tempDir.toPath();
        if (location == null || location.isEmpty()) return temp;
        Path configured = Path.of(location);
        if (configured.isAbsolute()) return configured.normalize();
        return temp == null ? null : temp.resolve(configured).normalize();
    }

    /**
     * The owner-only creation attribute for a new file under {@code dir}, when its file system
     * supports POSIX permissions; none otherwise.
     */
    static FileAttribute<?>[] ownerOnly(Path dir) {
        return dir.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new FileAttribute<?>[0];
    }

    /**
     * A new, empty, owner-only ({@code rw-------} where POSIX is supported) temporary file with a
     * random name under the location, created with it when needed. The client's file name is never
     * part of it.
     *
     * @throws IOException without a location, or once the request is over
     */
    Path createTempFile() throws IOException {
        if (location == null) {
            throw new IOException("no multipart location: the context has no temporary directory "
                    + "(jakarta.servlet.context.tempdir) and the multipart-config location is empty or relative");
        }
        lock.lock();
        try {
            if (closed) throw new IOException("the request is over: no new multipart temporary file");
        } finally {
            lock.unlock();
        }
        Files.createDirectories(location);
        Path file = Files.createTempFile(location, PREFIX, SUFFIX, ownerOnly(location));
        lock.lock();
        try {
            if (!closed) {
                owned.add(file);
                return file;
            }
        } finally {
            lock.unlock();
        }
        Files.deleteIfExists(file);
        throw new IOException("the request is over: no new multipart temporary file");
    }

    /** {@code file} is no longer the container's: a part moved it to the application. */
    void disown(Path file) {
        lock.lock();
        try {
            owned.remove(file);
        } finally {
            lock.unlock();
        }
    }

    /** Deletes a container file now; a failure leaves it owned, so {@link #close()} retries. */
    void discard(Path file) throws IOException {
        Files.deleteIfExists(file);
        disown(file);
    }

    /**
     * The target of {@code Part.write(fileName)}.
     *
     * <ul>
     *   <li>An <b>absolute</b> path is used as provided, as the {@code Part.write} Javadoc specifies:
     *       the application chose it, the container neither confines nor checks it.</li>
     *   <li>A <b>relative</b> name resolves under the location and must stay strictly inside it: it is
     *       normalised, then refused when it leaves the location ({@code ..}), names the location
     *       itself, crosses a symbolic link (or other alias) whose real path leaves the location, or
     *       names a container temporary file ({@code foy-part-*.tmp}, case-insensitively) that could
     *       be another live part's.</li>
     * </ul>
     *
     * <p>The link check reads the file system once; the location is the container's (or the
     * application's) private directory, so no third party can swap a link in afterwards. Never pass
     * {@code Part.getSubmittedFileName()} here unsanitised.</p>
     *
     * @throws IOException for an invalid name, a relative name without a location, or one leaving it
     */
    Path writeTarget(String fileName) throws IOException {
        Objects.requireNonNull(fileName, "fileName");
        Path requested;
        try {
            requested = Path.of(fileName);
        } catch (InvalidPathException e) {
            throw new IOException("invalid file name: " + e.getMessage(), e);
        }
        if (requested.isAbsolute()) return requested;
        if (location == null) throw new IOException("no multipart location to resolve a relative file name against");
        Path base = location.toAbsolutePath().normalize();
        Path target = base.resolve(requested).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IOException("the file name leaves the multipart location: " + fileName);
        }
        String last = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (last.startsWith(PREFIX) && last.endsWith(SUFFIX)) {
            throw new IOException("the file name is reserved for container temporary files: " + fileName);
        }
        requireRealPathInside(base, target, fileName);
        return target;
    }

    /** Refuses {@code target} when its deepest existing ancestor below {@code base} is really elsewhere. */
    private static void requireRealPathInside(Path base, Path target, String fileName) throws IOException {
        if (!Files.isDirectory(base)) return; // nothing exists below it: the write fails on its own
        Path realBase = base.toRealPath();
        Path existing = target.getParent();
        while (!existing.equals(base) && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException dangling) {
            throw new IOException("the file name leaves the multipart location: " + fileName, dangling);
        }
        if (!real.startsWith(realBase)) {
            throw new IOException("the file name leaves the multipart location: " + fileName);
        }
    }

    /** Deletes every file still owned and refuses new ones; idempotent, never throws. */
    @Override
    public void close() {
        List<Path> files;
        lock.lock();
        try {
            closed = true;
            files = List.copyOf(owned);
            owned.clear();
        } finally {
            lock.unlock();
        }
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot delete the multipart temporary file " + f, e);
            }
        }
    }
}
