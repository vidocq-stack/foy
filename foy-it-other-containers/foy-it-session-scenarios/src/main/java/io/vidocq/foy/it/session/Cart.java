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

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One per HTTP session: an id to tell instances apart, and what the client added. */
@SessionScoped
public class Cart implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id = UUID.randomUUID().toString();
    private final List<String> items = new ArrayList<>();

    @Inject
    SessionEvents events;

    public String id() {
        return id;
    }

    public synchronized void add(String item) {
        items.add(item);
    }

    public synchronized List<String> items() {
        return List.copyOf(items);
    }

    @PreDestroy
    void destroyed() {
        events.cartDestroyed(id);
    }
}
