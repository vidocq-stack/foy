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
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.CookieConfigDef;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.SessionTrackingMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Immutable description of a web application to deploy: everything a deployer needs,
 * independent of where it came from (annotations, {@code web.xml}, programmatic API).
 *
 * @param contextPath            context path of the application
 * @param displayName            display name, may be {@code null}
 * @param contextParams          context initialisation parameters
 * @param servlets               servlet declarations
 * @param filters                filter declarations
 * @param filterMappings         filter mappings; declaration order is chain order
 * @param listeners              listener declarations
 * @param initializers           servlet container initializers
 * @param errorPages             error page registry
 * @param sessionTimeoutMinutes  session timeout in minutes, {@code -1} for the container default
 * @param localeEncodingMappings locale to encoding mappings
 * @param effectiveMajorVersion  effective Servlet major version
 * @param effectiveMinorVersion  effective Servlet minor version
 * @param welcomeFiles           welcome files, in declaration order (dispatch is Phase 4)
 * @param mimeMappings           extension (lower case, no dot) to MIME type
 * @param requestCharacterEncoding  default request encoding, {@code null} when not configured
 * @param responseCharacterEncoding default response encoding, {@code null} when not configured
 * @param defaultContextPath     {@code default-context-path}, {@code null} when absent
 * @param denyUncoveredHttpMethods whether uncovered HTTP methods are denied (not enforced yet, Phase 6)
 * @param cookieConfig           session cookie configuration, {@code null} when absent
 * @param trackingModes          session tracking modes, empty for the container default
 * @param securityConstraints    security constraints (stored only, enforced from Phase 6)
 * @param loginConfig            login configuration (stored only, enforced from Phase 6), may be {@code null}
 * @param securityRoles          declared security roles (stored only)
 */
public record WebAppModel(String contextPath,
                          String displayName,
                          Map<String, String> contextParams,
                          List<ServletDecl> servlets,
                          List<FilterDecl> filters,
                          List<FilterMappingDecl> filterMappings,
                          List<ListenerDecl> listeners,
                          List<ServletContainerInitializer> initializers,
                          ErrorPageRegistry errorPages,
                          int sessionTimeoutMinutes,
                          Map<String, String> localeEncodingMappings,
                          int effectiveMajorVersion,
                          int effectiveMinorVersion,
                          List<String> welcomeFiles,
                          Map<String, String> mimeMappings,
                          String requestCharacterEncoding,
                          String responseCharacterEncoding,
                          String defaultContextPath,
                          boolean denyUncoveredHttpMethods,
                          CookieConfigDef cookieConfig,
                          Set<SessionTrackingMode> trackingModes,
                          List<SecurityConstraintDef> securityConstraints,
                          LoginConfigDef loginConfig,
                          List<String> securityRoles) {

    public WebAppModel {
        validateContextPath(contextPath);
        Objects.requireNonNull(errorPages, "errorPages");
        contextParams = copyOf(contextParams);
        servlets = List.copyOf(servlets);
        filters = List.copyOf(filters);
        filterMappings = List.copyOf(filterMappings);
        listeners = List.copyOf(listeners);
        initializers = List.copyOf(initializers);
        localeEncodingMappings = copyOf(localeEncodingMappings);
        welcomeFiles = List.copyOf(welcomeFiles);
        mimeMappings = copyOf(mimeMappings);
        trackingModes = trackingModes.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(trackingModes));
        securityConstraints = List.copyOf(securityConstraints);
        securityRoles = List.copyOf(securityRoles);
    }

    /**
     * A servlet declaration. {@code loadOnStartup == Integer.MIN_VALUE} means absent;
     * {@code servletSecurity} is the class's {@code @ServletSecurity} constraint, {@code null} for none;
     * {@code multipartConfig} is the effective multipart configuration (descriptor first, else the
     * class's {@code @MultipartConfig}), {@code null} for none; a servlet with {@code enabled == false}
     * is declared but neither instantiated nor mapped.
     */
    public record ServletDecl(String name, Class<? extends Servlet> type,
                              Supplier<? extends Servlet> factory,
                              List<String> urlPatterns, Map<String, String> initParams,
                              int loadOnStartup,
                              boolean asyncSupported,
                              ServletSecurityElement servletSecurity,
                              MultipartConfigElement multipartConfig,
                              boolean enabled) {
        public ServletDecl {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(factory, "factory");
            urlPatterns = List.copyOf(urlPatterns);
            initParams = copyOf(initParams);
        }

        /** An enabled declaration without multipart configuration. */
        public ServletDecl(String name, Class<? extends Servlet> type, Supplier<? extends Servlet> factory,
                           List<String> urlPatterns, Map<String, String> initParams, int loadOnStartup,
                           boolean asyncSupported, ServletSecurityElement servletSecurity) {
            this(name, type, factory, urlPatterns, initParams, loadOnStartup, asyncSupported, servletSecurity,
                    null, true);
        }

        /** A declaration without security constraints. */
        public ServletDecl(String name, Class<? extends Servlet> type, Supplier<? extends Servlet> factory,
                           List<String> urlPatterns, Map<String, String> initParams, int loadOnStartup,
                           boolean asyncSupported) {
            this(name, type, factory, urlPatterns, initParams, loadOnStartup, asyncSupported, null, null, true);
        }
    }

    /** A filter declaration. */
    public record FilterDecl(String name, Class<? extends Filter> type,
                             Supplier<? extends Filter> factory,
                             Map<String, String> initParams, boolean asyncSupported) {
        public FilterDecl {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(factory, "factory");
            initParams = copyOf(initParams);
        }
    }

    /**
     * A filter mapping, by URL pattern or by servlet name (exactly one of the two).
     * An empty dispatcher type set defaults to {@code REQUEST}.
     */
    public record FilterMappingDecl(String filterName,
                                    String urlPattern,
                                    String servletName,
                                    Set<DispatcherType> dispatcherTypes) {
        public FilterMappingDecl {
            Objects.requireNonNull(filterName, "filterName");
            if ((urlPattern == null) == (servletName == null)) {
                throw new IllegalArgumentException("Filter mapping for '" + filterName
                        + "' needs exactly one of urlPattern / servletName");
            }
            dispatcherTypes = dispatcherTypes == null || dispatcherTypes.isEmpty()
                    ? Collections.unmodifiableSet(EnumSet.of(DispatcherType.REQUEST))
                    : Collections.unmodifiableSet(EnumSet.copyOf(dispatcherTypes));
        }
    }

    /** A listener declaration. */
    public record ListenerDecl(Class<? extends EventListener> type,
                               Supplier<? extends EventListener> factory) {
        public ListenerDecl {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(factory, "factory");
        }
    }

    private static Map<String, String> copyOf(Map<String, String> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * Checks a configured context path: {@code ""} or {@code "/"} (the root context), or
     * {@code '/'} followed by non-empty segments, without a trailing {@code '/'}. A segment is
     * never {@code "."} or {@code ".."}, and the path holds no backslash, no control character and
     * none of {@code % ; ? #}: the container matches request URIs against the context path as a
     * literal prefix, so a character that a client would send encoded, or that ends or splits a
     * path, would make the application unreachable or ambiguous.
     *
     * @throws IllegalArgumentException with the reason, for an invalid path
     * @throws NullPointerException     for {@code null}
     */
    public static void validateContextPath(String contextPath) {
        Objects.requireNonNull(contextPath, "contextPath");
        if (contextPath.isEmpty() || "/".equals(contextPath)) return;
        String reason = null;
        if (contextPath.charAt(0) != '/') reason = "must be empty or start with '/'";
        else if (contextPath.endsWith("/")) reason = "must not end with '/'";
        else {
            for (int i = 0; i < contextPath.length() && reason == null; i++) {
                char c = contextPath.charAt(i);
                if (c < 0x20 || c == 0x7f) reason = "must not contain a control character";
                else if (c == '\\') reason = "must not contain a backslash";
                else if ("%;?#".indexOf(c) >= 0) reason = "must not contain '" + c + "'";
            }
            if (reason == null) {
                for (String segment : contextPath.substring(1).split("/", -1)) {
                    if (segment.isEmpty()) reason = "must not contain an empty segment";
                    else if (".".equals(segment) || "..".equals(segment)) {
                        reason = "must not contain a '" + segment + "' segment";
                    }
                    if (reason != null) break;
                }
            }
        }
        if (reason != null) {
            throw new IllegalArgumentException("invalid context path \"" + printable(contextPath) + "\": " + reason);
        }
    }

    /** The path with control characters escaped as Java Unicode escapes, for an error message. */
    private static String printable(String s) {
        var out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c == 0x7f) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    public static Builder builder(String contextPath) {
        return new Builder(contextPath);
    }

    /** Mutable builder; {@link #build()} validates and freezes the model. */
    public static final class Builder {
        private final String contextPath;
        private String displayName;
        private final Map<String, String> contextParams = new LinkedHashMap<>();
        private final List<ServletDecl> servlets = new ArrayList<>();
        private final List<FilterDecl> filters = new ArrayList<>();
        private final List<FilterMappingDecl> filterMappings = new ArrayList<>();
        private final List<ListenerDecl> listeners = new ArrayList<>();
        private final List<ServletContainerInitializer> initializers = new ArrayList<>();
        private ErrorPageRegistry errorPages = new ErrorPageRegistry();
        private int sessionTimeoutMinutes = -1;
        private Map<String, String> localeEncodingMappings = Map.of();
        private int effectiveMajorVersion = 6;
        private int effectiveMinorVersion = 1;
        private List<String> welcomeFiles = List.of();
        private Map<String, String> mimeMappings = Map.of();
        private String requestCharacterEncoding;
        private String responseCharacterEncoding;
        private String defaultContextPath;
        private boolean denyUncoveredHttpMethods;
        private CookieConfigDef cookieConfig;
        private Set<SessionTrackingMode> trackingModes = Set.of();
        private List<SecurityConstraintDef> securityConstraints = List.of();
        private LoginConfigDef loginConfig;
        private List<String> securityRoles = List.of();

        private Builder(String contextPath) {
            this.contextPath = Objects.requireNonNull(contextPath, "contextPath");
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder contextParam(String name, String value) {
            contextParams.put(name, value);
            return this;
        }

        public Builder servlet(ServletDecl servlet) {
            servlets.add(Objects.requireNonNull(servlet, "servlet"));
            return this;
        }

        public Builder filter(FilterDecl filter) {
            filters.add(Objects.requireNonNull(filter, "filter"));
            return this;
        }

        public Builder filterMapping(FilterMappingDecl mapping) {
            filterMappings.add(Objects.requireNonNull(mapping, "mapping"));
            return this;
        }

        public Builder listener(ListenerDecl listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        public Builder initializer(ServletContainerInitializer initializer) {
            initializers.add(Objects.requireNonNull(initializer, "initializer"));
            return this;
        }

        public Builder errorPages(ErrorPageRegistry errorPages) {
            this.errorPages = Objects.requireNonNull(errorPages, "errorPages");
            return this;
        }

        public Builder sessionTimeoutMinutes(int minutes) {
            this.sessionTimeoutMinutes = minutes;
            return this;
        }

        public Builder localeEncodingMappings(Map<String, String> mappings) {
            this.localeEncodingMappings = Objects.requireNonNull(mappings, "mappings");
            return this;
        }

        public Builder effectiveVersion(int major, int minor) {
            this.effectiveMajorVersion = major;
            this.effectiveMinorVersion = minor;
            return this;
        }

        public Builder welcomeFiles(List<String> welcomeFiles) {
            this.welcomeFiles = Objects.requireNonNull(welcomeFiles, "welcomeFiles");
            return this;
        }

        public Builder mimeMappings(Map<String, String> mimeMappings) {
            this.mimeMappings = Objects.requireNonNull(mimeMappings, "mimeMappings");
            return this;
        }

        public Builder requestCharacterEncoding(String requestCharacterEncoding) {
            this.requestCharacterEncoding = requestCharacterEncoding;
            return this;
        }

        public Builder responseCharacterEncoding(String responseCharacterEncoding) {
            this.responseCharacterEncoding = responseCharacterEncoding;
            return this;
        }

        public Builder defaultContextPath(String defaultContextPath) {
            this.defaultContextPath = defaultContextPath;
            return this;
        }

        public Builder denyUncoveredHttpMethods(boolean denyUncoveredHttpMethods) {
            this.denyUncoveredHttpMethods = denyUncoveredHttpMethods;
            return this;
        }

        public Builder cookieConfig(CookieConfigDef cookieConfig) {
            this.cookieConfig = cookieConfig;
            return this;
        }

        public Builder trackingModes(Set<SessionTrackingMode> trackingModes) {
            this.trackingModes = Objects.requireNonNull(trackingModes, "trackingModes");
            return this;
        }

        public Builder securityConstraints(List<SecurityConstraintDef> securityConstraints) {
            this.securityConstraints = Objects.requireNonNull(securityConstraints, "securityConstraints");
            return this;
        }

        public Builder loginConfig(LoginConfigDef loginConfig) {
            this.loginConfig = loginConfig;
            return this;
        }

        public Builder securityRoles(List<String> securityRoles) {
            this.securityRoles = Objects.requireNonNull(securityRoles, "securityRoles");
            return this;
        }

        /**
         * Builds the model.
         *
         * @throws IllegalStateException on a duplicate servlet or filter name, or on a filter
         *                               mapping that references an undeclared filter
         */
        public WebAppModel build() {
            Set<String> servletNames = new HashSet<>();
            for (ServletDecl s : servlets) {
                if (!servletNames.add(s.name())) {
                    throw new IllegalStateException("Duplicate servlet name: " + s.name());
                }
            }
            Set<String> filterNames = new HashSet<>();
            for (FilterDecl f : filters) {
                if (!filterNames.add(f.name())) {
                    throw new IllegalStateException("Duplicate filter name: " + f.name());
                }
            }
            for (FilterMappingDecl m : filterMappings) {
                if (!filterNames.contains(m.filterName())) {
                    throw new IllegalStateException(
                            "Filter mapping references undeclared filter: " + m.filterName());
                }
            }
            return new WebAppModel(contextPath, displayName, contextParams, servlets, filters,
                    filterMappings, listeners, initializers, errorPages, sessionTimeoutMinutes,
                    localeEncodingMappings, effectiveMajorVersion, effectiveMinorVersion, welcomeFiles,
                    mimeMappings, requestCharacterEncoding, responseCharacterEncoding, defaultContextPath,
                    denyUncoveredHttpMethods, cookieConfig, trackingModes, securityConstraints, loginConfig,
                    securityRoles);
        }
    }
}
