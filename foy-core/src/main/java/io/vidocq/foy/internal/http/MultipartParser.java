package io.vidocq.foy.internal.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Parser {@code multipart/form-data} compliant with minimum RFC 7578 §4.
 *
 * <p>MVP: full loading of the body into memory, split on {@code --boundary}, parse
 * headers on each side, extraction of content up to the next boundary.
 * Does not support partial streaming or disk transfer.</p>
 */
public final class MultipartParser {

    private MultipartParser() {}

    /** Extracts the boundary value from a multipart/form-data Content-Type. */
    public static String extractBoundary(String contentType) {
        if (contentType == null) return null;
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("boundary=");
        if (idx < 0) return null;
        String b = contentType.substring(idx + "boundary=".length()).trim();
        int semi = b.indexOf(';');
        if (semi >= 0) b = b.substring(0, semi).trim();
        if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
            b = b.substring(1, b.length() - 1);
        }
        return b;
    }

    public static List<PartImpl> parse(InputStream body, String boundary) throws IOException {
        byte[] all = readAll(body);
        return parse(all, boundary);
    }

    public static List<PartImpl> parse(byte[] all, String boundary) {
        List<PartImpl> parts = new ArrayList<>();
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);

        int pos = indexOf(all, delim, 0);
        if (pos < 0) return parts;

        while (pos < all.length) {
            int nextDelim = indexOf(all, delim, pos + delim.length);
            if (nextDelim < 0) break;

            // Positionner au début des headers de la part (après le CRLF qui suit --boundary).
            int headerStart = pos + delim.length;
            // Closing boundary "--boundary--" → fin.
            if (headerStart + 2 <= all.length
                    && all[headerStart] == '-' && all[headerStart + 1] == '-') {
                break;
            }
            // Consommer CRLF après --boundary.
            if (headerStart + 2 <= all.length
                    && all[headerStart] == '\r' && all[headerStart + 1] == '\n') {
                headerStart += 2;
            }

            // Trouver fin des headers : CRLF CRLF
            int bodyStart = findBlankLine(all, headerStart, nextDelim);
            if (bodyStart < 0) { pos = nextDelim; continue; }

            String headersRaw = new String(all, headerStart, bodyStart - headerStart - 4,
                    StandardCharsets.US_ASCII);
            int bodyEnd = nextDelim;
            // Retire le CRLF juste avant le delimiter suivant.
            if (bodyEnd - 2 >= bodyStart
                    && all[bodyEnd - 2] == '\r' && all[bodyEnd - 1] == '\n') {
                bodyEnd -= 2;
            }
            byte[] content = new byte[bodyEnd - bodyStart];
            System.arraycopy(all, bodyStart, content, 0, content.length);

            PartImpl part = buildPart(headersRaw, content);
            if (part != null) parts.add(part);

            pos = nextDelim;
        }
        return parts;
    }

    private static PartImpl buildPart(String headersRaw, byte[] content) {
        LinkedHashMap<String, String> singles = new LinkedHashMap<>();
        String name = null;
        String filename = null;
        String contentType = null;
        for (String line : headersRaw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String k = line.substring(0, colon).trim();
            String v = line.substring(colon + 1).trim();
            singles.put(k, v);
            if ("Content-Disposition".equalsIgnoreCase(k)) {
                name = extractParam(v, "name");
                filename = extractParam(v, "filename");
            } else if ("Content-Type".equalsIgnoreCase(k)) {
                contentType = v;
            }
        }
        if (name == null) return null;
        return new PartImpl(name, filename, contentType, content, PartImpl.headersOf(singles));
    }

    private static String extractParam(String header, String key) {
        int idx = header.toLowerCase(Locale.ROOT).indexOf(key + "=");
        if (idx < 0) return null;
        int start = idx + key.length() + 1;
        if (start >= header.length()) return null;
        if (header.charAt(start) == '"') {
            int end = header.indexOf('"', start + 1);
            if (end < 0) return null;
            return header.substring(start + 1, end);
        }
        int end = start;
        while (end < header.length() && header.charAt(end) != ';' && header.charAt(end) != ' ') end++;
        return header.substring(start, end);
    }

    private static int findBlankLine(byte[] buf, int from, int until) {
        for (int i = from; i + 3 < until; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
