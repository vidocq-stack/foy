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
package io.vidocq.foy.internal.listener;

import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Activates the CDI request context for each servlet request, through the standard
 * {@link RequestContextController}, and deactivates it when the request ends — so
 * {@code @RequestScoped} beans work in servlets, filters and listeners under any CDI container
 * (foy#18). The container fires the {@code @Initialized} and {@code @Destroyed(RequestScoped.class)}
 * events of the context.
 *
 * <p>Registered first by the deployment when a {@code BeanManager} is given, so the context is
 * active for the application's own request listeners, and deactivated after them. A request ends
 * on the thread that started it (Foy waits for an asynchronous request before its destroyed
 * event), which {@code RequestContextController} needs. The controller of each request is kept
 * here, not in a request attribute, which the application would see.</p>
 *
 * <p>When the context is already active (an embedding runtime activated it), {@code activate()}
 * returns {@code false} and this listener leaves the context alone.</p>
 */
public final class CdiRequestScopeListener implements ServletRequestListener {

    private static final System.Logger LOG = System.getLogger(CdiRequestScopeListener.class.getName());

    private final BeanManager beanManager;
    private final Map<ServletRequest, RequestContextController> active = new ConcurrentHashMap<>();

    public CdiRequestScopeListener(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        RequestContextController controller;
        try {
            var controllers = beanManager.createInstance().select(RequestContextController.class);
            if (!controllers.isResolvable()) {
                return;
            }
            controller = controllers.get();
        } catch (RuntimeException noController) {
            // A BeanManager that cannot supply one (a partial or test implementation): the request
            // runs without a CDI request context, as it did before this listener existed.
            LOG.log(System.Logger.Level.DEBUG, "No RequestContextController: {0}", noController.toString());
            return;
        }
        if (controller.activate()) {
            active.put(event.getServletRequest(), controller);
        }
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        RequestContextController controller = active.remove(event.getServletRequest());
        if (controller != null) {
            controller.deactivate();
        }
    }
}
