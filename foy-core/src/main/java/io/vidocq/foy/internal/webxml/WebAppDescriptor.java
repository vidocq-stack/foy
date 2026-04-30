package io.vidocq.foy.internal.webxml;

import jakarta.servlet.DispatcherType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Descripteur d'une application web parsé depuis {@code WEB-INF/web.xml}.
 */
public final class WebAppDescriptor {

    public record ServletDef(String name, String className, Map<String, String> initParams,
                             boolean asyncSupported) {
        public ServletDef(String name, String className, Map<String, String> initParams) {
            this(name, className, initParams, false);
        }
    }
    public record ServletMappingDef(String servletName, String urlPattern) {}
    public record FilterDef(String name, String className, Map<String, String> initParams) {}
    public record FilterMappingDef(String filterName, String urlPattern, String servletName,
                                   Set<DispatcherType> dispatcherTypes) {
        public FilterMappingDef(String filterName, String urlPattern, Set<DispatcherType> dispatcherTypes) {
            this(filterName, urlPattern, null, dispatcherTypes);
        }
    }
    public record ErrorPageDef(Integer statusCode, String exceptionType, String location) {}

    private final Map<String, String> contextParams;
    private final List<ServletDef> servlets;
    private final List<ServletMappingDef> servletMappings;
    private final List<FilterDef> filters;
    private final List<FilterMappingDef> filterMappings;
    private final List<String> listenerClasses;
    private final List<ErrorPageDef> errorPages;
    private final int sessionTimeoutMinutes;
    private final Map<String, String> localeEncodingMappings;
    /** Version déclarée dans l'attribut {@code web-app/version} (par défaut "6.0"). */
    private String version = "6.0";
    public String version() { return version; }
    public WebAppDescriptor withVersion(String v) {
        if (v != null && !v.isBlank()) this.version = v;
        return this;
    }

    /** {@code <display-name>} du web.xml — exposé via {@link
     *  jakarta.servlet.ServletContext#getServletContextName()}. */
    private String displayName;
    public String displayName() { return displayName; }
    public WebAppDescriptor withDisplayName(String v) {
        if (v != null && !v.isBlank()) this.displayName = v;
        return this;
    }

    public WebAppDescriptor(Map<String, String> contextParams,
                            List<ServletDef> servlets,
                            List<ServletMappingDef> servletMappings,
                            List<FilterDef> filters,
                            List<FilterMappingDef> filterMappings,
                            List<String> listenerClasses,
                            List<ErrorPageDef> errorPages,
                            int sessionTimeoutMinutes) {
        this(contextParams, servlets, servletMappings, filters, filterMappings,
                listenerClasses, errorPages, sessionTimeoutMinutes, Map.of());
    }

    public WebAppDescriptor(Map<String, String> contextParams,
                            List<ServletDef> servlets,
                            List<ServletMappingDef> servletMappings,
                            List<FilterDef> filters,
                            List<FilterMappingDef> filterMappings,
                            List<String> listenerClasses,
                            List<ErrorPageDef> errorPages,
                            int sessionTimeoutMinutes,
                            Map<String, String> localeEncodingMappings) {
        this.contextParams = Collections.unmodifiableMap(new LinkedHashMap<>(contextParams));
        this.servlets = List.copyOf(servlets);
        this.servletMappings = List.copyOf(servletMappings);
        this.filters = List.copyOf(filters);
        this.filterMappings = List.copyOf(filterMappings);
        this.listenerClasses = List.copyOf(listenerClasses);
        this.errorPages = List.copyOf(errorPages);
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.localeEncodingMappings = Collections.unmodifiableMap(
                new LinkedHashMap<>(localeEncodingMappings));
    }

    public static WebAppDescriptor empty() {
        return new WebAppDescriptor(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), -1, Map.of());
    }

    public Map<String, String> contextParams() { return contextParams; }
    public List<ServletDef> servlets() { return servlets; }
    public List<ServletMappingDef> servletMappings() { return servletMappings; }
    public List<FilterDef> filters() { return filters; }
    public List<FilterMappingDef> filterMappings() { return filterMappings; }
    public List<String> listenerClasses() { return listenerClasses; }
    public List<ErrorPageDef> errorPages() { return errorPages; }
    public int sessionTimeoutMinutes() { return sessionTimeoutMinutes; }
    public Map<String, String> localeEncodingMappings() { return localeEncodingMappings; }

    public boolean isEmpty() {
        return contextParams.isEmpty() && servlets.isEmpty() && filters.isEmpty()
                && listenerClasses.isEmpty() && errorPages.isEmpty() && sessionTimeoutMinutes == -1;
    }

    /** Patterns associés à un servlet donné. */
    public List<String> patternsFor(String servletName) {
        return servletMappings.stream()
                .filter(m -> m.servletName().equals(servletName))
                .map(ServletMappingDef::urlPattern)
                .toList();
    }

    public static Set<DispatcherType> defaultDispatcherTypes() {
        return EnumSet.of(DispatcherType.REQUEST);
    }
}
