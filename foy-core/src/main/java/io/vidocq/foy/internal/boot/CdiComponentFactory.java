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

import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;

import java.util.Objects;

/**
 * Creates a web component through CDI when its class is a bean of its own, and through {@code delegate} otherwise.
 * A servlet, filter or listener that {@code web.xml} declares, or that the application registers by its class
 * ({@code addServlet(String, Class)}, {@code createServlet}, ...), then gets its injection points as an annotated one
 * does, which {@link WebAppDiscovery} creates through CDI too (Servlet 6.1 §15.5, §4.4.3.5).
 *
 * <p>Only a bean whose class is exactly the component class is used: a lookup by type also matches the beans of its
 * subclasses. A class without such a bean, or with two, is created by {@code delegate}, without injection, as before.
 * Loading, visibility and descriptors are {@code delegate}'s.</p>
 */
public final class CdiComponentFactory implements ComponentFactory {

    private final BeanManager beanManager;
    private final ComponentFactory delegate;

    /**
     * @param beanManager the container the beans come from
     * @param delegate    loads the classes, reads their descriptors and creates the components that are no bean
     */
    public CdiComponentFactory(BeanManager beanManager, ComponentFactory delegate) {
        this.beanManager = Objects.requireNonNull(beanManager, "beanManager");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Class<?> load(String className) throws ClassNotFoundException, ServletException {
        return delegate.load(className);
    }

    @Override
    public <T> T newInstance(Class<T> type) throws ServletException {
        Bean<?> bean = ownBean(type);
        if (bean == null) {
            return delegate.newInstance(type);
        }
        try {
            return type.cast(beanManager.getReference(bean, type, beanManager.createCreationalContext(bean)));
        } catch (RuntimeException e) {
            throw new ServletException("cannot create " + type.getName() + " through CDI: " + e, e);
        }
    }

    @Override
    public boolean isVisible(Class<?> type) {
        return delegate.isVisible(type);
    }

    @Override
    public WebComponentDescriptor descriptor(Class<?> type) {
        return delegate.descriptor(type);
    }

    /** The only bean whose class is {@code type}, or {@code null}. */
    private Bean<?> ownBean(Class<?> type) {
        Bean<?> own = null;
        for (Bean<?> bean : beanManager.getBeans(type, Any.Literal.INSTANCE)) {
            if (bean.getBeanClass() == type) {
                if (own != null) {
                    return null;
                }
                own = bean;
            }
        }
        return own;
    }
}
