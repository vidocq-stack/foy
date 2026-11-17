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
package io.vidocq.foy.cdi.vauban;

import io.vidocq.vauban.webcontexts.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * An {@link HttpSession} as the storage of Vauban's session context. Built from a request, it
 * creates the session on the first instance stored, and only then; built from a session, it adapts
 * that session, for its destruction. It tells Vauban which instance is the session itself, which
 * stays out of the session when the session is written at a Vidocq dev swap.
 */
final class HttpSessionStore implements SessionStore {

    private final HttpServletRequest request;
    private final HttpSession session;

    private HttpSessionStore(HttpServletRequest request, HttpSession session) {
        this.request = request;
        this.session = session;
    }

    static HttpSessionStore of(HttpServletRequest request) {
        return new HttpSessionStore(request, null);
    }

    static HttpSessionStore of(HttpSession session) {
        return new HttpSessionStore(null, session);
    }

    private HttpSession existing() {
        return session != null ? session : request.getSession(false);
    }

    @Override
    public Object getAttribute(String name) {
        HttpSession s = existing();
        return s == null ? null : s.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        (session != null ? session : request.getSession(true)).setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        HttpSession s = existing();
        if (s != null) {
            s.removeAttribute(name);
        }
    }

    @Override
    public Collection<String> attributeNames() {
        HttpSession s = existing();
        return s == null ? List.of() : Collections.list(s.getAttributeNames());
    }

    /**
     * The session itself, the same object for every request of that session; before the session
     * exists, the request, since no other request can share a session not yet created.
     */
    @Override
    public Object mutex() {
        HttpSession s = existing();
        return s != null ? s : request;
    }

    /**
     * Whether {@code instance} is the session itself, the same object: the instance of the {@code HttpSession} bean
     * ({@link ServletBuiltInBeans}), which the session context stores in that session. Vauban leaves that entry out
     * when the session is written at a swap, and the bean gives the imported session again at its first use: without
     * it, every session that holds the bean would hold itself, and could not move.
     */
    @Override
    @SuppressWarnings("ReferenceEquality") // the session object itself is the contract, never an equal one
    public boolean isSession(Object instance) {
        HttpSession s = existing();
        return s != null && s == instance;
    }
}
