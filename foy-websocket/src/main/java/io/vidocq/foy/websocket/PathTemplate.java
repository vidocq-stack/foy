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
package io.vidocq.foy.websocket;

import jakarta.websocket.DeploymentException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A context-relative endpoint path: literal segments and whole-segment variables ({@code {name}}).
 *
 * <p>A variable matches exactly one non-empty segment; there is no wildcard and the segment counts
 * must be equal. A template has no empty segment, but for a trailing {@code '/'} on a template
 * without variables (as Tomcat's {@code UriTemplate}): that last segment is the literal empty one,
 * so {@code /a/b/} matches only {@code /a/b/}, and {@code /a/b/{x}} never matches {@code /a/b/}.
 * Templates are ordered by {@link #PRECEDENCE}: fewer segments first, then, at the first position
 * where two templates differ, the literal before the variable.
 */
final class PathTemplate {

    /** Ordering of the templates by precedence; the first template that matches wins. */
    static final Comparator<PathTemplate> PRECEDENCE = PathTemplate::comparePrecedence;

    private final String path;
    /** One entry per segment: the literal, or {@code null} when the segment is a variable. */
    private final List<String> literals;
    /** One entry per segment: the variable name, or {@code null} when the segment is a literal. */
    private final List<String> names;

    private PathTemplate(String path, List<String> literals, List<String> names) {
        this.path = path;
        this.literals = literals;
        this.names = names;
    }

    static PathTemplate parse(String path) throws DeploymentException {
        if (path == null || !path.startsWith("/")) {
            throw new DeploymentException("An endpoint path starts with '/': " + path);
        }
        List<String> literals = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (path.length() > 1) {
            String[] segments = path.substring(1).split("/", -1);
            for (int i = 0; i < segments.length; i++) {
                boolean last = i == segments.length - 1;
                parseSegment(segments[i], last, path, literals, names, seen);
            }
        }
        return new PathTemplate(path, Collections.unmodifiableList(literals), Collections.unmodifiableList(names));
    }

    private static void parseSegment(String segment, boolean last, String path, List<String> literals,
            List<String> names, Set<String> seen) throws DeploymentException {
        if (segment.isEmpty()) {
            if (!last || !seen.isEmpty()) {
                throw new DeploymentException("An endpoint path has no empty segment, but for a trailing '/'"
                        + " on a path without variables: " + path);
            }
            // A trailing '/' on a template without variables, as Tomcat's UriTemplate allows:
            // a literal empty last segment, matched only by a path ending in '/'.
            addLiteral(segment, literals, names);
        } else if (segment.startsWith("{") && segment.endsWith("}")) {
            String name = variableName(segment, path);
            if (!seen.add(name)) {
                throw new DeploymentException("Path variable '" + name + "' is repeated in " + path);
            }
            literals.add(null);
            names.add(name);
        } else if (hasBrace(segment)) {
            throw new DeploymentException("A path variable is a whole segment: " + path);
        } else {
            addLiteral(segment, literals, names);
        }
    }

    private static String variableName(String segment, String path) throws DeploymentException {
        String name = segment.substring(1, segment.length() - 1);
        if (name.isEmpty() || hasBrace(name)) {
            throw new DeploymentException("Invalid path variable '" + segment + "' in " + path);
        }
        return name;
    }

    private static boolean hasBrace(String text) {
        return text.indexOf('{') >= 0 || text.indexOf('}') >= 0;
    }

    private static void addLiteral(String literal, List<String> literals, List<String> names) {
        literals.add(literal);
        names.add(null);
    }

    /** The variables by name when the path matches, empty otherwise. */
    Optional<Map<String, String>> match(String requestPath) {
        if (requestPath == null || !requestPath.startsWith("/")) {
            return Optional.empty();
        }
        String[] segments = requestPath.length() == 1 ? new String[0] : requestPath.substring(1).split("/", -1);
        if (segments.length != literals.size()) {
            return Optional.empty();
        }
        Map<String, String> parameters = new HashMap<>();
        for (int i = 0; i < segments.length; i++) {
            if (!matchSegment(i, segments[i], parameters)) {
                return Optional.empty();
            }
        }
        return Optional.of(parameters);
    }

    private boolean matchSegment(int index, String segment, Map<String, String> parameters) {
        String literal = literals.get(index);
        if (literal != null) {
            return literal.equals(segment);
        }
        if (segment.isEmpty()) {
            return false;
        }
        parameters.put(names.get(index), segment);
        return true;
    }

    /** The shape of the template, variable names erased: two templates with the same shape are equivalent. */
    String shape() {
        StringBuilder shape = new StringBuilder();
        for (String literal : literals) {
            shape.append('/').append(literal == null ? "{}" : literal);
        }
        return shape.isEmpty() ? "/" : shape.toString();
    }

    String path() {
        return path;
    }

    private static int comparePrecedence(PathTemplate a, PathTemplate b) {
        int counts = Integer.compare(a.literals.size(), b.literals.size());
        if (counts != 0) {
            return counts;
        }
        for (int i = 0; i < a.literals.size(); i++) {
            boolean aVariable = a.literals.get(i) == null;
            if (aVariable != (b.literals.get(i) == null)) {
                return aVariable ? 1 : -1;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return path;
    }
}
