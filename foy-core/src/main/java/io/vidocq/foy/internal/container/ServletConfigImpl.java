package io.vidocq.foy.internal.container;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ServletConfig} minimum changed to {@link jakarta.servlet.Servlet#init} when
 * of startups. Exposes the logical name of the servlet, its {@link ServletContext} and
 * its init-params.
 */
public final class ServletConfigImpl implements ServletConfig {

    private final String servletName;
    private final ServletContext servletContext;
    private final Map<String, String> initParameters;

    public ServletConfigImpl(String servletName, ServletContext servletContext,
                             Map<String, String> initParameters) {
        this.servletName = Objects.requireNonNull(servletName);
        this.servletContext = Objects.requireNonNull(servletContext);
        this.initParameters = Map.copyOf(
                initParameters == null ? Map.of() : new LinkedHashMap<>(initParameters));
    }

    @Override public String getServletName() { return servletName; }
    @Override public ServletContext getServletContext() { return servletContext; }
    @Override public String getInitParameter(String name) { return initParameters.get(name); }
    @Override public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
}
