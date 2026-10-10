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
import io.vidocq.vauban.core.container.VaubanBeanManager;
import jakarta.enterprise.inject.spi.BeanManager;
import java.util.EventListener;
import java.util.List;

/**
 * The session scope when Foy runs on Vauban (foy#21): a {@link VaubanWebContextsListener} that
 * binds each request's {@code HttpSession} to Vauban's session context ({@code vauban-webcontexts})
 * and ends the context with the session. Nothing for another CDI container.
 */
public final class VaubanCdiContextListeners implements CdiContextListeners {

    @Override
    public List<EventListener> listeners(BeanManager beanManager) {
        return beanManager instanceof VaubanBeanManager
                ? List.of(new VaubanWebContextsListener(beanManager))
                : List.of();
    }
}
