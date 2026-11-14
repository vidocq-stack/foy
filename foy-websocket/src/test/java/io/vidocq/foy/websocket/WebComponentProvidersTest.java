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
package io.vidocq.foy.websocket;

import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.internal.gen.WebComponentRegistry.Tier;
import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The components Foy creates for this module reach its component registry as service providers:
 * neither the Class-File tier nor the logged reflective one, on the class path or the module path.
 */
class WebComponentProvidersTest {

    private final WebComponentRegistry registry = WebComponentRegistry.forClassLoader(getClass().getClassLoader());

    @Test
    void theInitializerIsAServiceProvider() {
        WebComponent component = registry.lookup(WebSocketInitializer.class);

        assertEquals(Tier.SERVICE_LOADER, registry.tierOf(WebSocketInitializer.class));
        assertEquals(Kind.INITIALIZER, component.descriptor().kind());
        assertInstanceOf(WebSocketInitializer.class, component.newInstance());
    }

    @Test
    void theUpgradeHandlerIsAServiceProvider() {
        WebComponent component = registry.lookup(FoyUpgradeHandler.class);

        assertEquals(Tier.SERVICE_LOADER, registry.tierOf(FoyUpgradeHandler.class));
        assertEquals(Kind.PLAIN, component.descriptor().kind());
        assertInstanceOf(FoyUpgradeHandler.class, component.newInstance());
    }
}
