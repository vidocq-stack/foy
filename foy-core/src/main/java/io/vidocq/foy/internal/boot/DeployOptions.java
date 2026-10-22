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
import io.vidocq.foy.internal.http.RequestLimits;
import io.vidocq.foy.internal.security.SecuritySettings;
import io.vidocq.foy.spi.security.IdentityStore;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Container-side settings of a deployment, everything {@link WebAppDeployer} needs
 * besides the {@link WebAppModel} itself.
 *
 * @param security             security settings; {@code null} gives {@link SecuritySettings#DEFAULTS}
 * @param resourceProvider     static resource provider, {@code null} for none
 * @param servletContextName   servlet context name; {@code null} falls back to the model display name
 * @param reservedServletNames servlet names a dynamic {@code addServlet} must not take
 * @param reservedFilterNames  filter names a dynamic {@code addFilter} must not take
 * @param reservedUrlPatterns  URL patterns a dynamic {@code addMapping} must not take
 * @param componentFactory     loads and instantiates dynamically registered components
 * @param handlesTypes         computes the class set passed to each initializer
 * @param virtualServerName    {@code ServletContext.getVirtualServerName()}; {@code null} gives {@code "vidocq"}
 * @param tempDirRoot          directory under which the context temp dir is created; {@code null} gives
 *                             {@code java.io.tmpdir}
 * @param requestLimits        container ceilings on request bodies; {@code null} gives {@link RequestLimits#DEFAULTS}
 * @param applicationLoader    the application's class loader: {@code ServletContext.getClassLoader()} and the
 *                             thread context class loader while Foy runs application code (BUG-20261022-06);
 *                             {@code null} leaves the context class loader untouched
 */
public record DeployOptions(SecuritySettings security,
                            VidocqServletContext.ResourceProvider resourceProvider,
                            String servletContextName,
                            Set<String> reservedServletNames,
                            Set<String> reservedFilterNames,
                            Set<String> reservedUrlPatterns,
                            ComponentFactory componentFactory,
                            HandlesTypesResolver handlesTypes,
                            String virtualServerName,
                            Path tempDirRoot,
                            RequestLimits requestLimits,
                            ClassLoader applicationLoader) {

    public DeployOptions {
        if (security == null) security = SecuritySettings.DEFAULTS;
        if (requestLimits == null) requestLimits = RequestLimits.DEFAULTS;
        reservedServletNames = Set.copyOf(reservedServletNames);
        reservedFilterNames = Set.copyOf(reservedFilterNames);
        reservedUrlPatterns = Set.copyOf(reservedUrlPatterns);
        Objects.requireNonNull(componentFactory, "componentFactory");
        Objects.requireNonNull(handlesTypes, "handlesTypes");
    }

    /** Without application class loader (the context class loader is left untouched). */
    public DeployOptions(SecuritySettings security,
                         VidocqServletContext.ResourceProvider resourceProvider,
                         String servletContextName,
                         Set<String> reservedServletNames,
                         Set<String> reservedFilterNames,
                         Set<String> reservedUrlPatterns,
                         ComponentFactory componentFactory,
                         HandlesTypesResolver handlesTypes,
                         String virtualServerName,
                         Path tempDirRoot,
                         RequestLimits requestLimits) {
        this(security, resourceProvider, servletContextName, reservedServletNames, reservedFilterNames,
                reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName, tempDirRoot, requestLimits,
                null);
    }

    /** Without virtual server name, temp-dir root, request limits (their defaults apply) and application class loader. */
    public DeployOptions(SecuritySettings security,
                         VidocqServletContext.ResourceProvider resourceProvider,
                         String servletContextName,
                         Set<String> reservedServletNames,
                         Set<String> reservedFilterNames,
                         Set<String> reservedUrlPatterns,
                         ComponentFactory componentFactory,
                         HandlesTypesResolver handlesTypes) {
        this(security, resourceProvider, servletContextName, reservedServletNames, reservedFilterNames,
                reservedUrlPatterns, componentFactory, handlesTypes, null, null, null, null);
    }

    /**
     * Registry-backed component factory (generated, Class-File, then reflective tiers) and class-index
     * {@code @HandlesTypes} resolution on {@code loader}, nothing reserved; {@code loader} is the
     * application class loader.
     */
    public static DeployOptions defaults(ClassLoader loader) {
        return defaults(loader, io.vidocq.foy.internal.gen.WebComponentRegistry.forClassLoader(loader));
    }

    /** As {@link #defaults(ClassLoader)}, over an existing {@code registry} (shared with discovery). */
    public static DeployOptions defaults(ClassLoader loader, io.vidocq.foy.internal.gen.WebComponentRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return new DeployOptions(null, null, null, Set.of(), Set.of(), Set.of(),
                new io.vidocq.foy.internal.gen.RegistryComponentFactory(registry, loader),
                new io.vidocq.foy.internal.gen.IndexedHandlesTypesResolver(registry, loader),
                null, null, null, loader);
    }

    /**
     * As {@link #defaults(ClassLoader, io.vidocq.foy.internal.gen.WebComponentRegistry)}, and the
     * {@code @HandlesTypes} resolution also scans the class bytes of {@code handlesTypesRoots}
     * (jars and directories) that ship no {@code META-INF/foy/class-index.list}. The roots are the
     * application roots, the ordered fragments' jars and the jars of the retained initializers.
     */
    public static DeployOptions defaults(ClassLoader loader,
                                         io.vidocq.foy.internal.gen.WebComponentRegistry registry,
                                         java.util.List<java.nio.file.Path> handlesTypesRoots) {
        return defaults(loader, registry, handlesTypesRoots, Set.of());
    }

    /**
     * As {@link #defaults(ClassLoader, io.vidocq.foy.internal.gen.WebComponentRegistry, java.util.List)},
     * and the class indexes of {@code excludedRoots} (the jars an absolute ordering excludes) are
     * not read: their classes are never handed to a {@code @HandlesTypes} initializer.
     */
    public static DeployOptions defaults(ClassLoader loader,
                                         io.vidocq.foy.internal.gen.WebComponentRegistry registry,
                                         java.util.List<java.nio.file.Path> handlesTypesRoots,
                                         Set<java.net.URL> excludedRoots) {
        Objects.requireNonNull(registry, "registry");
        return new DeployOptions(null, null, null, Set.of(), Set.of(), Set.of(),
                new io.vidocq.foy.internal.gen.RegistryComponentFactory(registry, loader),
                new io.vidocq.foy.internal.gen.IndexedHandlesTypesResolver(registry, loader,
                        java.util.List.copyOf(handlesTypesRoots), excludedRoots),
                null, null, null, loader);
    }

    public DeployOptions withComponentFactory(ComponentFactory f) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, f, handlesTypes, virtualServerName, tempDirRoot, requestLimits,
                applicationLoader);
    }

    public DeployOptions withHandlesTypes(HandlesTypesResolver r) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, r, virtualServerName, tempDirRoot,
                requestLimits, applicationLoader);
    }

    public DeployOptions withSecurity(SecuritySettings s) {
        return new DeployOptions(s, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName,
                tempDirRoot, requestLimits, applicationLoader);
    }

    public DeployOptions withIdentityStore(IdentityStore store) {
        return withSecurity(security.withIdentityStore(store));
    }

    public DeployOptions withResourceProvider(VidocqServletContext.ResourceProvider p) {
        return new DeployOptions(security, p, servletContextName, reservedServletNames, reservedFilterNames,
                reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName, tempDirRoot, requestLimits,
                applicationLoader);
    }

    public DeployOptions withServletContextName(String n) {
        return new DeployOptions(security, resourceProvider, n, reservedServletNames, reservedFilterNames,
                reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName, tempDirRoot, requestLimits,
                applicationLoader);
    }

    public DeployOptions withReserved(Set<String> servletNames, Set<String> filterNames, Set<String> urlPatterns) {
        return new DeployOptions(security, resourceProvider, servletContextName, servletNames, filterNames,
                urlPatterns, componentFactory, handlesTypes, virtualServerName, tempDirRoot, requestLimits,
                applicationLoader);
    }

    public DeployOptions withVirtualServerName(String name) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, name, tempDirRoot,
                requestLimits, applicationLoader);
    }

    public DeployOptions withTempDirRoot(Path root) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName, root,
                requestLimits, applicationLoader);
    }

    public DeployOptions withRequestLimits(RequestLimits limits) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName,
                tempDirRoot, limits, applicationLoader);
    }

    /** The application's class loader ({@code null}: the context class loader is left untouched). */
    public DeployOptions withApplicationLoader(ClassLoader loader) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName,
                tempDirRoot, requestLimits, loader);
    }
}
