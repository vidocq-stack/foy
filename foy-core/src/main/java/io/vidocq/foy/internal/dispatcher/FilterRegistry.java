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
