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
package io.vidocq.foy.internal.session;

import io.vidocq.foy.spi.session.SessionStore;

import jakarta.servlet.http.HttpSession;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Memory storage (local process) of sessions. No clustering.
 */
public final class InMemorySessionStore implements SessionStore {

    private final ConcurrentMap<String, HttpSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<HttpSession> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public void put(HttpSession session) {
        sessions.put(session.getId(), session);
    }

    @Override
    public void remove(String id) {
        sessions.remove(id);
    }

    @Override
    public int size() {
        return sessions.size();
    }

    @Override
    public Collection<HttpSession> sessions() {
        return List.copyOf(sessions.values());
    }
}
