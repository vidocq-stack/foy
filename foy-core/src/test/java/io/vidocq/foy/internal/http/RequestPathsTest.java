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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Request path canonicalisation (Servlet 6.1 section 3.5.2). */
class RequestPathsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "/                         | /",
            "/a/b                      | /a/b",
            "/a/b/                     | /a/b/",
            "/my%20file.txt            | /my file.txt",
            "/caf%C3%A9.txt            | /café.txt",
            "/caf%c3%a9.txt            | /café.txt",
            "/a%20b                    | /a b",
            "/100%25.txt               | /100%.txt",
            "/100%252e.txt             | /100%2e.txt",
            "/a%3Bb                    | /a;b",
            "/a/./b                    | /a/b",
            "/a/x/../b                 | /a/b",
            "/a/b/..                   | /a/",
            "/a/.                      | /a/",
            "/.                        | /",
            "/a/..                     | /",
            "/a/%2e%2e/b               | /b",
            "/a/%2E/b                  | /a/b",
            "//a//b                    | /a/b",
            "/a///                     | /a/",
            "/a;x=1/b                  | /a/b",
            "/WEB-INF;x=1/web.xml      | /WEB-INF/web.xml",
            "/a;jsessionid=ABC         | /a",
            "/a;p=1;q=2/b;r            | /a/b",
            "/;x/a                     | /a",
            "/..;x/a                   | ''",
            "/a/..;x/b                 | /b",
            "/a/b%2Bc                  | /a/b+c",
            "/a+b                      | /a+b",
    })
    void canonicalisesValidPaths(String raw, String expected) {
        if (expected.isEmpty()) {
            assertNull(RequestPaths.canonicalize(raw), raw);
        } else {
            assertEquals(expected, RequestPaths.canonicalize(raw), raw);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/..%2fWEB-INF/web.xml", "/WEB-INF%2fweb.xml", "/WEB-INF%2Fweb.xml", "/a%00b", "/a%5Cb", "/a%5cb",
            "/a\\b", "%zz", "/a%zz", "/a%2", "/a%", "/a%g0", "/../x", "/a/../../x", "/%2e%2e/x", "/./../x",
            "/a\u0000b", "/a\u0001b", "/a\tb", "/a\u007fb", "/a%0Ab", "/a%0db", "/a%7F", "relative", "",
            "/a%C3", "/a%C3%28", "/a%FF", "/a%C0%AF", "/a%ED%A0%80", "/café.txt",
    })
    void rejectsInvalidPaths(String raw) {
        assertNull(RequestPaths.canonicalize(raw), raw);
    }

    @Test
    void rejectsNull() {
        assertNull(RequestPaths.canonicalize(null));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "/x.html                   | /x.html",
            "/views/../x.html          | /x.html",
            "/views/./x.html           | /views/x.html",
            "//views//x.html           | /views/x.html",
            "/a b/%20                  | /a b/%20",
            "/views/..                 | /",
            "/WEB-INF/../WEB-INF/a     | /WEB-INF/a",
    })
    void normalisesDecodedDispatchPathsWithoutDecodingThem(String path, String expected) {
        assertEquals(expected, RequestPaths.normalize(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/../x", "/a/../../x", "/..", "relative", ""})
    void dispatchPathEscapingTheContextRootIsRejected(String path) {
        assertNull(RequestPaths.normalize(path), path);
    }
}
