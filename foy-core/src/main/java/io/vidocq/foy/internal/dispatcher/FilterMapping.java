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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Association of a {@link Filter} with either a url-pattern ({@code matcher}) or a servlet name
 * ({@code servletName}, {@code "*"} meaning every servlet), and a subset of {@link DispatcherType}
 * (Servlet 6.1 spec section 6.2). Exactly one of {@code matcher} and {@code servletName} is set: a
 * servlet-name mapping is never expanded into the servlet's URL patterns (section 6.2.4).
 * {@code asyncSupported} is the filter's declared async support (section 2.3.3.3).
 */
public record FilterMapping(UrlPatternMatcher matcher,
                            Filter filter,
                            String filterName,
                            Set<DispatcherType> dispatcherTypes,
                            boolean asyncSupported,
                            String servletName) {

    /** {@code <servlet-name>*</servlet-name>}: the mapping applies to every servlet. */
    public static final String ALL_SERVLETS = "*";

    /** A URL-pattern mapping of a filter that does not declare async support (the servlet default). */
    public FilterMapping(UrlPatternMatcher matcher, Filter filter, String filterName,
                         Set<DispatcherType> dispatcherTypes) {
        this(matcher, filter, filterName, dispatcherTypes, false);
    }

    /** A URL-pattern mapping. */
    public FilterMapping(UrlPatternMatcher matcher, Filter filter, String filterName,
                         Set<DispatcherType> dispatcherTypes, boolean asyncSupported) {
        this(Objects.requireNonNull(matcher, "matcher"), filter, filterName, dispatcherTypes, asyncSupported, null);
    }

    public FilterMapping {
        if ((matcher == null) == (servletName == null)) {
            throw new IllegalArgumentException("a filter mapping has either a url-pattern or a servlet name");
        }
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(filterName, "filterName");
        Objects.requireNonNull(dispatcherTypes, "dispatcherTypes");
        if (dispatcherTypes.isEmpty()) {
            dispatcherTypes = EnumSet.of(DispatcherType.REQUEST);
        } else {
            dispatcherTypes = EnumSet.copyOf(dispatcherTypes);
        }
    }

    /** Convenience: filter mapped to REQUEST only. */
    public static FilterMapping onRequest(UrlPatternMatcher matcher, Filter filter, String name) {
        return new FilterMapping(matcher, filter, name, EnumSet.of(DispatcherType.REQUEST));
    }

    /** A servlet-name mapping ({@link #ALL_SERVLETS} for every servlet). */
    public static FilterMapping forServletName(String servletName, Filter filter, String name,
                                               Set<DispatcherType> dispatcherTypes, boolean asyncSupported) {
        return new FilterMapping(null, filter, name, dispatcherTypes, asyncSupported,
                Objects.requireNonNull(servletName, "servletName"));
    }

    /** True for a servlet-name mapping. */
    public boolean byServletName() { return servletName != null; }

    /** A URL-pattern mapping matching {@code path} for {@code type}; {@code path == null} never matches. */
    public boolean applies(String path, DispatcherType type) {
        return matcher != null && path != null && dispatcherTypes.contains(type) && matcher.matches(path);
    }

    /** A servlet-name mapping naming {@code targetServlet} (or {@code *}) for {@code type}. */
    public boolean appliesToServlet(String targetServlet, DispatcherType type) {
        return servletName != null && targetServlet != null && dispatcherTypes.contains(type)
                && (ALL_SERVLETS.equals(servletName) || servletName.equals(targetServlet));
    }
}
