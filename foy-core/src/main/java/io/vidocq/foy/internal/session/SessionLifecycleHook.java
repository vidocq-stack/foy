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

/**
 * Foy-internal hook on the life of the sessions of one web application, used by the CDI session
 * context (foy#21). It is not an {@code HttpSessionListener}: the destroyed listeners run in one
 * loop that a throwing application listener ends, and the CDI context must wrap that loop (its
 * beans stay usable from the listeners, then they are destroyed whatever the listeners did).
 */
public interface SessionLifecycleHook {

    /** No hook: the destruction runs as is. */
    SessionLifecycleHook NONE = new SessionLifecycleHook() {
        @Override
        public void sessionCreated(HttpSessionImpl session) {}

        @Override
        public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
            destruction.run();
        }
    };

    /**
     * A session was stored, on the creating thread, before the application's
     * {@code sessionCreated} listeners run: the hook can bind the new session to the current
     * request first, so that a session bean used from those listeners resolves to it instead of
     * creating another session.
     */
    void sessionCreated(HttpSessionImpl session);

    /**
     * Wraps the Servlet half of an invalidation — the application's {@code sessionDestroyed}
     * listeners, then the unbinding of the attributes — which the hook must run exactly once, on the
     * calling thread. Called for {@code invalidate()}, expiry (lazy or by the reaper) and undeploy,
     * while the session is still in its store.
     */
    void aroundDestruction(HttpSessionImpl session, Runnable destruction);
}
