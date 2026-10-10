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

import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code HttpServletRequest.upgrade} refuses HTTP/2, which has no Upgrade mechanism (RFC 9113 section 8.6). */
class UpgradeContractTest {

    public static final class Handler implements HttpUpgradeHandler {
        @Override public void init(WebConnection wc) {}
        @Override public void destroy() {}
    }

    @Test
    void upgradeOverHttp2IsAnIoException() {
        var local = new InetSocketAddress("127.0.0.1", 8080);
        var chappe = new PushBuilderContractTest.FakeRequest(HttpVersion.HTTP_2, true,
                new InetSocketAddress("127.0.0.1", 50000), local);
        var request = new HttpServletRequestImpl(chappe, new VidocqServletContext("/ctx"), "/ctx", "/x", null);
        var e = assertThrows(IOException.class, () -> request.upgrade(Handler.class));
        assertEquals("HTTP upgrade is not supported over HTTP/2", e.getMessage());
    }
}
