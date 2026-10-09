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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@code ServletContext.getRealPath}: a file-system path only for a directory-backed resource that exists. */
class RealPathTest {

    /** Serves {@code root} as a directory, or answers every URL with {@code jar:}. */
    record Provider(Path root, boolean jar) implements VidocqServletContext.ResourceProvider {
        @Override public Set<String> listPaths(String path) { return Set.of(); }
        @Override public InputStream openStream(String path) { return null; }
        @Override public URL toUrl(String path) {
            try {
                if (jar) return URI.create("jar:file:/tmp/app.jar!" + path).toURL();
                Path p = root.resolve(path.substring(1));
                return Files.exists(p) ? p.toUri().toURL() : null;
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void directoryBackedExistingResourceHasARealPath(@TempDir Path root) throws IOException {
        Path file = Files.writeString(Files.createDirectories(root.resolve("css")).resolve("site.css"), "x");
        var ctx = new VidocqServletContext("/ctx");
        ctx.setResourceProvider(new Provider(root, false));
        assertEquals(file.toAbsolutePath().toString(), ctx.getRealPath("/css/site.css"));
        assertEquals(file.toAbsolutePath().toString(), ctx.getRealPath("css/site.css"), "a leading '/' is implied");
        assertEquals(root.resolve("css").toAbsolutePath().toString(),
                Path.of(ctx.getRealPath("/css/")).toString(), "a directory has a real path too");
        assertNull(ctx.getRealPath("/missing.txt"));
        assertNull(ctx.getRealPath(null));
        assertEquals(file.toAbsolutePath().toString(), ctx.getRealPath("/css/../css/./site.css"),
                "'.' and '..' inside the root are resolved");
        assertNull(ctx.getRealPath("/../site.css"), "above the root");
        assertNull(ctx.getRealPath("/css/../../site.css"), "above the root");
        assertNull(ctx.getRealPath("/css\\site.css"), "backslash");
    }

    @Test
    void canonicalResourcePathResolvesDotSegments() {
        assertEquals("/a/b", VidocqServletContext.canonicalResourcePath("/a/./b"));
        assertEquals("/b", VidocqServletContext.canonicalResourcePath("/a/../b"));
        assertEquals("/a/", VidocqServletContext.canonicalResourcePath("/a/b/.."));
        assertEquals("/", VidocqServletContext.canonicalResourcePath("/a/.."));
        assertNull(VidocqServletContext.canonicalResourcePath("/.."));
    }

    @Test
    void archiveBackedOrMissingProviderHasNoRealPath() {
        var ctx = new VidocqServletContext("/ctx");
        assertNull(ctx.getRealPath("/index.html"), "no provider");
        ctx.setResourceProvider(new Provider(null, true));
        assertNull(ctx.getRealPath("/index.html"), "inside a jar");
    }
}
