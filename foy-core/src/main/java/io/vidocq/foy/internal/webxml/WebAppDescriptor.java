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
package io.vidocq.foy.internal.webxml;

import jakarta.servlet.DispatcherType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Descriptor of a web application parsed from {@code WEB-INF/web.xml}.
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
    /** Version declared in the {@code web-app/version} attribute (default "6.0"). */
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

    /** Patterns associated with a given servlet. */
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
