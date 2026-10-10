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
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

/** Server push is not supported: {@code newPushBuilder()} must report it with {@code null}. */
class PushBuilderContractTest {

    record FakeRequest(HttpVersion version, boolean isSecure, InetSocketAddress remoteAddress,
                       InetSocketAddress localAddress) implements Request {
        @Override public HttpMethod method() { return HttpMethod.GET; }
        @Override public URI uri() { return URI.create("http://localhost/x"); }
        @Override public String path() { return "/x"; }
        @Override public String query() { return null; }
        @Override public Headers headers() { return Headers.of("Host", "localhost"); }
        @Override public Body body() { return Body.empty(); }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
    }

    @Test
    void newPushBuilderIsNull() {
        var local = new InetSocketAddress("127.0.0.1", 8080);
        var chappe = new FakeRequest(HttpVersion.HTTP_2, true, new InetSocketAddress("127.0.0.1", 50000), local);
        var request = new HttpServletRequestImpl(chappe, new VidocqServletContext("/ctx"), "/ctx", "/x", null);
        assertNull(request.newPushBuilder());
    }
}
