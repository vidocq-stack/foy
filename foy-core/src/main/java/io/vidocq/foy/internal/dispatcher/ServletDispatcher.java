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

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletSecurityElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Associates {@link UrlPatternMatcher} with {@link Servlet} and resolves the servlet
 * the most specific for a given path.
 */
public final class ServletDispatcher {

    /** Pattern-to-servlet association. {@code asyncSupported} reflects
     *  {@code <async-supported>} from web.xml (or {@code @WebServlet(asyncSupported=...)});
     *  default is {@code true} for constructors without this argument. {@code security} holds
     *  the servlet's {@code @ServletSecurity} constraints (or {@code setServletSecurity}),
     *  {@code null} for none. */
    public record Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName,
                          boolean asyncSupported, ServletSecurityElement security) {
        public Mapping {
            Objects.requireNonNull(matcher);
            Objects.requireNonNull(servlet);
            Objects.requireNonNull(servletName);
        }
        public Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName, boolean asyncSupported) {
            this(matcher, servlet, servletName, asyncSupported, null);
        }
        public Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName) {
            this(matcher, servlet, servletName, true);
        }
    }

    private final List<Mapping> mappings;

    public ServletDispatcher(List<Mapping> mappings) {
        List<Mapping> sorted = new ArrayList<>(mappings);
        sorted.sort(Comparator.comparingInt(m -> m.matcher().precedence()));
        this.mappings = List.copyOf(sorted);
    }

    /** Finds the servlet that should respond for the given path. */
    public Optional<Mapping> find(String path) {
        for (Mapping m : mappings) {
            if (m.matcher().matches(path)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public List<Mapping> mappings() {
        return mappings;
    }
}
