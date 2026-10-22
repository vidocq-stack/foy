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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the parameter syntax and the RFC 8187 file names {@link MultipartParser} accepts. */
class MultipartParameterSyntaxTest {

    @TempDir Path temp;

    @Test
    void quotedValuesKeepRawBackslashesAndUnescapeOnlyQuotesAndBackslashes() throws IOException {
        Map<String, String> params = new HashMap<>();
        String type = MultipartParser.parseParameters(
                "Form-Data;; NAME=\"a\\\"b\\\\c\\d\" ; filename = C:\\dir\\f.txt ;", params);
        assertEquals("form-data", type);
        assertEquals(Map.of("name", "a\"b\\c\\d", "filename", "C:\\dir\\f.txt"), params);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "form-data; name",
            "form-data; =x",
            "form-data; name=\"x",
            "form-data; name=\"x\" y",
            "form-data; name=a; NAME=b"
    })
    void malformedParametersAreRefused(String value) {
        Map<String, String> params = new HashMap<>();
        assertThrows(MalformedMultipartException.class, () -> MultipartParser.parseParameters(value, params));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "ISO-8859-1''%E9t%E9.txt | \u00e9t\u00e9.txt",
            "utf-8'en'%e2%82%ac       | \u20ac",
            "UTF-8''%G1.txt           | fallback.txt",
            "UTF-8''%4                | fallback.txt",
            "UTF-16''%00%41           | fallback.txt",
            "UTF-8''%FF               | fallback.txt",
            "UTF-8%41                 | fallback.txt",
            "UTF-8''a b               | fallback.txt"
    })
    void anExtendedFilenameWinsOnlyWhenItDecodes(String extended, String expected) throws IOException {
        String body = "--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"fallback.txt\"; filename*="
                + extended + "\r\n\r\nv\r\n--B--\r\n";
        var parts = MultipartParser.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), "B",
                new MultipartParser.Limits(-1, -1, 1024, -1, 8192), new PartStorage(temp));
        assertEquals(expected, parts.getFirst().getSubmittedFileName());
    }
}
