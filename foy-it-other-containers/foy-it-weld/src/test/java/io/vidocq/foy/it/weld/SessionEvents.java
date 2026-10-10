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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.event.Observes;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the session context events (by session id) and the destroyed session beans (by bean id). */
@ApplicationScoped
public class SessionEvents {

    private final List<String> initialized = new CopyOnWriteArrayList<>();
    private final List<String> beforeDestroyed = new CopyOnWriteArrayList<>();
    private final List<String> destroyed = new CopyOnWriteArrayList<>();
    private final List<String> destroyedBeans = new CopyOnWriteArrayList<>();

    void onInitialized(@Observes @Initialized(SessionScoped.class) HttpSession session) {
        initialized.add(session.getId());
    }

    void onBeforeDestroyed(@Observes @BeforeDestroyed(SessionScoped.class) HttpSession session) {
        beforeDestroyed.add(session.getId());
    }

    void onDestroyed(@Observes @Destroyed(SessionScoped.class) HttpSession session) {
        destroyed.add(session.getId());
    }

    public void beanDestroyed(String beanId) {
        destroyedBeans.add(beanId);
    }

    public List<String> initialized() { return List.copyOf(initialized); }
    public List<String> beforeDestroyed() { return List.copyOf(beforeDestroyed); }
    public List<String> destroyed() { return List.copyOf(destroyed); }
    public List<String> destroyedBeans() { return List.copyOf(destroyedBeans); }
}
