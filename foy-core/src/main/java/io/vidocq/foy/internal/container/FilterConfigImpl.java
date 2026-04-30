package io.vidocq.foy.internal.container;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link FilterConfig} minimal passé à {@link jakarta.servlet.Filter#init} au démarrage.
 */
public final class FilterConfigImpl implements FilterConfig {

    private final String filterName;
    private final ServletContext servletContext;
    private final Map<String, String> initParameters;

    public FilterConfigImpl(String filterName, ServletContext servletContext,
                            Map<String, String> initParameters) {
        this.filterName = Objects.requireNonNull(filterName);
        this.servletContext = Objects.requireNonNull(servletContext);
        this.initParameters = Map.copyOf(
                initParameters == null ? Map.of() : new LinkedHashMap<>(initParameters));
    }

    @Override public String getFilterName() { return filterName; }
    @Override public ServletContext getServletContext() { return servletContext; }
    @Override public String getInitParameter(String name) { return initParameters.get(name); }
    @Override public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
}
