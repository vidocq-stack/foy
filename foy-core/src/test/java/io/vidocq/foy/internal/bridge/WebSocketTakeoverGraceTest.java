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
package io.vidocq.foy.internal.bridge;

import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The waits of a WebSocket takeover fit in the context's wait for its upgraded connections: a connection the
 * undeploy cuts ends within {@link WebSocketTakeover#UNDEPLOY_GRACE} plus {@link WebSocketTakeover#CUT_GRACE},
 * and the context still has time left for the application's {@code onClose} and {@code destroy()} before the
 * undeploy goes on (BUG-20261115-01).
 */
class WebSocketTakeoverGraceTest {

    @Test
    void aCutConnectionEndsWithinTheContextsGraceForItsUpgradedConnections() {
        Duration takeover = WebSocketTakeover.UNDEPLOY_GRACE.plus(WebSocketTakeover.CUT_GRACE);
        Duration context = VidocqServletContext.UPGRADED_CONNECTIONS_GRACE;
        assertTrue(takeover.compareTo(context) < 0,
                () -> "a cut takeover may wait " + takeover + ", the context waits " + context + " for all");
    }
}
