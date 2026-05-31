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
 * {@link FilterChain} which iterates over a list of {@link Filter} then invokes
 * a final {@link Servlet}.
 *
 * <p>Spec Servlet 6.1 §6.2: a filter that does not call {@link #doFilter} short-circuits
 * the rest of the chain (and the servlet). The order of the filters is that of the list.</p>
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

    /** Immutable list of chain filters (for diagnostics). */
    public List<Filter> filters() {
        return filters;
    }
}
