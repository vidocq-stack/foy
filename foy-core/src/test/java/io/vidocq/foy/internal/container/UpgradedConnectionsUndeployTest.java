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
package io.vidocq.foy.internal.container;

import io.vidocq.foy.internal.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How an undeploy closes the upgraded connections of its context (Servlet 6.1 section 2.3.3.5): all at
 * once, each on a virtual thread of its own, and for one shared deadline.
 */
@Timeout(30)
class UpgradedConnectionsUndeployTest {

    private static final String CONTEXT_LOGGER = VidocqServletContext.class.getName();
    private static final int CONNECTIONS = 3;

    private final VidocqServletContext context = new VidocqServletContext("/");

    /** No connection closes on the undeploy's thread: each gets a virtual thread of its own. */
    @Test
    void eachConnectionClosesOnAVirtualThreadOfItsOwn() {
        List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int i = 0; i < CONNECTIONS; i++) {
            assertTrue(context.registerUpgradedConnection(() -> threads.add(Thread.currentThread())));
        }
        context.closeUpgradedConnections();
        assertEquals(CONNECTIONS, threads.size());
        assertEquals(CONNECTIONS, Set.copyOf(threads).size(), "two connections closed on one thread");
        for (Thread thread : threads) {
            assertTrue(thread.isVirtual(), thread.toString());
            assertNotSame(Thread.currentThread(), thread);
        }
    }

    /**
     * Connections that never finish closing hold the undeploy for one deadline in all, not one each, and a
     * WARNING counts them.
     */
    @Test
    void stuckConnectionsHoldTheUndeployForOneDeadlineInAll() {
        var release = new CountDownLatch(1);
        for (int i = 0; i < CONNECTIONS; i++) {
            assertTrue(context.registerUpgradedConnection(release::await));
        }
        try (var log = LogCapture.of(CONTEXT_LOGGER)) {
            long start = System.nanoTime();
            context.closeUpgradedConnections();
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            Duration grace = VidocqServletContext.UPGRADED_CONNECTIONS_GRACE;
            assertTrue(took.compareTo(grace.multipliedBy(2)) < 0, "the undeploy took " + took);
            assertTrue(log.warnings().stream().anyMatch(w -> w.startsWith(CONNECTIONS + " upgraded connections")),
                    log.warnings().toString());
        } finally {
            release.countDown();
        }
    }

    /**
     * An {@code Error} from the end of one connection (an application's {@code onClose} or
     * {@code destroy()}) stops neither the others nor the undeploy: it is logged.
     */
    @Test
    void anErrorEndingOneConnectionStopsNeitherTheOthersNorTheUndeploy() {
        var failure = new ExceptionInInitializerError("destroy() failed");
        var ended = new CopyOnWriteArrayList<String>();
        assertTrue(context.registerUpgradedConnection(() -> {
            throw failure;
        }));
        assertTrue(context.registerUpgradedConnection(() -> ended.add("the other")));
        try (var log = LogCapture.of(CONTEXT_LOGGER)) {
            assertDoesNotThrow(context::closeUpgradedConnections);
            assertEquals(List.of("the other"), ended);
            assertEquals(List.of(failure), log.thrown());
        }
    }

    /** A connection whose close throws is logged, and the others still close. */
    @Test
    void aFailingCloseIsLoggedAndTheOthersStillClose() {
        var failure = new IllegalStateException("close failed");
        var ended = new CopyOnWriteArrayList<String>();
        assertTrue(context.registerUpgradedConnection(() -> {
            throw failure;
        }));
        assertTrue(context.registerUpgradedConnection(() -> ended.add("the other")));
        try (var log = LogCapture.of(CONTEXT_LOGGER)) {
            context.closeUpgradedConnections();
            assertEquals(List.of("the other"), ended);
            assertEquals(List.of(failure), log.thrown());
        }
    }

    /** Once undeployed, a context refuses new connections. */
    @Test
    void aConnectionRegisteredAfterTheUndeployIsRefused() {
        context.closeUpgradedConnections();
        assertFalse(context.registerUpgradedConnection(() -> fail("a refused connection is not closed by the context")));
    }
}
