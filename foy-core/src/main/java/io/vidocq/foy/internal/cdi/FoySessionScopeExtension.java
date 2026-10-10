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
package io.vidocq.foy.internal.cdi;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;

/**
 * Portable extension giving CDI Full containers (Weld, any Full implementation) Foy's context for
 * {@code @SessionScoped} (foy#21). CDI 4.1 §17.2 lets an extension register a context object for a
 * built-in scope through {@code AfterBeanDiscovery}; a build compatible extension may not (§6.7),
 * which is why Vauban gets the context from foy-cdi-vauban instead.
 *
 * <p>Found through {@code META-INF/services} on a class path and through the {@code provides} of
 * foy-core's module descriptor on a module path. Vauban ignores portable extensions.</p>
 */
public class FoySessionScopeExtension implements Extension {

    /** Public no-argument constructor, required by {@code ServiceLoader}. */
    public FoySessionScopeExtension() {}

    /**
     * Registers the context. The container may register its own session context too (Weld SE
     * registers an inactive bound one): CDI only forbids two <em>active</em> contexts for a scope.
     *
     * @param event the container's event
     */
    public void registerSessionContext(@Observes AfterBeanDiscovery event) {
        event.addContext(new FoySessionContext());
    }
}
