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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.internal.session.SessionManager.SessionImport;
import io.vidocq.foy.spi.security.Identity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identity a session-based login caches in the session (a container note) moves with the session when its
 * {@link Principal} can be written; otherwise only that note is left out, and that user logs in again.
 */
class CachedIdentityTransferTest {

    private static final String CART = "cart";
    private static final String ITEMS = "3 items";
    private static final String FORM = "FORM";

    private final List<SessionManager> managers = new ArrayList<>();

    @AfterEach
    void closeManagers() {
        managers.forEach(SessionManager::close);
    }

    @Test
    void anIdentityWithASerializablePrincipalMoves() {
        var source = manager();
        var session = source.createNew();
        session.setNote(FormAuthenticator.IDENTITY_NOTE,
                new WebSecurity.CachedIdentity(Identity.of("ada", Set.of("admin")), FORM));

        var target = manager();
        assertEquals(new SessionImport(1, 0, 0),
                target.importSessions(source.closeExporting(), CachedIdentityTransferTest.class.getClassLoader()));

        var cached = assertInstanceOf(WebSecurity.CachedIdentity.class,
                stored(target, session.getId()).note(FormAuthenticator.IDENTITY_NOTE));
        assertEquals("ada", cached.identity().name());
        assertEquals(Set.of("admin"), cached.identity().roles());
        assertEquals(FORM, cached.authType());
    }

    @Test
    void anIdentityWithAnUnwritablePrincipalIsLeftOutAndTheSessionMoves() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(CART, ITEMS);
        session.setNote(FormAuthenticator.IDENTITY_NOTE,
                new WebSecurity.CachedIdentity(new Identity(new LocalPrincipal("ada"), Set.of("admin")), FORM));
        byte[] export;
        List<String> infos;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            export = source.closeExporting();
            infos = log.infos();
        }

        var target = manager();
        assertEquals(new SessionImport(1, 0, 0),
                target.importSessions(export, CachedIdentityTransferTest.class.getClassLoader()));
        var moved = stored(target, session.getId());
        assertEquals(ITEMS, moved.getAttribute(CART));
        assertNull(moved.note(FormAuthenticator.IDENTITY_NOTE));
        assertEquals(1, infos.size(), infos::toString);
        assertTrue(infos.getFirst().contains(WebSecurity.CachedIdentity.class.getName()), infos::toString);
        assertFalse(infos.getFirst().contains(session.getId()), infos::toString);
    }

    private SessionManager manager() {
        var manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
        managers.add(manager);
        return manager;
    }

    private static HttpSessionImpl stored(SessionManager manager, String id) {
        return assertInstanceOf(HttpSessionImpl.class, manager.store().get(id).orElse(null));
    }

    /** A principal of an authentication hook that cannot be written. */
    private record LocalPrincipal(String name) implements Principal {
        @Override public String getName() { return name; }
    }
}
