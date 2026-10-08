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
package io.vidocq.foy.spi.gen;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletSecurityElement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable static metadata of one web component, as declared by its annotations.
 *
 * <p>Collections are defensively copied and never {@code null}: a {@code null}
 * collection component is replaced by an empty one. {@code initParams} keeps
 * declaration order. The {@code with*} methods return modified copies and are
 * meant to keep generated code short.
 *
 * @param kind            the kind of component
 * @param name            the component name; {@code null} for LISTENER, INITIALIZER and PLAIN
 * @param urlPatterns     the URL patterns
 * @param initParams      the init parameters, in declaration order
 * @param loadOnStartup   the load-on-startup order; {@link Integer#MIN_VALUE} when absent
 * @param asyncSupported  whether asynchronous processing is supported
 * @param dispatcherTypes the dispatcher types a filter applies to
 * @param servletNames    the servlet names a filter applies to ({@code @WebFilter(servletNames)})
 * @param multipartConfig the multipart configuration, or {@code null}
 * @param servletSecurity the servlet security constraints, or {@code null}
 * @param declaredRoles   the declared security roles
 * @param runAs           the run-as role, or {@code null}
 * @param handlesTypes    the fully qualified class names handled by an INITIALIZER
 */
public record WebComponentDescriptor(Kind kind,
                                     String name,
                                     List<String> urlPatterns,
                                     Map<String, String> initParams,
                                     int loadOnStartup,
                                     boolean asyncSupported,
                                     Set<DispatcherType> dispatcherTypes,
                                     List<String> servletNames,
                                     MultipartConfigElement multipartConfig,
                                     ServletSecurityElement servletSecurity,
                                     List<String> declaredRoles,
                                     String runAs,
                                     List<String> handlesTypes) {

    /** The kind of web component. */
    public enum Kind { SERVLET, FILTER, LISTENER, INITIALIZER, PLAIN }

    /** Copies collections into unmodifiable ones; null collections become empty. */
    public WebComponentDescriptor {
        urlPatterns = urlPatterns == null ? List.of() : List.copyOf(urlPatterns);
        initParams = initParams == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(initParams));
        dispatcherTypes = dispatcherTypes == null ? Set.of() : Set.copyOf(dispatcherTypes);
        servletNames = servletNames == null ? List.of() : List.copyOf(servletNames);
        declaredRoles = declaredRoles == null ? List.of() : List.copyOf(declaredRoles);
        handlesTypes = handlesTypes == null ? List.of() : List.copyOf(handlesTypes);
    }

    /**
     * Starting point for generated code.
     *
     * @return a {@link Kind#PLAIN} descriptor with every other component empty or absent
     */
    public static WebComponentDescriptor plain() {
        return new WebComponentDescriptor(Kind.PLAIN, null, List.of(), Map.of(), Integer.MIN_VALUE,
                false, Set.of(), List.of(), null, null, List.of(), null, List.of());
    }

    /** Returns a copy with the given kind. */
    public WebComponentDescriptor withKind(Kind k) {
        return new WebComponentDescriptor(k, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given name. */
    public WebComponentDescriptor withName(String n) {
        return new WebComponentDescriptor(kind, n, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given URL patterns. */
    public WebComponentDescriptor withUrlPatterns(String... p) {
        return new WebComponentDescriptor(kind, name, List.of(p), initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /**
     * Returns a copy with the given init parameters, kept in the given order.
     *
     * @param keyValuePairs alternating keys and values; must have an even length
     * @throws IllegalArgumentException if the number of arguments is odd
     */
    public WebComponentDescriptor withInitParams(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "init parameters must be key/value pairs, got " + keyValuePairs.length + " arguments");
        }
        var params = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            params.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new WebComponentDescriptor(kind, name, urlPatterns, params, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given load-on-startup order. */
    public WebComponentDescriptor withLoadOnStartup(int v) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, v,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given asynchronous-support flag. */
    public WebComponentDescriptor withAsyncSupported(boolean v) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                v, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given dispatcher types. */
    public WebComponentDescriptor withDispatcherTypes(DispatcherType... t) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, Set.of(t), servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given servlet names. */
    public WebComponentDescriptor withServletNames(String... n) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, List.of(n), multipartConfig, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given multipart configuration (nullable). */
    public WebComponentDescriptor withMultipartConfig(MultipartConfigElement m) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, m, servletSecurity,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given servlet security element (nullable). */
    public WebComponentDescriptor withServletSecurity(ServletSecurityElement s) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, s,
                declaredRoles, runAs, handlesTypes);
    }

    /** Returns a copy with the given declared roles. */
    public WebComponentDescriptor withDeclaredRoles(String... r) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                List.of(r), runAs, handlesTypes);
    }

    /** Returns a copy with the given run-as role (nullable). */
    public WebComponentDescriptor withRunAs(String r) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, r, handlesTypes);
    }

    /** Returns a copy with the given handled type names (fully qualified). */
    public WebComponentDescriptor withHandlesTypes(String... fqcns) {
        return new WebComponentDescriptor(kind, name, urlPatterns, initParams, loadOnStartup,
                asyncSupported, dispatcherTypes, servletNames, multipartConfig, servletSecurity,
                declaredRoles, runAs, List.of(fqcns));
    }
}
