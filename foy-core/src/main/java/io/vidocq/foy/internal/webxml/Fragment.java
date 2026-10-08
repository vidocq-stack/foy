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
package io.vidocq.foy.internal.webxml;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/**
 * One web fragment and the jar it came from.
 *
 * @param id         stable identifier used in diagnostics
 * @param jar        URL of the jar holding the fragment
 * @param descriptor the parsed {@code META-INF/web-fragment.xml}
 */
public record Fragment(String id, URL jar, WebAppDescriptor descriptor) {

    /**
     * Normalised identity of a code source or fragment jar, so that the URL discovery sees
     * ({@code jar:file:/x.jar!/}) and a class's code source ({@code file:/x.jar}) compare equal:
     * the {@code jar:} wrapper and its {@code !/} entry part are dropped, the URI is normalised
     * ({@code file:} URIs through {@link Path}, so {@code file:/x} and {@code file:///x} agree)
     * and trailing {@code /} are stripped.
     */
    public static String sourceKey(URL url) {
        String s = url.toString();
        if (s.startsWith("jar:")) {
            s = s.substring(4);
            int bang = s.indexOf("!/");
            if (bang >= 0) s = s.substring(0, bang);
            else if (s.endsWith("!")) s = s.substring(0, s.length() - 1);
        }
        try {
            URI uri = new URI(s).normalize();
            s = "file".equalsIgnoreCase(uri.getScheme()) && uri.getAuthority() == null
                    ? Path.of(uri).normalize().toUri().toString() : uri.toString();
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            // not a hierarchical URI: compare the raw spelling
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
