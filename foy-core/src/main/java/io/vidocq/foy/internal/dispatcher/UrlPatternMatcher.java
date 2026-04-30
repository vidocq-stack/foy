package io.vidocq.foy.internal.dispatcher;

import java.util.Objects;

/**
 * Matching url-pattern selon Servlet 6.1 section 12.2.
 * <ol>
 *   <li>Exact : {@code /path}</li>
 *   <li>Préfixe (le plus long gagne) : {@code /path/*}</li>
 *   <li>Extension : {@code *.ext}</li>
 *   <li>Défaut : {@code /}</li>
 *   <li>Empty string ({@code ""}) — correspond à la racine du contexte</li>
 * </ol>
 *
 * <p>L'ordre de précédence est strict : exact > préfixe (longueur décroissante)
 * > extension > défaut.</p>
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
     * Retourne {@code true} si ce pattern matche le path donné (doit commencer par {@code /}).
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
     * Précédence pour choisir un match parmi plusieurs. Plus petit = meilleur.
     * Exact = 0, prefix long = 1 (+ longueur négative), extension = 2, default = 3, empty = 4.
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
