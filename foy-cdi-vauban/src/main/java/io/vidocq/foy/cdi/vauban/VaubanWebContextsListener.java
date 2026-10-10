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

import io.vidocq.vauban.webcontexts.WebContexts;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

/**
 * Drives Vauban's session context from the servlet container's events (CDI 4.1 §6.7.2): the
 * request's session is bound to the thread that serves the request, a new session fires
 * {@code @Initialized(SessionScoped.class)}, and an invalidated or expired session has its
 * {@code @SessionScoped} instances destroyed, between {@code @BeforeDestroyed} and
 * {@code @Destroyed}.
 *
 * <p>The session is not created up front: the first {@code @SessionScoped} bean a request uses
 * creates it, as {@code getSession()} would.</p>
 */
final class VaubanWebContextsListener implements ServletRequestListener, HttpSessionListener {

    private final BeanManager beanManager;

    VaubanWebContextsListener(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        if (event.getServletRequest() instanceof HttpServletRequest request) {
            WebContexts.activateSession(HttpSessionStore.of(request));
        }
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        WebContexts.deactivateSession();
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        WebContexts.sessionInitialized(beanManager, event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        WebContexts.destroySession(beanManager, HttpSessionStore.of(event.getSession()), event.getSession());
    }
}
