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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts the CDI events of the session context (CDI 4.1 §6.7.2) and the carts destroyed. */
@ApplicationScoped
public class SessionEvents {

    private final AtomicInteger initialized = new AtomicInteger();
    private final AtomicInteger destroyed = new AtomicInteger();
    private final List<String> destroyedCarts = new CopyOnWriteArrayList<>();

    void onInitialized(@Observes @Initialized(SessionScoped.class) Object event) {
        initialized.incrementAndGet();
    }

    void onDestroyed(@Observes @Destroyed(SessionScoped.class) Object event) {
        destroyed.incrementAndGet();
    }

    void cartDestroyed(String id) {
        destroyedCarts.add(id);
    }

    /** {@code initialized|destroyed|cart ids destroyed, comma-separated}. */
    public String summary() {
        return initialized.get() + "|" + destroyed.get() + "|" + String.join(",", destroyedCarts);
    }
}
