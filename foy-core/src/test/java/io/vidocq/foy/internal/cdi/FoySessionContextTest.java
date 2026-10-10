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

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FoySessionContextTest {

    private final FoySessionContext context = new FoySessionContext();

    private static final Contextual<Object> BEAN = new Contextual<>() {
        @Override public Object create(CreationalContext<Object> creationalContext) { return new Object(); }
        @Override public void destroy(Object instance, CreationalContext<Object> creationalContext) {}
    };

    @Test
    void theScopeIsSessionScoped() {
        assertEquals(SessionScoped.class, context.getScope());
    }

    @Test
    void withoutABindingTheContextIsInactiveAndRefusesEveryCall() {
        assertFalse(context.isActive());
        assertThrows(ContextNotActiveException.class, () -> context.get(BEAN));
        assertThrows(ContextNotActiveException.class, () -> context.get(BEAN, null));
        assertThrows(ContextNotActiveException.class, () -> context.destroy(BEAN));
    }

    @Test
    void theExtensionRegistersAFoySessionContextAfterBeanDiscovery() {
        var added = new ArrayList<Object>();
        var event = (AfterBeanDiscovery) Proxy.newProxyInstance(AfterBeanDiscovery.class.getClassLoader(),
                new Class<?>[] {AfterBeanDiscovery.class}, (p, m, a) -> {
                    if (m.getName().equals("addContext")) {
                        added.add(a[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        new FoySessionScopeExtension().registerSessionContext(event);
        assertEquals(1, added.size());
        assertInstanceOf(FoySessionContext.class, added.getFirst());
    }
}
