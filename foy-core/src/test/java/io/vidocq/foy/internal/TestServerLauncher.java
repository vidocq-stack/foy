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

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;

import javax.net.ssl.SSLContext;

/**
 * Test-only helper which starts a Chappe {@link Server} on a port the system picks: the server
 * binds port 0 and the port it got is read back, so no other process can take the port between
 * the pick and the bind (BUG-20261114-06).
 */
final class TestServerLauncher {

    static final class Result {
        final Server server;
        final int port;
        Result(Server server, int port) { this.server = server; this.port = port; }
    }

    static Result start(Handler handler) {
        return launch(handler, null, null);
    }

    /** Same as {@link #start(Handler)}, over TLS with the given client-authentication mode. */
    static Result startTls(Handler handler, SSLContext sslContext, ClientAuth clientAuth) {
        return launch(handler, sslContext, clientAuth);
    }

    private static Result launch(Handler handler, SSLContext sslContext, ClientAuth clientAuth) {
        var builder = Server.builder().host("127.0.0.1").port(0).handler(handler);
        if (sslContext != null) builder = builder.tls(sslContext, clientAuth);
        Server server = builder.build();
        server.start();
        return new Result(server, server.port());
    }

    private TestServerLauncher() {}
}
