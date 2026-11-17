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

import io.vidocq.foy.spi.cdi.CdiContextListeners;
import io.vidocq.vauban.api.Passivation;
import io.vidocq.vauban.core.container.VaubanBeanManager;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequestListener;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.function.Supplier;

/**
 * The servlet container's side of CDI when Foy runs on Vauban: a {@link VaubanWebContextsListener} that binds
 * each request's {@code HttpSession} to Vauban's session context ({@code vauban-webcontexts}) and ends the context
 * with the session (foy#21), and the bindings behind the {@code HttpServletRequest}, {@code HttpSession} and
 * {@code ServletContext} beans ({@link ServletBuiltInBeans}): {@link ServletContextBinding} for the deployment's
 * context, {@link ServletRequestBinding} for each request, once its request context is active. Around the import
 * of the sessions of a previous deployment (a Vidocq dev swap), the binding of Vauban's container that their CDI
 * state reads back into. Nothing for another CDI container.
 */
public final class VaubanCdiContextListeners implements CdiContextListeners {

    @Override
    public List<EventListener> listeners(BeanManager beanManager) {
        if (!(beanManager instanceof VaubanBeanManager)) {
            return List.of();
        }
        List<EventListener> listeners = new ArrayList<>();
        listeners.add(new VaubanWebContextsListener(beanManager));
        ServletContextBinding.of(beanManager).ifPresent(listeners::add);
        return List.copyOf(listeners);
    }

    @Override
    public List<ServletRequestListener> requestContextListeners(BeanManager beanManager) {
        if (!(beanManager instanceof VaubanBeanManager)) {
            return List.of();
        }
        return ServletRequestBinding.of(beanManager).<List<ServletRequestListener>>map(List::of).orElseGet(List::of);
    }

    /**
     * Binds Vauban's container as the importing one while the sessions of a previous deployment are read
     * ({@link Passivation#importInto}): their {@code @SessionScoped} instances, the client proxies and the injected
     * {@code Event}, {@code Instance}, {@code Provider} and {@code BeanManager} they hold read back as this
     * container's. Runs the import as is for another CDI container.
     */
    @Override
    public <T> T aroundSessionImport(BeanManager beanManager, Supplier<T> importer) {
        if (!(beanManager instanceof VaubanBeanManager)) {
            return CdiContextListeners.super.aroundSessionImport(beanManager, importer);
        }
        return Passivation.importInto(beanManager, importer);
    }
}
