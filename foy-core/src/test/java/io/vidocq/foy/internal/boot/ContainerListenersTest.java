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
package io.vidocq.foy.internal.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The container's own listeners, registered through {@link DeployOptions#withContainerListeners}: in place before
 * the initializers run, a container {@code ServletContextListener} hears the context before
 * {@code ServletContainerInitializer.onStartup} and is told last that it ended, also when the deployment fails.
 */
class ContainerListenersTest {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    void aContainerContextListenerHearsTheContextBeforeTheInitializersAndLeavesLast() {
        var model = WebAppModel.builder("/container-listeners")
                .initializer((classes, ctx) -> events.add("initializer onStartup"))
                .listener(new ListenerDecl(ServletContextListener.class, () -> tracing("application")))
                .build();

        try (var _ = WebAppDeployer.deploy(model, withContainerListener())) {
            assertEquals(List.of("hook", "container initialized", "initializer onStartup",
                    "application initialized"), List.copyOf(events));
            events.clear();
        }

        assertEquals(List.of("application destroyed", "container destroyed"), List.copyOf(events));
    }

    @Test
    void aFailingInitializerStillTellsTheContainerListenerThatTheContextEnded() {
        ServletContainerInitializer failing = (classes, ctx) -> {
            throw new IllegalStateException("failed on purpose");
        };
        var model = WebAppModel.builder("/container-listeners-failing")
                .initializer(failing)
                .listener(new ListenerDecl(ServletContextListener.class, () -> tracing("application")))
                .build();
        var options = withContainerListener();

        assertThrows(IllegalStateException.class, () -> WebAppDeployer.deploy(model, options));

        assertEquals(List.of("hook", "container initialized", "container destroyed"), List.copyOf(events));
    }

    private DeployOptions withContainerListener() {
        return DeployOptions.defaults(getClass().getClassLoader()).withContainerListeners(listeners -> {
            events.add("hook");
            listeners.registerFirst(tracing("container"));
        });
    }

    private ServletContextListener tracing(String name) {
        return new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                events.add(name + " initialized");
            }

            @Override
            public void contextDestroyed(ServletContextEvent event) {
                events.add(name + " destroyed");
            }
        };
    }
}
