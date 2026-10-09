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
package io.vidocq.foy.spi.session;

import jakarta.servlet.http.HttpSession;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@link HttpSession} storage SPI.
 * <p>
 * A third-party implementation (Redis, JDBC, clustered storage, etc.) can
 * replace the default in-memory store by exposing this service via
 * {@code ServiceLoader}.
 * </p>
 */
public interface SessionStore {

    /** Retrieves a session by its ID, if it exists. */
    Optional<HttpSession> get(String id);

    /** Saves a new session. */
    void put(HttpSession session);

    /** Deletes a session (typically on invalidate or expiration). */
    void remove(String id);

    /** Number of sessions currently stored (diagnostic). */
    int size();

    /**
     * Re-keys {@code session}, whose {@link HttpSession#getId()} already returns its new id, from
     * {@code oldId} (Servlet 6.1 section 7.3, {@code HttpServletRequest.changeSessionId}). The
     * default stores the session under its new id before removing the old key, so that the
     * session is never absent; a store that can move a key atomically should override it.
     */
    default void rename(String oldId, HttpSession session) {
        put(session);
        remove(oldId);
    }

    /**
     * A snapshot of the sessions held locally, scanned by the container's expiry reaper and
     * invalidated on undeploy. The default is empty: a remote store that expires its entries
     * itself does not need the container to scan it.
     */
    default Collection<HttpSession> sessions() {
        return List.of();
    }
}
