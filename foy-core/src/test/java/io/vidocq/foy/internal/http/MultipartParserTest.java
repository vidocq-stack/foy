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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static io.vidocq.foy.internal.http.PartStorageTest.count;
import static org.junit.jupiter.api.Assertions.*;

class MultipartParserTest {

    @TempDir Path temp;

    private static final MultipartParser.Limits OPEN = new MultipartParser.Limits(-1, -1, 1024, -1, 8192);

    private List<PartImpl> parse(String body, String boundary, MultipartParser.Limits limits) throws IOException {
        return parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), boundary, limits);
    }

    private List<PartImpl> parse(InputStream body, String boundary, MultipartParser.Limits limits) throws IOException {
        return MultipartParser.parse(body, boundary, limits, new PartStorage(temp));
    }

    private static String content(PartImpl p) throws IOException {
        try (var in = p.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String field(String b, String name, String value) {
        return "--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    /** One byte per read: every delimiter and header crosses read boundaries. */
    private static InputStream trickle(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            private int i;
            @Override public int read() { return i < bytes.length ? bytes[i++] & 0xFF : -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                int c = read();
                if (c < 0) return -1;
                b[off] = (byte) c;
                return 1;
            }
        };
    }

    /** {@code head}, then the byte 'a' forever. */
    private static InputStream endless(String head) {
        return new SequenceInputStream(new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)),
                new InputStream() { @Override public int read() { return 'a'; } });
    }

    @Test
    void extractBoundaryAcceptsOnlyAValidMultipartFormDataBoundary() {
        assertEquals("abc", MultipartParser.extractBoundary("multipart/form-data; boundary=abc"));
        assertEquals("abc", MultipartParser.extractBoundary("Multipart/Form-Data; BOUNDARY=abc; charset=UTF-8"));
        assertEquals("a b'()+_,-./:=?", MultipartParser.extractBoundary("multipart/form-data; boundary=\"a b'()+_,-./:=?\""));
        assertNull(MultipartParser.extractBoundary("multipart/form-data"));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary="));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary=" + "x".repeat(71)));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary=\"ends with space \""));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary=a{b"));
        assertNull(MultipartParser.extractBoundary("multipart/form-datax; boundary=abc"));
        assertNull(MultipartParser.extractBoundary("text/plain; boundary=abc"));
        assertNull(MultipartParser.extractBoundary(null));
    }

    @Test
    void parsesFieldsAndFilesInOrder() throws IOException {
        String body = field("B", "a", "1")
                + "--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"hello.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nhello world\r\n"
                + "--B--\r\n";
        var parts = parse(body, "B", OPEN);
        assertEquals(List.of("a", "f"), parts.stream().map(PartImpl::getName).toList());
        assertNull(parts.get(0).getSubmittedFileName());
        assertEquals("1", content(parts.get(0)));
        assertEquals("hello.txt", parts.get(1).getSubmittedFileName());
        assertEquals("text/plain", parts.get(1).getContentType());
        assertEquals(11, parts.get(1).getSize());
        assertEquals("hello world", content(parts.get(1)));
    }

    @Test
    void aPreambleAndAnEpilogueAreIgnoredAndPaddingIsAccepted() throws IOException {
        var parts = parse("preamble\r\n--B \t\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--B--\r\nepilogue", "B", OPEN);
        assertEquals(1, parts.size());
        assertEquals("1", content(parts.get(0)));
    }

    @Test
    void anEmptyMultipartBodyHasNoPart() throws IOException {
        assertTrue(parse("--B--\r\n", "B", OPEN).isEmpty());
    }

    @Test
    void boundaryTextInsideContentIsNotADelimiter() throws IOException {
        var parts = parse(field("B", "a", "x--B y --B--") + "--B--\r\n", "B", OPEN);
        assertEquals("x--B y --B--", content(parts.get(0)));
    }

    @Test
    void aPartialDelimiterBeforeTheRealOneStaysContent() throws IOException {
        String value = "keep\r\n--Xy\r\n-\r\n--X";
        var parts = parse(field("XyzW", "a", value) + "--XyzW--\r\n", "XyzW", OPEN);
        assertEquals(value, content(parts.get(0)));
    }

    @Test
    void aDelimiterSplitAcrossReadsIsFound() throws IOException {
        String body = field("Boundary", "a", "first") + field("Boundary", "b", "second") + "--Boundary--\r\n";
        var parts = parse(trickle(body), "Boundary", OPEN);
        assertEquals(List.of("first", "second"), List.of(content(parts.get(0)), content(parts.get(1))));
    }

    @Test
    void binaryContentIsByteExact() throws IOException {
        byte[] head = "--B\r\nContent-Disposition: form-data; name=\"bin\"; filename=\"b\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        byte[] tail = "\r\n--B--\r\n".getBytes(StandardCharsets.US_ASCII);
        var body = new SequenceInputStream(new SequenceInputStream(new ByteArrayInputStream(head),
                new ByteArrayInputStream(data)), new ByteArrayInputStream(tail));
        var part = parse(body, "B", OPEN).getFirst();
        try (var in = part.getInputStream()) { assertArrayEquals(data, in.readAllBytes()); }
    }

    @Test
    void nameIsNotMatchedInsideFilename() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; filename=\"a.txt\"; name=\"b\"\r\n\r\nx\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("b", part.getName());
        assertEquals("a.txt", part.getSubmittedFileName());
    }

    @Test
    void quotedValuesKeepSemicolonsAndEscapedQuotes() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"a;b\"; filename=\"x\\\"y.txt\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("a;b", part.getName());
        assertEquals("x\"y.txt", part.getSubmittedFileName());
    }

    @Test
    void submittedFileNameIsReturnedRaw() throws IOException {
        var parts = parse("--B\r\nContent-Disposition: form-data; name=\"a\"; filename=\"../../etc/passwd\"\r\n\r\nv\r\n"
                + "--B\r\nContent-Disposition: form-data; name=\"b\"; filename=\"C:\\dir\\f.txt\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN);
        assertEquals("../../etc/passwd", parts.get(0).getSubmittedFileName());
        assertEquals("C:\\dir\\f.txt", parts.get(1).getSubmittedFileName(), "backslashes of a Windows path are kept");
    }

    @Test
    void utf8HeadersAreDecoded() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"\u00e9\"; filename=\"r\u00e9sum\u00e9.pdf\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("\u00e9", part.getName());
        assertEquals("r\u00e9sum\u00e9.pdf", part.getSubmittedFileName());
    }

    @Test
    void anExtendedFilenameWins() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"fallback.txt\"; filename*=UTF-8''%E2%82%AC.txt\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("\u20ac.txt", part.getSubmittedFileName());
    }

    @Test
    void repeatedHeadersAreKept() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"a\"\r\nX-A: 1\r\nx-a: 2\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals(List.of("1", "2"), List.copyOf(part.getHeaders("X-A")));
        assertEquals("1", part.getHeader("x-a"));
    }

    @Test
    void aPartWithoutAFormDataNameIsDropped() throws IOException {
        var parts = parse("--B\r\nContent-Type: text/plain\r\n\r\nno disposition\r\n"
                + "--B\r\nContent-Disposition: attachment; name=\"x\"\r\n\r\nnot form-data\r\n"
                + field("B", "kept", "1") + "--B--\r\n", "B", OPEN);
        assertEquals(List.of("kept"), parts.stream().map(PartImpl::getName).toList());
    }

    @Test
    void aTruncatedBodyIsMalformed() {
        assertThrows(MalformedMultipartException.class, () -> parse(field("B", "a", "1"), "B", OPEN));
        assertThrows(MalformedMultipartException.class, () -> parse("--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nhalf", "B", OPEN));
        assertThrows(MalformedMultipartException.class, () -> parse("--B\r\nContent-Disposition: form-da", "B", OPEN));
    }

    @Test
    void aBodyWithoutTheBoundaryIsMalformed() {
        assertThrows(MalformedMultipartException.class, () -> parse("not multipart", "MISSING", OPEN));
    }

    @Test
    void malformedPartHeadersAreRefused() {
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nContent-Disposition: form-data; name=\"a\"\nX: y\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "bare LF");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nContent-Disposition: form-data;\r\n name=\"a\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "folded");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nContent-Disposition: form-data; name=\"a\u0000\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "control character");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nBad Header: x\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "not a token");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--Bxx\r\n", "B", OPEN), "garbage after the delimiter");
    }

    @Test
    void tooManyPartsAreRefused() {
        var limits = new MultipartParser.Limits(-1, -1, 1024, 2, 8192);
        String body = field("B", "a", "1") + field("B", "b", "2") + field("B", "c", "3") + "--B--\r\n";
        assertThrows(MultipartLimitException.class, () -> parse(body, "B", limits));
    }

    @Test
    void oversizedPartHeadersAreRefused() {
        var limits = new MultipartParser.Limits(-1, -1, 1024, -1, 64);
        String body = "--B\r\nContent-Disposition: form-data; name=\"a\"\r\nX-Long: " + "y".repeat(100) + "\r\n\r\nv\r\n--B--\r\n";
        assertThrows(MultipartLimitException.class, () -> parse(body, "B", limits));
        assertThrows(MultipartLimitException.class,
                () -> parse(endless("--B\r\nX-Endless: "), "B", limits), "an endless header line stops at the limit");
    }

    @Test
    void maxFileSizeIsEnforcedWhileStreaming() {
        var limits = new MultipartParser.Limits(1024, -1, 0, -1, 8192);
        assertThrows(MultipartLimitException.class,
                () -> parse(endless("--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\n"), "B", limits));
    }

    @Test
    void maxRequestSizeIsEnforcedWhileStreaming() {
        var limits = new MultipartParser.Limits(-1, 64 * 1024, 0, -1, 8192);
        assertThrows(MultipartLimitException.class,
                () -> parse(endless("--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\n"), "B", limits));
    }

    @Test
    void contentAboveTheThresholdSpillsAndBelowStaysInMemory() throws IOException {
        var limits = new MultipartParser.Limits(-1, -1, 4, -1, 8192);
        parse(field("B", "small", "abc") + field("B", "big", "0123456789") + "--B--\r\n", "B", limits);
        assertEquals(1, count(temp));
    }

    @Test
    void aFailedParseDeletesItsTemporaryFiles() throws IOException {
        var limits = new MultipartParser.Limits(-1, -1, 0, -1, 8192);
        String body = field("B", "complete", "spilled") + "--B\r\nContent-Disposition: form-data; name=\"cut\"\r\n\r\nalso spilled";
        assertThrows(MalformedMultipartException.class, () -> parse(body, "B", limits));
        assertEquals(0, count(temp));
    }
}
