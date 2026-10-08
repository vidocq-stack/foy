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
package io.vidocq.foy.internal.gen;

import io.vidocq.foy.internal.boot.ComponentFactory;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.servlet.ServletException;

import java.util.Objects;
import java.util.Set;

/**
 * {@link ComponentFactory} backed by a {@link WebComponentRegistry}: instances are created by the
 * generated, Class-File or reflective tier. Class loading and visibility follow
 * {@link ComponentFactory#reflective(ClassLoader, Set)}.
 */
public final class RegistryComponentFactory implements ComponentFactory {

    private final WebComponentRegistry registry;
    private final ClassLoader loader;
    private final Set<String> visibleNames;

    /**
     * @param registry the component registry
     * @param loader the class loader used by {@link #load(String)}
     */
    public RegistryComponentFactory(WebComponentRegistry registry, ClassLoader loader) {
        this(registry, loader, null);
    }

    /**
     * @param registry the component registry
     * @param loader the class loader used by {@link #load(String)}
     * @param visibleNames the only class names visible to the deployment, or null for all
     */
    public RegistryComponentFactory(WebComponentRegistry registry, ClassLoader loader, Set<String> visibleNames) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.loader = loader;
        this.visibleNames = visibleNames;
    }

    /**
     * A factory over a fresh registry for {@code loader}, every class visible.
     *
     * @param loader the web application class loader
     * @return a new factory
     */
    public static RegistryComponentFactory forClassLoader(ClassLoader loader) {
        return new RegistryComponentFactory(WebComponentRegistry.forClassLoader(loader), loader);
    }

    /** @return the backing registry */
    public WebComponentRegistry registry() {
        return registry;
    }

    @Override
    public Class<?> load(String className) throws ClassNotFoundException, ServletException {
        try {
            return WebComponentRegistry.loadClass(className, loader);
        } catch (LinkageError e) {
            throw new ServletException("cannot load " + className + ": " + e, e);
        }
    }

    @Override
    public WebComponentDescriptor descriptor(Class<?> type) {
        return registry.lookup(type).descriptor();
    }

    @Override
    public <T> T newInstance(Class<T> type) throws ServletException {
        try {
            return type.cast(registry.lookup(type).newInstance());
        } catch (Exception | LinkageError e) {
            // Exception, not RuntimeException: a generated or hidden-class factory calls the
            // constructor directly, so a checked exception it declares escapes undeclared.
            throw new ServletException(instantiationMessage(type, e), e);
        }
    }

    /** "cannot instantiate X: reason", naming the class once even when the reason already does. */
    static String instantiationMessage(Class<?> type, Throwable cause) {
        String prefix = "cannot instantiate " + type.getName();
        String detail = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return detail.startsWith(prefix) ? detail : prefix + ": " + detail;
    }

    @Override
    public boolean isVisible(Class<?> type) {
        return visibleNames == null || visibleNames.contains(type.getName());
    }
}
