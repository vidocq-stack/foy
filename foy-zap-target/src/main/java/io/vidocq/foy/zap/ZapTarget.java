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
package io.vidocq.foy.zap;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.servlet.ServletException;

import java.util.concurrent.CountDownLatch;

/**
 * Boots Foy on Chappe with Vauban, as the getting-started page does, at the root context, on
 * {@code 127.0.0.1}. The port is the first argument, else the {@code FOY_ZAP_PORT} environment variable,
 * else 18080. The process serves until it is stopped.
 */
public final class ZapTarget {

    private static final System.Logger LOG = System.getLogger(ZapTarget.class.getName());

    static final int DEFAULT_PORT = 18080;

    private ZapTarget() {
    }

    public static void main(String[] args) throws ServletException, InterruptedException {
        int port = port(args, System.getenv("FOY_ZAP_PORT"));
        try (var container = VaubanContainer.builder().scanClasspath().build();
             var mounted = FoyChappeBoot.builder()
                     .beanManager(container.getBeanManager())
                     .contextPath("/")
                     .build()
                     .orElseThrow(() -> new IllegalStateException("No web component discovered"));
             var server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build()) {
            server.start();
            LOG.log(System.Logger.Level.INFO, "Foy ZAP target listening on http://127.0.0.1:{0,number,#}/", port);
            new CountDownLatch(1).await();
        }
    }

    /** The port to listen on: the first argument, else {@code fromEnvironment}, else {@link #DEFAULT_PORT}. */
    static int port(String[] args, String fromEnvironment) {
        if (args.length > 0) return Integer.parseInt(args[0]);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return Integer.parseInt(fromEnvironment.trim());
        return DEFAULT_PORT;
    }
}
