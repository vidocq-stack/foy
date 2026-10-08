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

/**
 * Build-time generated (or runtime-derived) description of one web component class.
 *
 * <p>An annotation processor emits one implementation per web component class and
 * registers it as a service, so the container can learn about servlets, filters,
 * listeners and initializers without reflective annotation scanning.
 */
public interface WebComponent {

    /** The described web component class. */
    Class<?> type();

    /** The static metadata of the component (mapping, init parameters, security, ...). */
    WebComponentDescriptor descriptor();

    /**
     * Creates a new instance of the component. Generated code calls the no-arg
     * constructor directly, avoiding reflection.
     *
     * @return a new, uninitialised instance of {@link #type()}
     */
    Object newInstance();
}
