package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * Conserve l'ensemble des {@link FilterMapping} et calcule la chaîne de filtres
 * applicable à un path donné.
 *
 * <p>L'ordre de découverte est préservé, ce qui correspond à l'ordre d'exécution
 * des filtres (spec Servlet 6.1 §6.2.4 — pour annotations, l'ordre n'est pas
 * spécifié ; on prend l'ordre de découverte CDI, stable).</p>
 */
public final class FilterRegistry {

    private final List<FilterMapping> mappings;

    public FilterRegistry(List<FilterMapping> mappings) {
        this.mappings = List.copyOf(mappings);
    }

    public List<FilterMapping> mappings() {
        return mappings;
    }

    /** Filtres applicables pour une requête (path + dispatcherType). */
    public List<Filter> chainFor(String path, DispatcherType type) {
        List<Filter> out = new ArrayList<>();
        for (FilterMapping m : mappings) {
            if (m.applies(path, type)) out.add(m.filter());
        }
        return out;
    }
}
