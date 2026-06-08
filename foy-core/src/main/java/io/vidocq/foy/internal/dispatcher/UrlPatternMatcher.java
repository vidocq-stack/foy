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

import java.util.Objects;

/**
 * Matching url-pattern according to Servlet 6.1 section 12.2.
 * <ol>
 *   <li>Correct: {@code /path}</li>
 *   <li>Prefix (longest wins): {@code /path/*}</li>
 *   <li>Extension: {@code *.ext}</li>
 *   <li>Default: {@code /}</li>
 *   <li>Empty string ({@code ""}) — corresponds to the root of the context</li>
 * </ol>
 *
 * <p>The order of precedence is strict: exact > prefix (descending length)
 * > extension > default.</p>
 */
public final class UrlPatternMatcher {

    private final String pattern;
    private final Kind kind;

    public enum Kind { EXACT, PREFIX, EXTENSION, DEFAULT, EMPTY }

    private UrlPatternMatcher(String pattern, Kind kind) {
        this.pattern = pattern;
        this.kind = kind;
    }

    public static UrlPatternMatcher of(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.isEmpty()) {
            return new UrlPatternMatcher("", Kind.EMPTY);
        }
        if ("/".equals(pattern)) {
            return new UrlPatternMatcher("/", Kind.DEFAULT);
        }
        if (pattern.endsWith("/*")) {
            return new UrlPatternMatcher(pattern, Kind.PREFIX);
        }
        if (pattern.startsWith("*.")) {
            return new UrlPatternMatcher(pattern, Kind.EXTENSION);
        }
        if (pattern.startsWith("/")) {
            return new UrlPatternMatcher(pattern, Kind.EXACT);
        }
        throw new IllegalArgumentException("Invalid servlet url-pattern: " + pattern);
    }

    public String pattern() {
        return pattern;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Returns {@code true} if this pattern matches the given path (must start with {@code /}).
     */
    public boolean matches(String path) {
        Objects.requireNonNull(path, "path");
        return switch (kind) {
            case EXACT -> pattern.equals(path);
            case PREFIX -> {
                String prefix = pattern.substring(0, pattern.length() - 2); // drop /*
                yield path.equals(prefix) || path.startsWith(prefix + "/");
            }
            case EXTENSION -> {
                String ext = pattern.substring(1); // drop *
                int lastSlash = path.lastIndexOf('/');
                String lastSegment = lastSlash < 0 ? path : path.substring(lastSlash + 1);
                yield lastSegment.endsWith(ext) && !lastSegment.equals(ext);
            }
            case DEFAULT -> true;
            case EMPTY -> path.isEmpty() || "/".equals(path);
        };
    }

    /**
     * Precedence to choose one match from several. Smaller = better.
     * Exact = 0, prefix long = 1 (+ negative length), extension = 2, default = 3, empty = 4.
     */
    public int precedence() {
        return switch (kind) {
            case EXACT -> 0;
            case PREFIX -> 1_000 - pattern.length(); // plus long = meilleur
            case EXTENSION -> 10_000;
            case DEFAULT -> 100_000;
            case EMPTY -> 1_000_000;
        };
    }
}
