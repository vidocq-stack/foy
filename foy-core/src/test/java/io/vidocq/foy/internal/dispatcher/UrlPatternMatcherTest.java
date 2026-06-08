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
package io.vidocq.foy.internal.dispatcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlPatternMatcherTest {

    @Test
    void exactPatternMatchesOnlyItself() {
        var m = UrlPatternMatcher.of("/foo");
        assertEquals(UrlPatternMatcher.Kind.EXACT, m.kind());
        assertTrue(m.matches("/foo"));
        assertFalse(m.matches("/foo/"));
        assertFalse(m.matches("/foobar"));
    }

    @Test
    void prefixPatternMatchesSelfAndDescendants() {
        var m = UrlPatternMatcher.of("/api/*");
        assertEquals(UrlPatternMatcher.Kind.PREFIX, m.kind());
        assertTrue(m.matches("/api"));
        assertTrue(m.matches("/api/users"));
        assertTrue(m.matches("/api/users/42"));
        assertFalse(m.matches("/apix"));
        assertFalse(m.matches("/other"));
    }

    @Test
    void extensionPatternMatchesSuffix() {
        var m = UrlPatternMatcher.of("*.jsp");
        assertEquals(UrlPatternMatcher.Kind.EXTENSION, m.kind());
        assertTrue(m.matches("/index.jsp"));
        assertTrue(m.matches("/a/b/c.jsp"));
        assertFalse(m.matches("/index.html"));
        assertFalse(m.matches("/.jsp"));
    }

    @Test
    void defaultPatternMatchesAny() {
        var m = UrlPatternMatcher.of("/");
        assertEquals(UrlPatternMatcher.Kind.DEFAULT, m.kind());
        assertTrue(m.matches("/"));
        assertTrue(m.matches("/anything"));
        assertTrue(m.matches("/nested/path.txt"));
    }

    @Test
    void emptyPatternMatchesContextRoot() {
        var m = UrlPatternMatcher.of("");
        assertEquals(UrlPatternMatcher.Kind.EMPTY, m.kind());
        assertTrue(m.matches(""));
        assertTrue(m.matches("/"));
        assertFalse(m.matches("/anything"));
    }

    @Test
    void invalidPatternRejected() {
        assertThrows(IllegalArgumentException.class, () -> UrlPatternMatcher.of("foo"));
    }

    @Test
    void exactBeatsPrefixBeatsExtensionBeatsDefault() {
        int exact = UrlPatternMatcher.of("/foo").precedence();
        int prefix = UrlPatternMatcher.of("/foo/*").precedence();
        int ext = UrlPatternMatcher.of("*.jsp").precedence();
        int def = UrlPatternMatcher.of("/").precedence();
        assertTrue(exact < prefix);
        assertTrue(prefix < ext);
        assertTrue(ext < def);
    }

    @Test
    void longerPrefixBeatsShorter() {
        assertTrue(UrlPatternMatcher.of("/a/b/*").precedence()
                < UrlPatternMatcher.of("/a/*").precedence());
    }
}
