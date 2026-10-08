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

import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.spi.security.SecurityProvider;

import java.util.Objects;
import java.util.Set;

/**
 * Container-side settings of a deployment, everything {@link WebAppDeployer} needs
 * besides the {@link WebAppModel} itself.
 *
 * @param securityProvider     security provider, {@code null} for none
 * @param resourceProvider     static resource provider, {@code null} for none
 * @param servletContextName   servlet context name; {@code null} falls back to the model display name
 * @param reservedServletNames servlet names a dynamic {@code addServlet} must not take
 * @param reservedFilterNames  filter names a dynamic {@code addFilter} must not take
 * @param reservedUrlPatterns  URL patterns a dynamic {@code addMapping} must not take
 * @param componentFactory     loads and instantiates dynamically registered components
 * @param handlesTypes         computes the class set passed to each initializer
 */
public record DeployOptions(SecurityProvider securityProvider,
                            VidocqServletContext.ResourceProvider resourceProvider,
                            String servletContextName,
                            Set<String> reservedServletNames,
                            Set<String> reservedFilterNames,
                            Set<String> reservedUrlPatterns,
                            ComponentFactory componentFactory,
                            HandlesTypesResolver handlesTypes) {

    public DeployOptions {
        reservedServletNames = Set.copyOf(reservedServletNames);
        reservedFilterNames = Set.copyOf(reservedFilterNames);
        reservedUrlPatterns = Set.copyOf(reservedUrlPatterns);
        Objects.requireNonNull(componentFactory, "componentFactory");
        Objects.requireNonNull(handlesTypes, "handlesTypes");
    }

    /** Registry-backed component factory (generated, Class-File, then reflective tiers) and class-index {@code @HandlesTypes} resolution on {@code loader}, nothing reserved. */
    public static DeployOptions defaults(ClassLoader loader) {
        return defaults(loader, io.vidocq.foy.internal.gen.WebComponentRegistry.forClassLoader(loader));
    }

    /** As {@link #defaults(ClassLoader)}, over an existing {@code registry} (shared with discovery). */
    public static DeployOptions defaults(ClassLoader loader, io.vidocq.foy.internal.gen.WebComponentRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return new DeployOptions(null, null, null, Set.of(), Set.of(), Set.of(),
                new io.vidocq.foy.internal.gen.RegistryComponentFactory(registry, loader),
                new io.vidocq.foy.internal.gen.IndexedHandlesTypesResolver(registry, loader));
    }

    public DeployOptions withComponentFactory(ComponentFactory f) {
        return new DeployOptions(securityProvider, resourceProvider, servletContextName,
                reservedServletNames, reservedFilterNames, reservedUrlPatterns, f, handlesTypes);
    }

    public DeployOptions withHandlesTypes(HandlesTypesResolver r) {
        return new DeployOptions(securityProvider, resourceProvider, servletContextName,
                reservedServletNames, reservedFilterNames, reservedUrlPatterns, componentFactory, r);
    }

    public DeployOptions withSecurityProvider(SecurityProvider p) {
        return new DeployOptions(p, resourceProvider, servletContextName,
                reservedServletNames, reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes);
    }

    public DeployOptions withResourceProvider(VidocqServletContext.ResourceProvider p) {
        return new DeployOptions(securityProvider, p, servletContextName,
                reservedServletNames, reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes);
    }

    public DeployOptions withServletContextName(String n) {
        return new DeployOptions(securityProvider, resourceProvider, n,
                reservedServletNames, reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes);
    }

    public DeployOptions withReserved(Set<String> servletNames, Set<String> filterNames, Set<String> urlPatterns) {
        return new DeployOptions(securityProvider, resourceProvider, servletContextName,
                servletNames, filterNames, urlPatterns, componentFactory, handlesTypes);
    }
}
