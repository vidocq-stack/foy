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
 * {@link Part} in-memory : chaque partie conserve son contenu en {@code byte[]}.
 *
 * <p>Ce jalon ne spill pas sur disque — le seuil {@code fileSizeThreshold} de
 * {@link jakarta.servlet.annotation.MultipartConfig @MultipartConfig} est ignoré pour l'instant.</p>
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

    /** Contenu brut (exposé pour que {@code HttpServletRequestImpl} puisse aussi l'utiliser comme paramètre). */
    public byte[] bytes() { return content; }

    public static Map<String, java.util.List<String>> headersOf(LinkedHashMap<String, String> singles) {
        Map<String, java.util.List<String>> out = new LinkedHashMap<>();
        singles.forEach((k, v) -> out.put(k, java.util.List.of(v)));
        return out;
    }
}
