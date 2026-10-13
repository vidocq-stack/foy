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
package io.vidocq.foy.internal.container;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourcePaths#realNameMatches} on any file system (BUG-20261011-05): the file is always
 * resolved under its real spelling, so the comparison with the requested relative path is what is
 * pinned here, whether or not the file system folds case.
 */
class ResourcePathsTest {

    @TempDir Path root;
    private Path file;

    @BeforeEach
    void files() throws IOException {
        Files.createDirectories(root.resolve("static"));
        file = Files.writeString(root.resolve("static/x.txt"), "x");
    }

    @Test
    void theExactRelativeSpellingMatches() {
        assertTrue(ResourcePaths.realNameMatches(file, "static/x.txt"));
    }

    @Test
    void aDirectoryInAnotherCaseDoesNotMatch() {
        assertFalse(ResourcePaths.realNameMatches(file, "STATIC/x.txt"));
    }

    @Test
    void aFileNameInAnotherCaseDoesNotMatch() {
        assertFalse(ResourcePaths.realNameMatches(file, "static/X.txt"));
    }

    @Test
    void aMissingFileMatchesSoTheCallerReportsAbsence() {
        assertTrue(ResourcePaths.realNameMatches(root.resolve("static/missing.txt"), "static/missing.txt"));
    }

    @Test
    void aRelativePathLongerThanTheRealPathDoesNotMatch() {
        String tooLong = "a/".repeat(file.toAbsolutePath().getNameCount() + 1) + "x.txt";
        assertFalse(ResourcePaths.realNameMatches(file, tooLong));
    }
}
