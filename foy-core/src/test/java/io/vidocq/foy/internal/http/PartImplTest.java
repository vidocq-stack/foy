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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.vidocq.foy.internal.http.PartStorageTest.count;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PartImplTest {

    @TempDir Path temp;

    private static final List<PartImpl.Header> HEADERS = List.of(
            new PartImpl.Header("Content-Disposition", "form-data; name=\"f\"; filename=\"a.txt\""),
            new PartImpl.Header("X-Tag", "1"),
            new PartImpl.Header("x-tag", "2"),
            new PartImpl.Header("Content-Type", "text/plain; charset=UTF-8"));

    private static PartImpl part(PartStorage storage, int threshold, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (var sink = new PartSink(storage, threshold, -1)) {
            sink.write(bytes, 0, bytes.length);
            return sink.finish("f", "a.txt", "text/plain; charset=UTF-8", HEADERS);
        }
    }

    private static String read(PartImpl p) throws IOException {
        try (var in = p.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    void contentWithinTheThresholdStaysInMemory() throws IOException {
        var p = part(new PartStorage(temp), 16, "hello");
        assertEquals(0, count(temp));
        assertEquals(5, p.getSize());
        assertEquals("hello", read(p));
    }

    @Test
    void contentAboveTheThresholdSpillsToOneFile() throws IOException {
        var p = part(new PartStorage(temp), 4, "0123456789");
        assertEquals(1, count(temp));
        assertEquals(10, p.getSize());
        assertEquals("0123456789", read(p));
    }

    @Test
    void aZeroThresholdSpillsAnyContentButNotAnEmptyPart() throws IOException {
        var storage = new PartStorage(temp);
        part(storage, 0, "x");
        assertEquals(1, count(temp));
        var empty = part(storage, 0, "");
        assertEquals(1, count(temp));
        assertEquals(0, empty.getSize());
    }

    @Test
    void maxFileSizeIsEnforcedAndTheSpilledFileDeleted() throws IOException {
        var sink = new PartSink(new PartStorage(temp), 0, 4);
        sink.write("abc".getBytes(StandardCharsets.US_ASCII), 0, 3);
        assertThrows(MultipartLimitException.class, () -> sink.write("de".getBytes(StandardCharsets.US_ASCII), 0, 2));
        sink.close();
        assertEquals(0, count(temp));
    }

    @Test
    void writeMovesTheSpilledFileUnderTheLocationAndHandsItToTheApplication() throws IOException {
        var storage = new PartStorage(temp);
        var p = part(storage, 0, "hello");
        p.write("kept.txt");
        assertEquals("hello", Files.readString(temp.resolve("kept.txt")));
        assertEquals(1, count(temp), "moved, not copied");
        assertEquals("hello", read(p), "still readable after write");
        p.delete();
        storage.close();
        assertTrue(Files.exists(temp.resolve("kept.txt")), "a written file belongs to the application");
    }

    @Test
    void writeOfAnInMemoryPartCreatesTheFile() throws IOException {
        var p = part(new PartStorage(temp), 64, "small");
        p.write("small.txt");
        assertEquals("small", Files.readString(temp.resolve("small.txt")));
    }

    @Test
    void writeRefusesNamesEscapingTheLocation() throws IOException {
        Path location = Files.createDirectory(temp.resolve("location"));
        var p = part(new PartStorage(location), 0, "evil");
        assertThrows(IOException.class, () -> p.write("../evil.txt"));
        assertFalse(Files.exists(temp.resolve("evil.txt")));
    }

    @Test
    void writeOfAnInMemoryPartDoesNotFollowALinkedTarget(@TempDir Path outside) throws IOException {
        Path victim = Files.writeString(outside.resolve("victim.txt"), "intact");
        try {
            Files.createSymbolicLink(temp.resolve("link.txt"), victim);
        } catch (UnsupportedOperationException | IOException noLinks) {
            assumeTrue(false, "symbolic links not supported: " + noLinks);
        }
        var p = part(new PartStorage(temp), 64, "evil");
        assertThrows(IOException.class, () -> p.write("link.txt"));
        assertEquals("intact", Files.readString(victim));
    }

    @Test
    void writeUsesAnAbsolutePathAsProvided(@TempDir Path other) throws IOException {
        var p = part(new PartStorage(temp), 0, "abs");
        p.write(other.resolve("abs.txt").toString());
        assertEquals("abs", Files.readString(other.resolve("abs.txt")));
    }

    @Test
    void deleteRemovesTheTemporaryFile() throws IOException {
        var p = part(new PartStorage(temp), 0, "gone");
        p.delete();
        assertEquals(0, count(temp));
        assertThrows(IOException.class, p::getInputStream);
    }

    @Test
    void headersAreCaseInsensitiveMultiValuedAndKeepTheirFirstSpelling() throws IOException {
        var p = part(new PartStorage(temp), 64, "h");
        assertEquals("1", p.getHeader("x-TAG"));
        assertEquals(List.of("1", "2"), List.copyOf(p.getHeaders("X-Tag")));
        assertEquals(List.of("Content-Disposition", "X-Tag", "Content-Type"), List.copyOf(p.getHeaderNames()));
        assertNull(p.getHeader("Missing"));
        assertTrue(p.getHeaders("Missing").isEmpty());
    }

    @Test
    void theCharsetComesFromThePartContentType() throws IOException {
        var p = part(new PartStorage(temp), 64, "h");
        assertEquals(StandardCharsets.UTF_8, p.charset(StandardCharsets.ISO_8859_1));
        var plain = PartImpl.inMemory("n", null, null, List.of(), new byte[0], new PartStorage(temp));
        assertEquals(StandardCharsets.ISO_8859_1, plain.charset(StandardCharsets.ISO_8859_1));
    }

    @Test
    void theCharsetParameterIsParsedLikeAnyParameter() {
        var storage = new PartStorage(temp);
        var quoted = PartImpl.inMemory("n", null, "text/plain; x=\"a; charset=latin1\"; charset=\"UTF-16\"",
                List.of(), new byte[0], storage);
        assertEquals(StandardCharsets.UTF_16, quoted.charset(StandardCharsets.ISO_8859_1),
                "a charset= inside another quoted parameter is not the charset");
        var malformed = PartImpl.inMemory("n", null, "text/plain; charset=\"UTF-8", List.of(), new byte[0], storage);
        assertEquals(StandardCharsets.ISO_8859_1, malformed.charset(StandardCharsets.ISO_8859_1));
    }
}
