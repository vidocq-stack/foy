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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.foy.spi.cdi.CdiContextListeners;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSessionListener;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class VaubanCdiContextListenersTest {

    @Test
    void isProvidedAsTheServiceFoyLoads() {
        // Foy (foy-chappe) declares the `uses`; this module only provides.
        var descriptor = VaubanCdiContextListeners.class.getModule().getDescriptor();
        assertTrue(descriptor.provides().stream().anyMatch(p ->
                        p.service().equals(CdiContextListeners.class.getName())
                                && p.providers().contains(VaubanCdiContextListeners.class.getName())),
                "provides " + CdiContextListeners.class.getName() + ": " + descriptor.provides());
    }

    @Test
    void drivesTheSessionContextOfVauban() {
        try (VaubanContainer container = VaubanContainer.builder().build()) {
            var listeners = new VaubanCdiContextListeners().listeners(container.getBeanManager());

            assertEquals(1, listeners.size());
            assertTrue(listeners.getFirst() instanceof ServletRequestListener);
            assertTrue(listeners.getFirst() instanceof HttpSessionListener);
        }
    }

    @Test
    void leavesAnotherContainerAlone() {
        BeanManager other = stub(BeanManager.class);

        assertTrue(new VaubanCdiContextListeners().listeners(other).isEmpty());
        assertTrue(new VaubanCdiContextListeners().requestContextListeners(other).isEmpty());
    }

    @Test
    void bindsNoRequestWhenTheServletBuiltInBeansAreNotDeployed() {
        try (VaubanContainer container = VaubanContainer.builder().build()) {
            assertTrue(new VaubanCdiContextListeners().requestContextListeners(container.getBeanManager()).isEmpty());
        }
    }

    @Test
    void aRequestWithoutRequestContextIsLeftUnbound() {
        try (VaubanContainer container = VaubanContainer.builder().scanClasspath().build()) {
            BeanManager beanManager = container.getBeanManager();
            var bindings = new VaubanCdiContextListeners().requestContextListeners(beanManager);
            assertEquals(1, bindings.size());
            assertThrows(ContextNotActiveException.class, () -> beanManager.getContext(RequestScoped.class));
            ServletRequestListener binding = bindings.getFirst();
            ServletContext context = stub(ServletContext.class);

            assertDoesNotThrow(() -> binding.requestInitialized(
                    new ServletRequestEvent(context, stub(HttpServletRequest.class))));
            assertDoesNotThrow(() -> binding.requestInitialized(
                    new ServletRequestEvent(context, stub(ServletRequest.class))));
        }
    }

    /** An implementation of {@code type} whose every method returns {@code null}. */
    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(VaubanCdiContextListenersTest.class.getClassLoader(),
                new Class<?>[] {type}, (proxy, method, args) -> null));
    }
}
