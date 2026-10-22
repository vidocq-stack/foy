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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Real-time pauses for the tests whose subject reads the wall clock (session expiry, last access
 * times) or whose assertion is that nothing happens in the meantime. A pause never stops early:
 * a spurious wake-up goes back to waiting, and an interruption is reported like
 * {@link Thread#sleep(long)} does.
 */
public final class TestPause {

    private TestPause() {
        // not instantiable
    }

    /**
     * Waits for at least {@code millis} milliseconds.
     *
     * @param millis how long to wait
     * @throws InterruptedException if the calling thread is interrupted meanwhile
     */
    public static void millis(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        long left = TimeUnit.MILLISECONDS.toNanos(millis);
        while (left > 0) {
            LockSupport.parkNanos(left);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            left = deadline - System.nanoTime();
        }
    }
}
