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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link Part} in-memory: each part keeps its content in {@code byte[]}.
 *
 * <p>This milestone does not spill to disk — the threshold {@code fileSizeThreshold} of
 * {@link jakarta.servlet.annotation.MultipartConfig @MultipartConfig} is ignored for now.</p>
 */
public final class PartImpl implements Part {

    private final String name;
    private final String submittedFileName;
    private final String contentType;
    private final byte[] content;
    private final Map<String, java.util.List<String>> headers;

    public PartImpl(String name, String submittedFileName, String contentType,
                    byte[] content, Map<String, java.util.List<String>> headers) {
        this.name = name;
        this.submittedFileName = submittedFileName;
        this.contentType = contentType;
        this.content = content;
        Map<String, java.util.List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.putAll(headers);
        this.headers = Collections.unmodifiableMap(h);
    }

    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    @Override public String getContentType() { return contentType; }
    @Override public String getName() { return name; }
    @Override public String getSubmittedFileName() { return submittedFileName; }
    @Override public long getSize() { return content.length; }

    @Override public void write(String fileName) throws IOException {
        Files.write(Path.of(fileName), content);
    }

    @Override public void delete() { /* in-memory: no-op */ }

    @Override public String getHeader(String name) {
        var v = headers.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
    @Override public Collection<String> getHeaders(String name) {
        var v = headers.get(name);
        return v == null ? java.util.List.of() : v;
    }
    @Override public Collection<String> getHeaderNames() {
        return new java.util.LinkedHashSet<>(headers.keySet());
    }

    /** Raw content (exposed so that {@code HttpServletRequestImpl} can also use it as a parameter). */
    public byte[] bytes() { return content; }

    public static Map<String, java.util.List<String>> headersOf(LinkedHashMap<String, String> singles) {
        Map<String, java.util.List<String>> out = new LinkedHashMap<>();
        singles.forEach((k, v) -> out.put(k, java.util.List.of(v)));
        return out;
    }
}
