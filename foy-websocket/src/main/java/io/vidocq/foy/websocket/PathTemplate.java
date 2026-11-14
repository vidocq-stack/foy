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
import java.util.Set;

/**
 * A context-relative endpoint path: literal segments and whole-segment variables ({@code {name}}).
 *
 * <p>A variable matches exactly one non-empty segment; there is no wildcard and the segment counts
 * must be equal. Templates are ordered by {@link #PRECEDENCE}: fewer segments first, then, at the
 * first position where two templates differ, the literal before the variable.
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
            for (String segment : path.substring(1).split("/", -1)) {
                if (segment.isEmpty()) {
                    throw new DeploymentException("An endpoint path has no empty segment: " + path);
                }
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    String name = segment.substring(1, segment.length() - 1);
                    if (name.isEmpty() || name.indexOf('{') >= 0 || name.indexOf('}') >= 0) {
                        throw new DeploymentException("Invalid path variable '" + segment + "' in " + path);
                    }
                    if (!seen.add(name)) {
                        throw new DeploymentException("Path variable '" + name + "' is repeated in " + path);
                    }
                    literals.add(null);
                    names.add(name);
                } else if (segment.indexOf('{') >= 0 || segment.indexOf('}') >= 0) {
                    throw new DeploymentException("A path variable is a whole segment: " + path);
                } else {
                    literals.add(segment);
                    names.add(null);
                }
            }
        }
        return new PathTemplate(path, Collections.unmodifiableList(literals), Collections.unmodifiableList(names));
    }

    /** The variables by name when the path matches, {@code null} otherwise. */
    Map<String, String> match(String requestPath) {
        if (requestPath == null || !requestPath.startsWith("/")) {
            return null;
        }
        String[] segments = requestPath.length() == 1 ? new String[0] : requestPath.substring(1).split("/", -1);
        if (segments.length != literals.size()) {
            return null;
        }
        Map<String, String> parameters = new HashMap<>();
        for (int i = 0; i < segments.length; i++) {
            String literal = literals.get(i);
            if (literal != null) {
                if (!literal.equals(segments[i])) {
                    return null;
                }
            } else if (segments[i].isEmpty()) {
                return null;
            } else {
                parameters.put(names.get(i), segments[i]);
            }
        }
        return parameters;
    }

    /** The shape of the template, variable names erased: two templates with the same shape are equivalent. */
    String shape() {
        StringBuilder shape = new StringBuilder();
        for (String literal : literals) {
            shape.append('/').append(literal == null ? "{}" : literal);
        }
        return shape.length() == 0 ? "/" : shape.toString();
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
