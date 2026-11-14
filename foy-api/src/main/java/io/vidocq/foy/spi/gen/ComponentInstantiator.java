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
package io.vidocq.foy.spi.gen;

import jakarta.servlet.ServletException;

/**
 * Creates application components through Foy's component factory (with CDI injection when a
 * CDI container is present). Foy publishes one as the {@code ServletContext} attribute
 * {@link #ATTRIBUTE}, so a module such as {@code foy-websocket} can instantiate its endpoints
 * without depending on Foy's internals.
 */
public interface ComponentInstantiator {

    /** Name of the {@code ServletContext} attribute holding the instantiator. */
    String ATTRIBUTE = "io.vidocq.foy.spi.gen.ComponentInstantiator";

    /**
     * Creates an instance of {@code type}.
     *
     * @param type the component class
     * @param <T>  the component type
     * @return a new, injected instance
     * @throws ServletException when the instance cannot be created
     */
    <T> T newInstance(Class<T> type) throws ServletException;
}
