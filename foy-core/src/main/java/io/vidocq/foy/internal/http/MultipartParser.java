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

import jakarta.servlet.MultipartConfigElement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streaming {@code multipart/form-data} parser (RFC 7578, RFC 2046 section 5.1).
 *
 * <p>The body is read once, through an 8 KiB window: a delimiter is {@code CRLF--boundary} (the
 * first one may open the body), found in linear time (Knuth-Morris-Pratt), so a file that holds the
 * boundary text elsewhere is never split. Each part's content goes to a {@link PartSink} (memory up
 * to the threshold, then a temporary file). Part headers are decoded as UTF-8 (ISO-8859-1 when they
 * are not valid UTF-8), kept in order with their repetitions; {@code filename*} (RFC 8187) wins over
 * {@code filename}. A truncated or malformed body is a {@link MalformedMultipartException}; a body
 * beyond a limit a {@link MultipartLimitException}; either way the files already written are deleted.
 * A part without a {@code form-data} disposition or a {@code name} is read, counted and dropped.</p>
 */
public final class MultipartParser {

    /** Read-ahead window, and the largest chunk handed to a part at once. */
    private static final int BUFFER = 8192;

    /**
     * The limits of one parse ({@code -1}: none): the servlet's {@code maxFileSize},
     * {@code maxRequestSize} and {@code fileSizeThreshold}, the container's part count and part
     * header size.
     */
    public record Limits(long maxFileSize, long maxRequestSize, int fileSizeThreshold, int maxParts,
                         int maxPartHeaderSize) {

        public static Limits of(MultipartConfigElement config, RequestLimits container) {
            return new Limits(config.getMaxFileSize(), config.getMaxRequestSize(),
                    Math.max(0, config.getFileSizeThreshold()), container.maxMultipartParts(),
                    container.maxMultipartPartHeaderSize());
        }
    }

    private MultipartParser() {}

    /**
     * The boundary of a {@code multipart/form-data} Content-Type: 1 to 70 RFC 2046 bchars, not ending
     * with a space, quoted or not; {@code null} for another media type or an invalid boundary.
     */
    public static String extractBoundary(String contentType) {
        if (contentType == null) return null;
        var params = new HashMap<String, String>();
        try {
            if (!"multipart/form-data".equals(parseParameters(contentType, params))) return null;
        } catch (MalformedMultipartException _) {
            return null;
        }
        String boundary = params.get("boundary");
        if (boundary == null || boundary.isEmpty() || boundary.length() > 70 || boundary.endsWith(" ")) return null;
        for (int i = 0; i < boundary.length(); i++) {
            if (!isBoundaryChar(boundary.charAt(i))) return null;
        }
        return boundary;
    }

    /**
     * Parses {@code body} up to its close delimiter (the epilogue is left unread).
     *
     * @throws MalformedMultipartException for a truncated or malformed body
     * @throws MultipartLimitException     beyond a limit
     * @throws IOException                 when the body or a temporary file fails
     */
    public static List<PartImpl> parse(InputStream body, String boundary, Limits limits, PartStorage storage)
            throws IOException {
        var in = new CountingReader(body, limits.maxRequestSize());
        var scanner = new DelimiterScanner(("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII));
        var parts = new ArrayList<PartImpl>();
        boolean complete = false;
        try {
            // The preamble is discarded; the first delimiter may open the body, as if a CRLF preceded it.
            if (!scanner.scan(in, null, true)) throw new MalformedMultipartException("no multipart boundary in the body");
            int seen = 0;
            while (!atCloseDelimiter(in)) {
                if (limits.maxParts() >= 0 && seen == limits.maxParts()) {
                    throw new MultipartLimitException("the request has more than " + limits.maxParts() + " parts");
                }
                seen++;
                List<PartImpl.Header> headers = readHeaders(in, limits.maxPartHeaderSize());
                Disposition disposition = Disposition.of(headers);
                if (disposition == null) {
                    if (!scanner.scan(in, null, false)) throw new MalformedMultipartException("truncated multipart body");
                    continue;
                }
                try (var sink = new PartSink(storage, limits.fileSizeThreshold(), limits.maxFileSize())) {
                    if (!scanner.scan(in, sink, false)) throw new MalformedMultipartException("truncated multipart body");
                    parts.add(sink.finish(disposition.name(), disposition.fileName(), header(headers, "Content-Type"),
                            headers));
                }
            }
            complete = true;
            return parts;
        } finally {
            if (!complete) for (PartImpl p : parts) p.discard();
        }
    }

    /** After a delimiter: {@code --} closes the body; else optional padding then CRLF open a part. */
    private static boolean atCloseDelimiter(CountingReader in) throws IOException {
        int c = in.read();
        if (c == '-') {
            if (in.read() == '-') return true;
            throw new MalformedMultipartException("malformed close delimiter");
        }
        while (c == ' ' || c == '\t') c = in.read();
        if (c == '\r') {
            if (in.read() == '\n') return false;
            throw new MalformedMultipartException("malformed delimiter line");
        }
        if (c < 0) throw new MalformedMultipartException("truncated multipart body");
        throw new MalformedMultipartException("malformed delimiter line");
    }

    /** The header section of a part, up to its blank line, at most {@code maxBytes} bytes (CRLFs included). */
    private static List<PartImpl.Header> readHeaders(CountingReader in, int maxBytes) throws IOException {
        var headers = new ArrayList<PartImpl.Header>();
        var line = new ByteArrayOutputStream();
        int total = 0;
        while (true) {
            line.reset();
            total = readHeaderLine(in, line, total, maxBytes);
            if (line.size() == 0) return headers;
            headers.add(parseHeader(line.toByteArray()));
        }
    }

    /**
     * Reads one header line into {@code line}, its CRLF left out, and returns the bytes of the header
     * section read so far, {@code total} before this line, CRLFs included.
     */
    private static int readHeaderLine(CountingReader in, ByteArrayOutputStream line, int total, int maxBytes)
            throws IOException {
        int count = total;
        int c;
        while ((c = in.read()) != '\r') {
            if (c < 0) throw new MalformedMultipartException("truncated multipart part headers");
            if (c == '\n') throw new MalformedMultipartException("bare LF in a part header");
            if (++count > maxBytes) {
                throw new MultipartLimitException("the headers of a part exceed " + maxBytes + " bytes");
            }
            line.write(c);
        }
        if (in.read() != '\n') throw new MalformedMultipartException("bare CR in a part header");
        count += 2;
        if (count > maxBytes) throw new MultipartLimitException("the headers of a part exceed " + maxBytes + " bytes");
        return count;
    }

    private static PartImpl.Header parseHeader(byte[] raw) throws MalformedMultipartException {
        if (raw[0] == ' ' || raw[0] == '\t') throw new MalformedMultipartException("obsolete folded part header");
        String text = decodeHeader(raw);
        int colon = text.indexOf(':');
        if (colon <= 0 || !isToken(text, 0, colon)) throw new MalformedMultipartException("malformed part header");
        for (int i = colon + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch < 0x20 && ch != '\t') || ch == 0x7F) {
                throw new MalformedMultipartException("control character in a part header");
            }
        }
        return new PartImpl.Header(text.substring(0, colon), trimOws(text.substring(colon + 1)));
    }

    /** UTF-8 (what browsers send, RFC 7578 section 5.1), else ISO-8859-1. */
    private static String decodeHeader(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException _) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
    }

    private static String header(List<PartImpl.Header> headers, String name) {
        for (var h : headers) if (h.name().equalsIgnoreCase(name)) return h.value();
        return null;
    }

    /** The {@code form-data} name and file name of a part; {@code null} when it has none (the part is dropped). */
    private record Disposition(String name, String fileName) {

        static Disposition of(List<PartImpl.Header> headers) throws MalformedMultipartException {
            String value = header(headers, "Content-Disposition");
            if (value == null) return null;
            var params = new HashMap<String, String>();
            if (!"form-data".equals(parseParameters(value, params))) return null;
            String name = params.get("name");
            if (name == null) return null;
            String extended = params.containsKey("filename*") ? decodeExtendedValue(params.get("filename*")) : null;
            return new Disposition(name, extended != null ? extended : params.get("filename"));
        }
    }

    /**
     * Parses {@code type; name=value; name="quoted"} into {@code params} (lower-case names) and returns
     * the lower-case type. Inside quotes only {@code \"} and {@code \\} are escapes: browsers send the
     * backslashes of a Windows path raw.
     */
    static String parseParameters(String value, Map<String, String> params) throws MalformedMultipartException {
        int length = value.length();
        int semi = value.indexOf(';');
        String type = (semi < 0 ? value : value.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        int i = semi < 0 ? length : semi + 1;
        while (i < length) {
            i = skipOws(value, i);
            if (i < length) i = value.charAt(i) == ';' ? i + 1 : parseParameter(value, i, params);
        }
        return type;
    }

    /**
     * Parses the {@code name=value} parameter at {@code start} into {@code params}; returns the index
     * after it and its {@code ;}.
     */
    private static int parseParameter(String value, int start, Map<String, String> params)
            throws MalformedMultipartException {
        int length = value.length();
        int eq = value.indexOf('=', start);
        if (eq < 0) throw new MalformedMultipartException("parameter without a value");
        String name = value.substring(start, eq).trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) throw new MalformedMultipartException("parameter without a name");
        int i = skipOws(value, eq + 1);
        String parameter;
        if (i < length && value.charAt(i) == '"') {
            var quoted = new StringBuilder();
            i = skipOws(value, readQuoted(value, i + 1, quoted));
            parameter = quoted.toString();
            if (i < length && value.charAt(i) != ';') {
                throw new MalformedMultipartException("unexpected text after a quoted parameter");
            }
        } else {
            int end = value.indexOf(';', i);
            if (end < 0) end = length;
            parameter = value.substring(i, end).trim();
            i = end;
        }
        if (params.putIfAbsent(name, parameter) != null) {
            throw new MalformedMultipartException("duplicate parameter " + name);
        }
        return i < length ? i + 1 : i;
    }

    /**
     * Reads the quoted string that starts after its opening quote, at {@code start}, into {@code out};
     * returns the index after its closing quote.
     */
    private static int readQuoted(String value, int start, StringBuilder out) throws MalformedMultipartException {
        int length = value.length();
        int i = start;
        while (i < length) {
            char c = value.charAt(i++);
            if (c == '"') return i;
            if (c == '\\' && i < length && (value.charAt(i) == '"' || value.charAt(i) == '\\')) c = value.charAt(i++);
            out.append(c);
        }
        throw new MalformedMultipartException("unterminated quoted string");
    }

    /** RFC 8187 {@code charset'language'pct-encoded} in UTF-8 or ISO-8859-1; {@code null} when it does not decode. */
    private static String decodeExtendedValue(String value) {
        int first = value.indexOf('\'');
        int second = first < 0 ? -1 : value.indexOf('\'', first + 1);
        if (second < 0) return null;
        Charset charset = switch (value.substring(0, first).toUpperCase(Locale.ROOT)) {
            case "UTF-8" -> StandardCharsets.UTF_8;
            case "ISO-8859-1" -> StandardCharsets.ISO_8859_1;
            default -> null;
        };
        if (charset == null) return null;
        var bytes = new ByteArrayOutputStream();
        if (!percentDecode(value.substring(second + 1), bytes)) return null;
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException _) {
            return null;
        }
    }

    /** Writes the bytes of an RFC 8187 {@code pct-encoded} value to {@code out}; {@code false} when malformed. */
    private static boolean percentDecode(String encoded, ByteArrayOutputStream out) {
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) return false;
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return false;
                out.write(hi << 4 | lo);
                i += 3;
            } else if (c < 0x21 || c > 0x7E) {
                return false;
            } else {
                out.write(c);
                i++;
            }
        }
        return true;
    }

    private static boolean isBoundaryChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || "'()+_,-./:=? ".indexOf(c) >= 0;
    }

    private static boolean isToken(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }

    private static int skipOws(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    private static String trimOws(String s) {
        int start = skipOws(s, 0);
        int end = s.length();
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) end--;
        return s.substring(start, end);
    }

    /** The body, read through a window and counted against maxRequestSize. */
    private static final class CountingReader {
        private final InputStream in;
        private final long max;
        private final byte[] buffer = new byte[BUFFER];
        private int position;
        private int length;
        private long total;

        CountingReader(InputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        int read() throws IOException {
            if (position == length) {
                int n;
                do {
                    n = in.read(buffer, 0, buffer.length);
                } while (n == 0);
                if (n < 0) return -1;
                total += n;
                if (max >= 0 && total > max) {
                    throw new MultipartLimitException("the multipart body exceeds maxRequestSize (" + max + " bytes)");
                }
                position = 0;
                length = n;
            }
            return buffer[position++] & 0xFF;
        }
    }

    /** Finds the delimiter in linear time (Knuth-Morris-Pratt); the bytes before it go to a sink. */
    private static final class DelimiterScanner {
        private final byte[] delimiter;
        /** {@code border[i]}: length of the longest proper border of {@code delimiter[0..i]}. */
        private final int[] border;
        private final byte[] pending = new byte[BUFFER];
        /** The bytes of {@link #pending} not handed to the sink yet, during a {@link #scan}. */
        private int pendingLength;

        DelimiterScanner(byte[] delimiter) {
            this.delimiter = delimiter;
            this.border = new int[delimiter.length];
            int k = 0;
            for (int i = 1; i < delimiter.length; i++) {
                while (k > 0 && delimiter[i] != delimiter[k]) k = border[k - 1];
                if (delimiter[i] == delimiter[k]) k++;
                border[i] = k;
            }
        }

        /**
         * Consumes the stream up to and including the next delimiter.
         *
         * @param sink   receives the bytes before the delimiter; {@code null} discards them
         * @param primed the stream starts right after a CRLF (the body's first delimiter needs none)
         * @return {@code false} when the stream ends first
         */
        boolean scan(CountingReader in, PartSink sink, boolean primed) throws IOException {
            int matched = primed ? 2 : 0;
            pendingLength = 0;
            int b;
            while ((b = in.read()) >= 0) {
                matched = fallBack(matched, (byte) b, sink);
                if (delimiter[matched] != (byte) b) {
                    append((byte) b, sink);
                } else if (++matched == delimiter.length) {
                    flush(sink);
                    return true;
                }
            }
            flush(sink);
            return false;
        }

        /** Shortens a partial match of {@code matched} bytes until {@code b} may extend it, or none is left. */
        private int fallBack(int matched, byte b, PartSink sink) throws IOException {
            int m = matched;
            while (m > 0 && delimiter[m] != b) {
                int keep = border[m - 1];
                // delimiter[0 .. m - keep) can no longer begin a delimiter: it was content.
                for (int i = 0; i < m - keep; i++) append(delimiter[i], sink);
                m = keep;
            }
            return m;
        }

        private void append(byte b, PartSink sink) throws IOException {
            if (pendingLength == pending.length) flush(sink);
            pending[pendingLength++] = b;
        }

        private void flush(PartSink sink) throws IOException {
            if (sink != null && pendingLength > 0) sink.write(pending, 0, pendingLength);
            pendingLength = 0;
        }
    }
}
