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
import jakarta.servlet.SessionTrackingMode;

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

    /** Kind of descriptor, from the root element. */
    public enum Kind { WEB_APP, WEB_FRAGMENT }

    /** Marker inside {@link #absoluteOrdering()} for {@code <others/>}. */
    public static final String OTHERS = "\u0000others";

    private Kind kind = Kind.WEB_APP;
    private String fragmentName;
    private Ordering ordering = Ordering.NONE;
    private List<String> absoluteOrdering;

    public Kind kind() { return kind; }
    public WebAppDescriptor withKind(Kind k) {
        this.kind = k;
        return this;
    }

    /** {@code <name>} of a fragment; {@code null} when absent. */
    public String fragmentName() { return fragmentName; }
    public WebAppDescriptor withFragmentName(String n) {
        this.fragmentName = n;
        return this;
    }

    /** Relative ordering of a fragment (§8.2.2); {@link Ordering#NONE} when absent. */
    public Ordering ordering() { return ordering; }
    public WebAppDescriptor withOrdering(Ordering o) {
        this.ordering = o == null ? Ordering.NONE : o;
        return this;
    }

    /** web.xml only: names in order, {@link #OTHERS} for {@code <others/>}; {@code null} when absent. */
    public List<String> absoluteOrdering() { return absoluteOrdering; }
    public WebAppDescriptor withAbsoluteOrdering(List<String> a) {
        this.absoluteOrdering = a == null ? null : List.copyOf(a);
        return this;
    }

    public record ServletDef(String name, String className, Map<String, String> initParams,
                             Boolean asyncSupported, int loadOnStartup,
                             MultipartConfigDef multipartConfig, boolean enabled,
                             String runAs, String jspFile) {
        public ServletDef(String name, String className, Map<String, String> initParams,
                          Boolean asyncSupported, int loadOnStartup) {
            this(name, className, initParams, asyncSupported, loadOnStartup, null, true, null, null);
        }
        public ServletDef(String name, String className, Map<String, String> initParams,
                          Boolean asyncSupported) {
            this(name, className, initParams, asyncSupported, Integer.MIN_VALUE);
        }
        public ServletDef(String name, String className, Map<String, String> initParams) {
            this(name, className, initParams, null, Integer.MIN_VALUE);
        }
    }
    /** Defaults per web-common_6_1.xsd: sizes {@code -1}, threshold {@code 0}. */
    public record MultipartConfigDef(String location, long maxFileSize, long maxRequestSize,
                                     int fileSizeThreshold) {}
    public record CookieConfigDef(String name, String domain, String path, String comment,
                                  Boolean httpOnly, Boolean secure, Integer maxAge,
                                  Map<String, String> attributes) {
        public CookieConfigDef {
            attributes = attributes == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }
    public record ServletMappingDef(String servletName, String urlPattern) {}
    public record FilterDef(String name, String className, Map<String, String> initParams,
                            Boolean asyncSupported) {
        public FilterDef(String name, String className, Map<String, String> initParams) {
            this(name, className, initParams, null);
        }
    }
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

    /** Value of the {@code metadata-complete} attribute (default {@code false}). */
    private boolean metadataComplete;
    public boolean metadataComplete() { return metadataComplete; }
    public WebAppDescriptor withMetadataComplete(boolean v) {
        this.metadataComplete = v;
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

    private List<String> welcomeFiles = List.of();
    private Map<String, String> mimeMappings = Map.of();
    private String requestCharacterEncoding;
    private String responseCharacterEncoding;
    private String defaultContextPath;
    private boolean denyUncoveredHttpMethods;
    private CookieConfigDef cookieConfig;
    private Set<SessionTrackingMode> trackingModes = Set.of();
    private List<SecurityDefs.SecurityConstraintDef> securityConstraints = List.of();
    private SecurityDefs.LoginConfigDef loginConfig;
    private List<String> securityRoles = List.of();

    /** Welcome files in declaration order. */
    public List<String> welcomeFiles() { return welcomeFiles; }
    public WebAppDescriptor withWelcomeFiles(List<String> v) {
        this.welcomeFiles = List.copyOf(v);
        return this;
    }

    /** Extension (no dot, lower-case) to MIME type. */
    public Map<String, String> mimeMappings() { return mimeMappings; }
    public WebAppDescriptor withMimeMappings(Map<String, String> v) {
        this.mimeMappings = Collections.unmodifiableMap(new LinkedHashMap<>(v));
        return this;
    }

    /** {@code null} when absent. */
    public String requestCharacterEncoding() { return requestCharacterEncoding; }
    public WebAppDescriptor withRequestCharacterEncoding(String v) {
        this.requestCharacterEncoding = v;
        return this;
    }

    /** {@code null} when absent. */
    public String responseCharacterEncoding() { return responseCharacterEncoding; }
    public WebAppDescriptor withResponseCharacterEncoding(String v) {
        this.responseCharacterEncoding = v;
        return this;
    }

    /** {@code null} when absent. */
    public String defaultContextPath() { return defaultContextPath; }
    public WebAppDescriptor withDefaultContextPath(String v) {
        this.defaultContextPath = v;
        return this;
    }

    public boolean denyUncoveredHttpMethods() { return denyUncoveredHttpMethods; }
    public WebAppDescriptor withDenyUncoveredHttpMethods(boolean v) {
        this.denyUncoveredHttpMethods = v;
        return this;
    }

    /** {@code null} when absent. */
    public CookieConfigDef cookieConfig() { return cookieConfig; }
    public WebAppDescriptor withCookieConfig(CookieConfigDef v) {
        this.cookieConfig = v;
        return this;
    }

    /** Empty when absent. */
    public Set<SessionTrackingMode> trackingModes() { return trackingModes; }
    public WebAppDescriptor withTrackingModes(Set<SessionTrackingMode> v) {
        this.trackingModes = v.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(v));
        return this;
    }

    public List<SecurityDefs.SecurityConstraintDef> securityConstraints() { return securityConstraints; }
    public WebAppDescriptor withSecurityConstraints(List<SecurityDefs.SecurityConstraintDef> v) {
        this.securityConstraints = List.copyOf(v);
        return this;
    }

    /** {@code null} when absent. */
    public SecurityDefs.LoginConfigDef loginConfig() { return loginConfig; }
    public WebAppDescriptor withLoginConfig(SecurityDefs.LoginConfigDef v) {
        this.loginConfig = v;
        return this;
    }

    public List<String> securityRoles() { return securityRoles; }
    public WebAppDescriptor withSecurityRoles(List<String> v) {
        this.securityRoles = List.copyOf(v);
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
