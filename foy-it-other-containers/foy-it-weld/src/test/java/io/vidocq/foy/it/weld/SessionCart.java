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
package io.vidocq.foy.it.weld;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.UUID;

/**
 * One instance per HTTP session. Serializable: {@code @SessionScoped} is a passivating scope, and
 * Weld refuses to deploy a non-passivation-capable bean in it (CDI 4.1 §17.5.5).
 */
@SessionScoped
public class SessionCart implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id = UUID.randomUUID().toString();

    @Inject
    SessionEvents events;

    @Inject
    RequestToken token;

    public String id() {
        return id;
    }

    /** Uses a request-scoped bean: Foy keeps a request context active while session beans are destroyed. */
    @PreDestroy
    void destroyed() {
        token.value();
        events.beanDestroyed(id);
    }
}
