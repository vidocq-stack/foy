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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpSessionStore#isSession}: the instance of the built-in {@code HttpSession} bean is the session the store
 * holds, which Vauban leaves out when the session is written at a swap.
 */
class HttpSessionStoreTest {

    @Test
    void aStoreOfASessionRecognisesThatSessionOnly() {
        HttpSession session = session();
        var store = HttpSessionStore.of(session);

        assertTrue(store.isSession(session));
        assertFalse(store.isSession(session()), "another session");
        assertFalse(store.isSession("a bean's state"));
        assertFalse(store.isSession(null));
    }

    @Test
    void aStoreOfARequestRecognisesTheRequestsSession() {
        HttpSession session = session();
        var store = HttpSessionStore.of(requestWith(session));

        assertTrue(store.isSession(session));
        assertFalse(store.isSession(session()), "another session");
    }

    @Test
    void beforeTheSessionExistsNothingIsTheSession() {
        HttpServletRequest request = requestWith(null);
        var store = HttpSessionStore.of(request);

        assertFalse(store.isSession(null));
        assertFalse(store.isSession(request));
    }

    private static HttpSession session() {
        return (HttpSession) Proxy.newProxyInstance(HttpSessionStoreTest.class.getClassLoader(),
                new Class<?>[] {HttpSession.class}, (proxy, method, args) -> null);
    }

    /** A request whose {@code getSession(false)} gives {@code session}; it creates none. */
    private static HttpServletRequest requestWith(HttpSession session) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpSessionStoreTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) ->
                        "getSession".equals(method.getName()) && args != null && Boolean.FALSE.equals(args[0])
                                ? session : null);
    }
}
