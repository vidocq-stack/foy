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
 * Association of a {@link Filter} with a url-pattern and a subset
 * of {@link DispatcherType} (Servlet 6.1 spec section 6.2).
 */
public record FilterMapping(UrlPatternMatcher matcher,
                            Filter filter,
                            String filterName,
                            Set<DispatcherType> dispatcherTypes) {

    public FilterMapping {
        Objects.requireNonNull(matcher, "matcher");
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

    public boolean applies(String path, DispatcherType type) {
        return dispatcherTypes.contains(type) && matcher.matches(path);
    }
}
