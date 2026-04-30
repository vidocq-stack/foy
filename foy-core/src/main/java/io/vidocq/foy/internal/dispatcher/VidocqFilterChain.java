package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * {@link FilterChain} qui itère sur une liste de {@link Filter} puis invoque
 * un {@link Servlet} final.
 *
 * <p>Spec Servlet 6.1 §6.2 : un filtre qui n'appelle pas {@link #doFilter} court-circuite
 * le reste de la chaîne (et le servlet). L'ordre des filtres est celui de la liste.</p>
 */
public final class VidocqFilterChain implements FilterChain {

    private final List<Filter> filters;
    private final Servlet target;
    private int index;

    public VidocqFilterChain(List<Filter> filters, Servlet target) {
        this.filters = Objects.requireNonNull(filters);
        this.target = target;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {
        if (index < filters.size()) {
            Filter filter = filters.get(index++);
            filter.doFilter(request, response, this);
        } else if (target != null) {
            target.service(request, response);
        }
    }

    /** Liste immuable des filtres de la chaîne (pour diagnostic). */
    public List<Filter> filters() {
        return filters;
    }
}
