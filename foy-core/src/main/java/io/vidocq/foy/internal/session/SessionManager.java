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

import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Coordinates the creation, resolution and expiration of {@link HttpSession}.
 *
 * <p>ID generation: 128 bits of {@link SecureRandom}, encoded in hex (32 chars).</p>
 * <p>Expiration: lazy check on each access — if
 * {@code now - lastAccessedTime > maxInactiveInterval * 1000}, la session est
 * removed from the store and considered non-existent.</p>
 */
public final class SessionManager {

    public static final String COOKIE_NAME = "JSESSIONID";

    private final SessionStore store;
    private final SecureRandom random = new SecureRandom();
    private final ServletContext servletContext;
    private final int defaultMaxInactiveSeconds;
    private ListenerRegistry listenerRegistry = new ListenerRegistry();

    public SessionManager(SessionStore store, ServletContext servletContext,
                          int defaultMaxInactiveSeconds) {
        this.store = store;
        this.servletContext = servletContext;
        this.defaultMaxInactiveSeconds = defaultMaxInactiveSeconds;
    }

    public void setListenerRegistry(ListenerRegistry registry) {
        this.listenerRegistry = registry;
    }

    public ListenerRegistry listenerRegistry() { return listenerRegistry; }

    /** Resolving an existing session by its ID, checking for expiration. */
    public HttpSessionImpl find(String id) {
        if (id == null) return null;
        HttpSession s = store.get(id).orElse(null);
        if (!(s instanceof HttpSessionImpl impl) || impl.isInvalidated()) return null;
        long idleMs = System.currentTimeMillis() - impl.getLastAccessedTime();
        if (impl.getMaxInactiveInterval() > 0
                && idleMs > impl.getMaxInactiveInterval() * 1000L) {
            store.remove(id);
            return null;
        }
        impl.markAccessed();
        return impl;
    }

    /** Creates a new session and stores it. */
    public HttpSessionImpl createNew() {
        String id = generateId();
        HttpSessionImpl s = new HttpSessionImpl(id, servletContext, this, defaultMaxInactiveSeconds);
        store.put(s);
        listenerRegistry.fireSessionCreated(s);
        return s;
    }

    /** Callback hook from {@link HttpSessionImpl#invalidate}. */
    void onInvalidate(HttpSessionImpl session) {
        listenerRegistry.fireSessionDestroyed(session);
        store.remove(session.getId());
    }

    public SessionStore store() { return store; }

    public int defaultMaxInactiveSeconds() { return defaultMaxInactiveSeconds; }

    private String generateId() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
