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
package io.vidocq.foy.spi.cdi;

import jakarta.enterprise.inject.spi.BeanManager;
import java.util.EventListener;
import java.util.List;

/**
 * Listeners that drive CDI contexts from the servlet container's events, for a given CDI container.
 * CDI Lite leaves the session context to the container that implements Servlet (CDI 4.1 §6.7.2), and
 * each CDI implementation drives it its own way: an adapter such as {@code foy-cdi-vauban} provides
 * them, and Foy registers them when it is given a {@code BeanManager}, ahead of the application's
 * listeners, so they see a request or a session first and are told last that it ended.
 *
 * <p>Discovered with {@link java.util.ServiceLoader}. Unlike a {@code ServletContainerInitializer},
 * a provider does not make Foy start an application that has no web component.</p>
 */
public interface CdiContextListeners {

    /**
     * The listeners for the CDI container that owns {@code beanManager}, among
     * {@code ServletRequestListener} and {@code HttpSessionListener}; empty when this provider does
     * not serve that container.
     */
    List<EventListener> listeners(BeanManager beanManager);
}
