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
