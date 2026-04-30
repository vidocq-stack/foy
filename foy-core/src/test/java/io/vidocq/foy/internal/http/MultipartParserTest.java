package io.vidocq.foy.internal.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultipartParserTest {

    @Test
    void extractBoundaryFromContentType() {
        assertEquals("abc", MultipartParser.extractBoundary("multipart/form-data; boundary=abc"));
        assertEquals("abc", MultipartParser.extractBoundary("multipart/form-data; BOUNDARY=abc;foo"));
        assertEquals("quoted", MultipartParser.extractBoundary("multipart/form-data; boundary=\"quoted\""));
        assertNull(MultipartParser.extractBoundary("text/plain"));
        assertNull(MultipartParser.extractBoundary(null));
    }

    @Test
    void parsesSimpleFormField() {
        String body = "--XYZ\r\n"
                + "Content-Disposition: form-data; name=\"field\"\r\n\r\n"
                + "value\r\n"
                + "--XYZ--\r\n";
        var parts = MultipartParser.parse(body.getBytes(StandardCharsets.UTF_8), "XYZ");
        assertEquals(1, parts.size());
        assertEquals("field", parts.get(0).getName());
        assertEquals("value", new String(parts.get(0).bytes(), StandardCharsets.UTF_8));
        assertNull(parts.get(0).getSubmittedFileName());
    }

    @Test
    void parsesFileUploadWithContentType() {
        String body = "--BND\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "hello world\r\n"
                + "--BND--\r\n";
        List<PartImpl> parts = MultipartParser.parse(body.getBytes(StandardCharsets.UTF_8), "BND");
        assertEquals(1, parts.size());
        var p = parts.get(0);
        assertEquals("file", p.getName());
        assertEquals("hello.txt", p.getSubmittedFileName());
        assertEquals("text/plain", p.getContentType());
        assertEquals("hello world", new String(p.bytes(), StandardCharsets.UTF_8));
        assertEquals(11L, p.getSize());
    }

    @Test
    void parsesMultiplePartsInOrder() {
        String body = "--B\r\n"
                + "Content-Disposition: form-data; name=\"a\"\r\n\r\n"
                + "1\r\n"
                + "--B\r\n"
                + "Content-Disposition: form-data; name=\"b\"\r\n\r\n"
                + "2\r\n"
                + "--B--\r\n";
        var parts = MultipartParser.parse(body.getBytes(StandardCharsets.UTF_8), "B");
        assertEquals(List.of("a", "b"),
                parts.stream().map(PartImpl::getName).toList());
    }

    @Test
    void returnsEmptyWhenNoBoundaryFound() {
        var parts = MultipartParser.parse("not multipart".getBytes(), "MISSING");
        assertTrue(parts.isEmpty());
    }

    @Test
    void headersExposedAsCaseInsensitive() {
        String body = "--B\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "X-Custom: yes\r\n\r\n"
                + "v\r\n"
                + "--B--\r\n";
        var parts = MultipartParser.parse(body.getBytes(StandardCharsets.UTF_8), "B");
        assertEquals("yes", parts.get(0).getHeader("x-custom"));
        assertTrue(parts.get(0).getHeaderNames().contains("Content-Disposition"));
    }
}
