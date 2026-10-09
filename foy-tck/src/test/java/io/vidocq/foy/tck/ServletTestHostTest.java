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
package io.vidocq.foy.tck;

import io.vidocq.chappe.api.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServletTestHostTest {

    private static HttpResponse<String> get(ServletTestHost host, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void routesByContextPathBoundary() throws Exception {
        try (var host = new ServletTestHost()) {
            host.mount("/ctx", r -> Response.ok("ctx"));
            assertEquals("ctx", get(host, "/ctx").body());
            assertEquals("ctx", get(host, "/ctx/x").body());
            assertEquals("ctx", get(host, "/ctx;jsessionid=abc/x").body());
            assertEquals(404, get(host, "/ctx2/x").statusCode());
            assertEquals(404, get(host, "/other").statusCode());
        }
    }

    @Test
    void longestPrefixWinsAndRootGetsTheRest() throws Exception {
        try (var host = new ServletTestHost()) {
            host.mount("/", r -> Response.ok("root"));
            host.mount("/ctx", r -> Response.ok("ctx"));
            assertEquals("ctx", get(host, "/ctx/a").body());
            assertEquals("root", get(host, "/ctx2/a").body());
            assertEquals("root", get(host, "/a").body());
            host.unmount("/ctx");
            assertEquals("root", get(host, "/ctx/a").body());
        }
    }

    @Test
    void trailingSlashOfAContextPathIsNormalised() throws Exception {
        try (var host = new ServletTestHost()) {
            host.mount("/ctx/", r -> Response.ok("ctx"));
            assertEquals("ctx", get(host, "/ctx/x").body());
            host.unmount("/ctx");
            assertEquals(404, get(host, "/ctx/x").statusCode());
            host.mount("/ctx", r -> Response.ok("again"));
            assertThrows(IllegalStateException.class, () -> host.mount("/ctx/", r -> Response.ok("dup")));
        }
    }
}
