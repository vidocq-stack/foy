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

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.spi.security.CurrentRequest;
import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.RequestAuthentication;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request a runtime above Foy (a security context, a CDI producer) needs without being handed it: Foy makes it
 * reachable for the code that serves the request, and for nothing else.
 */
class CurrentRequestEndToEndTest {

    public static class Peek extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var current = CurrentRequest.get();
            resp.getWriter().write(current.map(r -> r.getRequestURI() + " same=" + r.equals(req)
                    + " user=" + r.getRemoteUser()).orElse("none"));
        }
    }

    private static WebAppModel model() {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("peek", Peek.class, Peek::new, List.of("/peek"), Map.of(), -1, false))
                .build();
    }

    @Test
    void outsideARequestThereIsNoRequest() {
        assertTrue(CurrentRequest.get().isEmpty());
    }

    @Test
    void theServletSeesTheRequestItServes() throws Exception {
        try (var app = new SecureApp(model())) {
            assertEquals("/peek same=true user=null", app.get("/peek").body());
        }
    }

    @Test
    void theRequestCarriesTheCallerTheHookEstablished() throws Exception {
        var hook = RequestAuthentication.authenticated(Identity.of("alice", Set.of("admin")), "HTTP");
        RequestAuthentication fixed = (request, response, mandatory) -> hook;
        try (var app = new SecureApp(model(), SecuritySettings.DEFAULTS.withIdentityStore(SecureApp.USERS)
                .withRequestAuthentication(fixed))) {
            assertEquals("/peek same=true user=alice", app.get("/peek").body());
        }
    }
}
