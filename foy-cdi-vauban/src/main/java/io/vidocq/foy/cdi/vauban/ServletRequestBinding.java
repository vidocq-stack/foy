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

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Puts each request Foy serves into the {@link ServletRequestHolder} of its request context. Foy registers it right
 * after the listener that activates the CDI request context ({@code CdiContextListeners.requestContextListeners}),
 * and fires it again on the threads of {@code AsyncContext.start} with that thread's request.
 */
final class ServletRequestBinding implements ServletRequestListener {

    private static final System.Logger LOG = System.getLogger(ServletRequestBinding.class.getName());

    /** The client proxy: each call reaches the holder of the request context active on the calling thread. */
    private final ServletRequestHolder holder;

    private ServletRequestBinding(ServletRequestHolder holder) {
        this.holder = holder;
    }

    /** The binding for the container of {@code beanManager}; empty when foy-cdi-vauban's beans are not deployed. */
    static Optional<ServletRequestBinding> of(BeanManager beanManager) {
        var holders = beanManager.createInstance().select(ServletRequestHolder.class);
        return holders.isResolvable() ? Optional.of(new ServletRequestBinding(holders.get())) : Optional.empty();
    }

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        if (!(event.getServletRequest() instanceof HttpServletRequest request)) {
            return;
        }
        try {
            holder.hold(request);
        } catch (ContextNotActiveException notActive) {
            // No request context for this request (no RequestContextController): no request-scoped bean works in
            // it either, the injected request included.
            LOG.log(System.Logger.Level.DEBUG, "No CDI request context for {0}: {1}", request.getRequestURI(),
                    notActive.toString());
        }
    }
}
