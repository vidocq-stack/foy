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
import java.util.Optional;

/**
 * Resolves a relative path (without contextPath) to a {@link DispatchTarget} from
 * of {@link ServletDispatcher}.
 */
public final class DispatchResolver {

    private final ServletDispatcher dispatcher;

    public DispatchResolver(ServletDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    /** {@code path}: path relative to the contextPath, can contain a query. */
    public Optional<DispatchTarget> resolve(String path) {
        String justPath = path;
        String queryString = null;
        int q = path.indexOf('?');
        if (q >= 0) {
            justPath = path.substring(0, q);
            queryString = path.substring(q + 1);
        }
        Optional<ServletDispatcher.Mapping> match = dispatcher.find(justPath);
        if (match.isEmpty()) return Optional.empty();
        ServletDispatcher.Mapping m = match.get();
        String servletPath = servletPathFor(m, justPath);
        String pathInfo = pathInfoFor(m, justPath, servletPath);
        return Optional.of(new DispatchTarget(m.servlet(), m.servletName(),
                justPath, servletPath, pathInfo, queryString));
    }

    public static String servletPathFor(ServletDispatcher.Mapping m, String path) {
        return switch (m.matcher().kind()) {
            case EXACT -> m.matcher().pattern();
            case PREFIX -> {
                String prefix = m.matcher().pattern();
                yield prefix.substring(0, prefix.length() - 2);
            }
            case EXTENSION -> path;
            case DEFAULT, EMPTY -> "";
        };
    }

    /** Resolution by servlet name (Servlet 6.1 §9.1 getNamedDispatcher). */
    public Optional<DispatchTarget> resolveByName(String servletName) {
        if (servletName == null) return Optional.empty();
        for (ServletDispatcher.Mapping m : dispatcher.mappings()) {
            if (servletName.equals(m.servletName())) {
                // Pas de path associé — servletPath/pathInfo/query laissés vides pour un
                // dispatcher nommé (§9.3 : ne reflète pas l'URL d'origine).
                return Optional.of(new DispatchTarget(m.servlet(), m.servletName(),
                        "/", "", null, null));
            }
        }
        return Optional.empty();
    }

    public static String pathInfoFor(ServletDispatcher.Mapping m, String path, String servletPath) {
        return switch (m.matcher().kind()) {
            case PREFIX -> {
                String rest = path.substring(servletPath.length());
                yield rest.isEmpty() ? null : rest;
            }
            case EXACT, EXTENSION, DEFAULT, EMPTY -> null;
        };
    }
}
