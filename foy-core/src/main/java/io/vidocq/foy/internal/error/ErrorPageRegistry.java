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
package io.vidocq.foy.internal.error;

import jakarta.servlet.ServletException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Register of error pages of an application (Servlet 6.1 §9.9 spec).
 *
 * <p>Two types of mapping:</p>
 * <ul>
 *   <li>by HTTP code — {@link #register(int, String)}</li>
 *   <li>by exception type — {@link #register(Class, String)} (most specific type wins)</li>
 * </ul>
 */
public final class ErrorPageRegistry {

    private final Map<Integer, String> byStatus = new LinkedHashMap<>();
    private final LinkedHashMap<Class<? extends Throwable>, String> byException = new LinkedHashMap<>();

    public ErrorPageRegistry register(int statusCode, String location) {
        Objects.requireNonNull(location, "location");
        if (statusCode < 400 || statusCode > 599) {
            throw new IllegalArgumentException("status-code error page must be 4xx/5xx: " + statusCode);
        }
        byStatus.put(statusCode, location);
        return this;
    }

    public ErrorPageRegistry register(Class<? extends Throwable> type, String location) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
        byException.put(type, location);
        return this;
    }

    public Optional<String> findByStatus(int statusCode) {
        return Optional.ofNullable(byStatus.get(statusCode));
    }

    /** An exception error page together with the exception it matched. */
    public record Match(String location, Throwable matched) {}

    /**
     * Servlet 6.1 section 10.9.2 lookup: the class hierarchy of {@code throwable} first; when nothing
     * matches and it is a {@link ServletException}, the same lookup is repeated on its
     * {@link ServletException#getRootCause() root cause}, recursively through nested
     * {@code ServletException}s only (arbitrary {@code getCause()} chains are not walked).
     *
     * @return the page and the exception that matched it (the unwrapped one when unwrapping found it)
     */
    public Optional<Match> match(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 64; depth++) {
            String page = byHierarchy(current);
            if (page != null) return Optional.of(new Match(page, current));
            if (!(current instanceof ServletException se)) break;
            current = se.getRootCause();
        }
        return Optional.empty();
    }

    /** Convenience over {@link #match(Throwable)} returning only the page location. */
    public Optional<String> findByException(Throwable throwable) {
        return match(throwable).map(Match::location);
    }

    private String byHierarchy(Throwable t) {
        Class<?> c = t.getClass();
        while (c != null && Throwable.class.isAssignableFrom(c)) {
            String page = byException.get(c);
            if (page != null) return page;
            c = c.getSuperclass();
        }
        return null;
    }

    public int size() {
        return byStatus.size() + byException.size();
    }
}
