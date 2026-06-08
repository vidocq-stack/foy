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
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;

import java.net.ServerSocket;

/**
 * Test-only helper which starts a {@link Server} Chappe on a free port,
 * with retry to absorb port-allocation races typical of tests
 * (a port obtained via {@link ServerSocket} can be taken before the bind).
 */
final class TestServerLauncher {

    static final class Result {
        final Server server;
        final int port;
        Result(Server server, int port) { this.server = server; this.port = port; }
    }

    static Result start(Handler handler) {
        RuntimeException last = null;
        for (int i = 0; i < 5; i++) {
            int port;
            try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
            catch (Exception e) { throw new RuntimeException(e); }
            try {
                Server server = Server.builder().host("127.0.0.1").port(port).handler(handler).build();
                server.start();
                return new Result(server, port);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private TestServerLauncher() {}
}
