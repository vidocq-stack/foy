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
package io.vidocq.foy.it.session;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.vauban.core.container.VaubanContainer;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The target: Foy with Vauban, the session context coming from vauban-webcontexts, which
 * foy-cdi-vauban drives from Foy's request and session events. Nothing is configured for it:
 * Foy finds foy-cdi-vauban's CdiContextListeners when it is given Vauban's BeanManager.
 */
class VaubanSessionTest extends SessionScopeScenarios {

    private static VaubanContainer container;
    private static Server server;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        // The class path is scanned for the build-compatible extensions, vauban-webcontexts' among
        // them, as for an application. The scenario beans are named: their jar is compiled without
        // Vauban's annotation processor, so it carries no bean index for the scan to read.
        container = VaubanContainer.builder()
                .scanClasspath()
                .addBeanClass(Cart.class)
                .addBeanClass(SessionEvents.class)
                .build();
        var mounted = FoyChappeBoot.builder()
                .beanManager(container.getBeanManager())
                .classLoader(VaubanSessionTest.class.getClassLoader())
                .build()
                .orElseThrow();
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
        if (container != null) {
            container.close();
        }
    }

    @Override
    protected String base() {
        return base;
    }
}
