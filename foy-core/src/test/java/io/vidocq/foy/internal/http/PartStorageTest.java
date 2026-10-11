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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PartStorageTest {

    @TempDir Path temp;

    static long count(Path dir) throws IOException {
        try (var files = Files.list(dir)) { return files.count(); }
    }

    @Test
    void theLocationResolvesAgainstTheContextTempDir() {
        assertEquals(temp, PartStorage.resolveLocation("", temp.toFile()));
        assertEquals(temp, PartStorage.resolveLocation(null, temp.toFile()));
        assertEquals(temp.resolve("uploads"), PartStorage.resolveLocation("uploads", temp.toFile()));
        Path absolute = temp.resolve("abs").toAbsolutePath();
        assertEquals(absolute, PartStorage.resolveLocation(absolute.toString(), null), "absolute: used as is");
        assertNull(PartStorage.resolveLocation("uploads", null), "relative without a temp dir: none");
        assertNull(PartStorage.resolveLocation("", null));
    }

    @Test
    void spilledFilesAreOwnerOnlyAndRandomlyNamed() throws IOException {
        Path file = new PartStorage(temp).createTempFile();
        assertEquals(temp, file.getParent());
        assertTrue(file.getFileName().toString().matches("foy-part-.*\\.tmp"), file.toString());
        if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void theLocationIsCreatedOnTheFirstSpill() throws IOException {
        Path nested = temp.resolve("a/b");
        new PartStorage(nested).createTempFile();
        assertEquals(1, count(nested));
    }

    @Test
    void closeDeletesEveryOwnedFileAndRefusesNewOnes() throws IOException {
        var storage = new PartStorage(temp);
        storage.createTempFile();
        storage.createTempFile();
        storage.close();
        storage.close();
        assertEquals(0, count(temp));
        assertThrows(IOException.class, storage::createTempFile);
        assertEquals(0, count(temp));
    }

    @Test
    void noLocationFailsTheSpill() {
        assertThrows(IOException.class, () -> new PartStorage(null).createTempFile());
    }

    @Test
    void writeTargetsStayInsideTheLocation() throws IOException {
        var storage = new PartStorage(temp);
        assertEquals(temp.resolve("a.txt"), storage.writeTarget("a.txt"));
        assertEquals(temp.resolve("sub/a.txt"), storage.writeTarget("sub/../sub/a.txt"));
        assertThrows(IOException.class, () -> storage.writeTarget("../escape.txt"));
        assertThrows(IOException.class, () -> storage.writeTarget("sub/../../escape.txt"));
        assertThrows(IOException.class, () -> storage.writeTarget(""));
        Path absolute = temp.getParent().resolve("elsewhere.txt").toAbsolutePath();
        assertEquals(absolute, storage.writeTarget(absolute.toString()), "absolute paths are used as provided");
    }

    @Test
    void writeTargetsRefuseADirectoryLinkLeavingTheLocation(@TempDir Path outside) throws IOException {
        Path location = Files.createDirectory(temp.resolve("location"));
        try {
            Files.createSymbolicLink(location.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException noLinks) {
            assumeTrue(false, "symbolic links not supported: " + noLinks);
        }
        var storage = new PartStorage(location);
        assertThrows(IOException.class, () -> storage.writeTarget("link/escape.txt"));
        assertThrows(IOException.class, () -> storage.writeTarget("link/missing/escape.txt"));
    }

    @Test
    void writeTargetsRefuseTheNamesOfContainerTemporaryFiles() throws IOException {
        var storage = new PartStorage(temp);
        Path live = storage.createTempFile();
        assertThrows(IOException.class, () -> storage.writeTarget(live.getFileName().toString()));
        assertThrows(IOException.class, () -> storage.writeTarget("sub/foy-part-123.tmp"));
    }
}
