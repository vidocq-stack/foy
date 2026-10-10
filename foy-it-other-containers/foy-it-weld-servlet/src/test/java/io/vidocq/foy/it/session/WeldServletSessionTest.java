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
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The reference: Weld's own Servlet integration, hosted by Foy, manages the session context. Foy
 * runs weld-servlet's ServletContainerInitializer and is given no BeanManager.
 */
class WeldServletSessionTest extends SessionScopeScenarios {

    private static Server server;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        var mounted = FoyChappeBoot.builder()
                .classLoader(WeldServletSessionTest.class.getClassLoader())
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
    }

    @Override
    protected String base() {
        return base;
    }
}
