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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request that enters a context while its undeploy starts (Servlet 6.1 section 2.3.4): it is either
 * refused, or the undeploy waits for it before it goes on to {@code destroy()}, never neither.
 */
@Timeout(120)
class RequestsInServiceRaceTest {

    private static final int ROUNDS = 20_000;
    /** How long an admitted request stays in service before it looks whether the undeploy went on. */
    private static final int SPINS = 2_000;

    /** What one round saw. */
    private enum Outcome { REFUSED, WAITED_FOR, NEITHER }

    @Test
    void aRequestEnteringAsTheUndeployStartsIsRefusedOrWaitedFor() throws InterruptedException {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) counts.put(outcome, 0);
        for (int i = 0; i < ROUNDS; i++) {
            counts.merge(round(), 1, Integer::sum);
        }
        assertEquals(0, counts.get(Outcome.NEITHER), counts.toString());
    }

    /**
     * One request and one undeploy, released together. The request, once admitted, stays in service for
     * a moment and then looks whether the undeploy went past its wait; the undeploy, once past its wait,
     * looks whether the request is still in service. Either sighting is a request neither refused nor
     * waited for.
     */
    private static Outcome round() throws InterruptedException {
        var context = new VidocqServletContext("/");
        var go = new CountDownLatch(1);
        var inService = new AtomicBoolean();
        var pastTheWait = new AtomicBoolean();
        var neither = new AtomicBoolean();
        var refused = new AtomicBoolean();
        Thread request = Thread.ofVirtual().start(() -> {
            awaitQuietly(go);
            if (!context.enterRequest()) {
                refused.set(true);
                return;
            }
            inService.set(true);
            try {
                for (int s = 0; s < SPINS; s++) Thread.onSpinWait();
                if (pastTheWait.get()) neither.set(true);
            } finally {
                inService.set(false);
                context.exitRequest();
            }
        });
        Thread undeploy = Thread.ofVirtual().start(() -> {
            awaitQuietly(go);
            context.refuseRequests();
            context.awaitRequestsInService(System.nanoTime());
            pastTheWait.set(true);
            if (inService.get()) neither.set(true);
        });
        go.countDown();
        assertTrue(request.join(java.time.Duration.ofSeconds(10)), "the request did not end");
        assertTrue(undeploy.join(java.time.Duration.ofSeconds(10)), "the undeploy did not end");
        if (neither.get()) return Outcome.NEITHER;
        return refused.get() ? Outcome.REFUSED : Outcome.WAITED_FOR;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
