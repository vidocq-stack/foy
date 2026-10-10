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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * The session-scope behaviour every container under test must show (foy#21), over HTTP. Each
 * client is an {@link HttpClient} with its own cookie store, so it keeps its own session.
 * Subclasses start the server and give its base URL.
 */
public abstract class SessionScopeScenarios {

    protected abstract String base();

    @Test
    void oneClientKeepsItsCartAcrossRequests() throws Exception {
        HttpClient alice = client();
        String[] first = get(alice, "/cart/add?item=apple").split("\\|");
        String[] second = get(alice, "/cart/add?item=pear").split("\\|");

        assertEquals(first[0], second[0], "the same cart within a session");
        assertEquals("apple,pear", second[1]);
    }

    @Test
    void twoClientsHaveTwoCarts() throws Exception {
        String alice = get(client(), "/cart/add?item=apple").split("\\|")[0];
        String bob = get(client(), "/cart/add?item=apple").split("\\|")[0];

        assertNotEquals(alice, bob, "one cart per session");
    }

    @Test
    void invalidatingTheSessionDestroysTheCartAndStartsAnew() throws Exception {
        HttpClient alice = client();
        String before = get(alice, "/cart/add?item=apple").split("\\|")[0];

        get(alice, "/cart/invalidate");
        String after = get(alice, "/cart/show");

        assertNotEquals(before, after.split("\\|")[0], "a new cart after invalidate()");
        assertEquals("", after.split("\\|", -1)[1], "the new cart is empty: " + after);
        String[] events = get(client(), "/cart/events").split("\\|", -1);
        assertTrue(events[2].contains(before), "@PreDestroy ran for the invalidated cart: " + events[2]);
    }

    @Test
    void sessionContextEventsFire() throws Exception {
        HttpClient alice = client();
        get(alice, "/cart/add?item=apple");
        get(alice, "/cart/invalidate");

        String[] events = get(client(), "/cart/events").split("\\|", -1);
        assertTrue(Integer.parseInt(events[0]) > 0, "@Initialized(SessionScoped.class) fired: " + events[0]);
        assertTrue(Integer.parseInt(events[1]) > 0, "@Destroyed(SessionScoped.class) fired: " + events[1]);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private String get(HttpClient client, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }
}
