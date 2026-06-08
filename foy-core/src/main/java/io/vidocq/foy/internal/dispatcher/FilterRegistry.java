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

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps all {@link FilterMapping} and calculates the filter chain
 * applicable to a given path.
 *
 * <p>The order of discovery is preserved, which corresponds to the order of execution
 * filters (Servlet 6.1 spec §6.2.4 — for annotations, the order is not
 * specified; we take the discovery order CDI, stable).</p>
 */
public final class FilterRegistry {

    private final List<FilterMapping> mappings;

    public FilterRegistry(List<FilterMapping> mappings) {
        this.mappings = List.copyOf(mappings);
    }

    public List<FilterMapping> mappings() {
        return mappings;
    }

    /** Filters applicable for a request (path + dispatcherType). */
    public List<Filter> chainFor(String path, DispatcherType type) {
        List<Filter> out = new ArrayList<>();
        for (FilterMapping m : mappings) {
            if (m.applies(path, type)) out.add(m.filter());
        }
        return out;
    }
}
