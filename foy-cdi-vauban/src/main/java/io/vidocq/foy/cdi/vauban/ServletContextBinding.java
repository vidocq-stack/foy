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
package io.vidocq.foy.cdi.vauban;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import java.util.Optional;

/**
 * Attaches the deployment's {@link ServletContext} to the {@link ServletContextHolder}. Foy registers it ahead of
 * the application's listeners, before {@code contextInitialized} fires: an application listener can use the
 * injected context in {@code contextInitialized}, and still in {@code contextDestroyed}, which runs in reverse order.
 */
final class ServletContextBinding implements ServletContextListener {

    private final BeanManager beanManager;
    /** The client proxy of the application-scoped holder. */
    private final ServletContextHolder holder;

    private ServletContextBinding(BeanManager beanManager, ServletContextHolder holder) {
        this.beanManager = beanManager;
        this.holder = holder;
    }

    /** The binding for the container of {@code beanManager}; empty when foy-cdi-vauban's beans are not deployed. */
    static Optional<ServletContextBinding> of(BeanManager beanManager) {
        var holders = beanManager.createInstance().select(ServletContextHolder.class);
        return holders.isResolvable()
                ? Optional.of(new ServletContextBinding(beanManager, holders.get())) : Optional.empty();
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        holder.attach(event.getServletContext());
    }

    /**
     * Detaches the context, and destroys the instance of the application-scoped {@code ServletContext} bean, which
     * is that context: a deployment on the same container after this one (a dev-mode reload) gets its own.
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (holder.detach(event.getServletContext())
                && beanManager.getContext(ApplicationScoped.class) instanceof AlterableContext context) {
            beanManager.getBeans(ServletContext.class).stream()
                    .filter(bean -> bean.getBeanClass() == ServletBuiltInBeans.class)
                    .forEach(context::destroy);
        }
    }
}
